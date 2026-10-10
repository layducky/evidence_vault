# Phase 2 – Chain of custody with a hash chain

> Status: ⬜ Not started · Roadmap: `docs/PLAN.md` §3 Phase 2 · Implement with `/impl-phase 2 [step]`
> Rules: `rules/coding/concurrency.md`, `rules/coding/db.md`, `rules/testing/TEST_STRATEGY.md`

## 1. Goal
Every change to a case or evidence produces a **custody event** appended to that case's hash chain. Event `n`
stores `hash = sha256(prev_hash || canonical(payload))`. Prove three things:
(1) naive concurrent appends **lose events** (holes in the audit trail);
(2) two fixes keep the chain continuous under load;
(3) directly modifying a row in the DB is **detected** by the verify endpoint, and the DB blocks `UPDATE`/`DELETE` on the table.

**Invariant:** for each case, `seq` is contiguous from 1 to n, each `prev_hash` equals the `hash` of the immediately
preceding event, and every append call that returns success has exactly one corresponding row.

## 2. Scope
**In scope:** `custody_event` table, `CanonicalJson`, `HashChain`, two append strategies plus the naive version, events
written from casefile, evidence and retention, verify endpoint, append-only trigger.
**Out of scope:** digital signatures, external storage (WORM storage), partitioning (Phase 5).

## 3. Prerequisites
- Phase 1 done: holds and retention must also write custody events.

## 4. Changes vs PLAN.md
- **The naive version's anomaly is lost events, not forking.** `UNIQUE(case_id, seq)` exists from the first migration. The naive version
  reads the last `seq`/`hash` and then inserts `seq + 1`, so under concurrency the later transaction hits a unique violation.
  The accompanying business operation is rolled back or returns 500, i.e. the event is lost. The chain only actually **forks** if the
  constraint is missing. The doc will explain this as a layer of defense: the constraint turns silent data corruption into a
  visible error.
- The "1000 appends into 10 cases" test stays, but asserts that **the number of successful appends equals the number of rows and the chain is valid**.

## 5. Design

### Schema – `V3__custody_event.sql`
```sql
CREATE TABLE custody_event (
    id         UUID PRIMARY KEY,
    case_id    UUID         NOT NULL REFERENCES case_file (id),
    seq        BIGINT       NOT NULL CHECK (seq >= 1),
    actor      VARCHAR(128) NOT NULL,
    action     VARCHAR(64)  NOT NULL,     -- CASE_CREATED, EVIDENCE_REGISTERED, LEGAL_HOLD_PLACED, ...
    payload    JSONB        NOT NULL,
    prev_hash  CHAR(64)     NOT NULL,     -- 64 x '0' for seq = 1
    hash       CHAR(64)     NOT NULL,
    created_at TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT uq_custody_case_seq UNIQUE (case_id, seq)
);

-- Append-only at the database level.
CREATE FUNCTION custody_event_append_only() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    RAISE EXCEPTION 'custody_event is append-only' USING ERRCODE = 'insufficient_privilege';
END $$;
CREATE TRIGGER trg_custody_event_append_only
    BEFORE UPDATE OR DELETE ON custody_event
    FOR EACH ROW EXECUTE FUNCTION custody_event_append_only();
```

### API
| Method | Path | Success | Errors |
|---|---|---|---|
| GET | `/api/cases/{id}/custody` | 200 list of events ordered by `seq` | 404 |
| GET | `/api/cases/{id}/custody/verify` | 200 `{valid: true, length}` or `{valid: false, brokenAtSeq, reason}` | 404 |

### New classes
| Class | Package | Role | Transaction / isolation |
|---|---|---|---|
| `CanonicalJson` | `custody` | Canonical JSON: sorted keys, no whitespace, fixed format for numbers and `Instant` | – |
| `HashChain` | `custody` | `GENESIS`, `next(prevHash, payload)`, `verify(List<CustodyEvent>)` | – |
| `CustodyEvent`, `CustodyEventRepository` | `custody` | Entity (`payload` as `@JdbcTypeCode(SqlTypes.JSON)`) | – |
| `CustodyAppender` (interface) | `custody` | `append(caseId, actor, action, payload)` | `@Transactional(propagation = MANDATORY)`: **always runs inside the business operation's transaction** |
| `NaiveCustodyAppender` | `custody` | reads the last event then inserts; has a `pauseAfterRead` hook | READ COMMITTED |
| `AdvisoryLockCustodyAppender` (A) | `custody` | `SELECT pg_advisory_xact_lock(hashtext('custody:' \|\| :caseId))`, then read and insert | READ COMMITTED + advisory lock held until end of transaction |
| `RetryingCustodyAppender` (B) | `custody` | read and insert; on a unique violation **retry the whole business operation** (at the service layer, not just the insert statement) | READ COMMITTED + bounded retry on `23505` |
| `CustodyService`, `CustodyController` | `custody` | read chain, verify | read-only |

Configuration: `evidence-vault.custody.appender: advisory-lock | retrying | naive`.

## 6. Steps (code + tests together)

### - [ ] 2.1 `CanonicalJson` and `HashChain`
- **Code:** as in §5. No Spring; use a dedicated Jackson `ObjectMapper` configuration (`ORDER_MAP_ENTRIES_BY_KEYS`, `SORT_PROPERTIES_ALPHABETICALLY`).
- **Test:** `CanonicalJsonTest` (Unit): T-2.1 different key orders produce the same output string; T-2.2 `Instant` and
  `BigDecimal` are stable; T-2.3 Unicode is preserved.
  `HashChainTest` (Unit): T-2.4 fixed vectors (known SHA-256); T-2.5 verify a valid chain; T-2.6 a modified payload, a wrong
  `prev_hash` or a missing `seq` are each reported with the correct `brokenAtSeq`.
- **Done when:** T-2.1 to T-2.6 pass.

### - [ ] 2.2 Migration V3, entity, trigger
- **Code:** `/new-migration custody event`, entity and repository.
- **Test:** `CustodySchemaTest` (DB): T-2.7 `UPDATE` and `DELETE` are both blocked by the trigger; T-2.8 duplicate `(case_id, seq)` gives `23505`.
- **Done when:** T-2.7 and T-2.8 pass.

### - [ ] 2.3 Naive version and reproduction test
- **Code:** interface `CustodyAppender`, `NaiveCustodyAppender` with `pauseAfterRead`.
- **Test:** `CustodyReproductionTest#naive_concurrentAppend_losesEvent` (DB, deterministic interleaving): two transactions
  both read `seq = k` and both insert `k + 1`. **Assert that one transaction fails and there is only 1 row**, i.e. one
  event is lost. T-2.9.
- **Done when:** T-2.9 passes 5/5 times.

### - [ ] 2.4 Fix A: advisory lock
- **Code:** `AdvisoryLockCustodyAppender`.
- **Test:** `CustodyReproductionTest#fixA_…_appendsBoth` (DB): same interleaving, the second transaction is blocked (checked with
  `LockWaits`) and then appends `k + 2`. T-2.10.
  `CustodyStressTest#advisoryLock_1000AppendsOver10Cases` (DB): 1000 appends across 32 threads; every case has a valid chain
  and the number of rows equals the number of successful calls equals 1000. T-2.11.
- **Done when:** T-2.10 and T-2.11 pass twice in a row.

### - [ ] 2.5 Fix B: rely on the unique constraint and retry
- **Code:** `RetryingCustodyAppender`, plus a `RetryOnUniqueViolation` helper at the service layer (retries the whole transaction, at
  most N times, with jitter). State clearly in the Javadoc that **the retry is only safe because the entire business operation re-runs in
  a new transaction**.
- **Test:** T-2.12 same interleaving: one side retries and both events exist; T-2.13 stress test like T-2.11, recording the total
  retry count.
- **Done when:** T-2.12 and T-2.13 pass twice in a row.

### - [ ] 2.6 Write custody events from every write operation
- **Code:** call `custodyAppender.append(...)` **in the same transaction** in `CaseFileService.create`,
  `EvidenceService.register`, `LegalHoldService.place/release`, `holdEvidence/releaseEvidence`, and retention (one
  event per deleted evidence, actor `system:retention`). `actor` is taken from the `X-Actor` header for now, default `anonymous`.
- **Test:** `CustodyIntegrationTest` (DB): T-2.14 create a case, add evidence, place a hold: 3 events in the correct order
  and a valid chain; T-2.15 a failing business operation (e.g. 409 duplicate `caseNumber`) writes **no** events
  (rolled back together).
- **Done when:** T-2.14, T-2.15 pass and all Phase 1 tests stay green.

### - [ ] 2.7 Verify endpoint and tamper tests
- **Code:** `CustodyService.verify(caseId)` walks the whole chain by `seq` (reading in pages if the chain is long), plus `CustodyController`.
- **Test:** `CustodyTamperTest` (DB): T-2.16 a clean chain gives `valid = true`; T-2.17 tamper with a payload via SQL, disabling the
  trigger in the test with `SET session_replication_role = replica` (the Testcontainers user is a superuser), gives
  `valid = false, brokenAtSeq = k`; T-2.18 deleting a row in the middle of the chain is detected as a missing `seq`.
- **Done when:** T-2.16 to T-2.18 pass.

### - [ ] 2.8 Naive version stress, benchmark, docs
- **Code:** `CustodyStressTest#naive_…` with `@Tag("naive")` (rate of lost events), `CustodyBenchmarkTest` with
  `@Tag("bench")` (throughput and p95 of A vs B, retry count of B).
- **Docs:** `docs/concurrency/02-hash-chain.md`, `docs/adr/0002-custody-append.md`, `docs/ARCHITECTURE.md`, tick `docs/PLAN.md`.
- **Done when:** `./mvnw verify` is green, the doc contains real numbers.

## 7. Test cases
| ID | Type | Test | Scenario | Expected |
|----|------|------|----------|---------|
| T-2.1 → T-2.3 | Unit | `CanonicalJsonTest` | key order, data types, Unicode | stable output string |
| T-2.4 → T-2.6 | Unit | `HashChainTest` | fixed vectors, valid chain, broken chain | valid or invalid, correct `seq` |
| T-2.7 | DB | `CustodySchemaTest#appendOnly` | UPDATE/DELETE | blocked by trigger |
| T-2.8 | DB | `CustodySchemaTest#uniqueSeq` | duplicate seq | `23505` |
| T-2.9 | DB | `CustodyReproductionTest#naive_…` | 2 transactions both read k | **1 event lost** |
| T-2.10 | DB | `…#fixA_…` | same interleaving | both appended, lock blocking observed |
| T-2.11 | DB | `CustodyStressTest#advisoryLock_…` | 1000 appends, 10 cases | valid chains, all 1000 present |
| T-2.12, T-2.13 | DB | `…#fixB_…`, stress B | interleaving, stress | all events present, retry count recorded |
| T-2.14 | DB | `CustodyIntegrationTest#eventsInOrder` | business flow | 3 events in correct order |
| T-2.15 | DB | `CustodyIntegrationTest#rollbackTogether` | failing business operation | no events |
| T-2.16 → T-2.18 | DB | `CustodyTamperTest` | clean, modified payload, deleted row | detected at the correct position |

## 8. Docs to update
- `docs/concurrency/02-hash-chain.md`, `docs/adr/0002-custody-append.md`, `docs/ARCHITECTURE.md`, `docs/PLAN.md`.

## 9. Definition of Done
- [ ] `./mvnw verify` green
- [ ] Every write operation has a custody event in the same transaction
- [ ] Doc 02 contains the naive version's lost-event rate and the A/B benchmark table

## 10. Open questions
- Phase 5 wants to partition this table. Partitioning by month forces the unique key to include `created_at`, which breaks
  `UNIQUE(case_id, seq)`. Phase 5 proposes partitioning by `HASH(case_id)` (see the Phase 5 plan).
- Where does `actor` come from before Spring Security exists? The current plan uses the `X-Actor` header.
