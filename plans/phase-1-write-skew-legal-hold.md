# Phase 1 – Write skew: Legal hold and Retention ⭐

> Status: ⬜ Not started · Roadmap: `docs/PLAN.md` §3 Phase 1 · Implement with `/impl-phase 1 [step]`
> Rules: `rules/coding/concurrency.md`, `rules/coding/db.md`, `rules/testing/TEST_STRATEGY.md`

## 1. Goal
Prove with a test that the retention purge job **can delete evidence of a case that has just been placed on legal
hold**: two transactions are each valid on their own and write to **two different rows**, but the combined result
violates the invariant. Then compare 3 fixes: locking, SERIALIZABLE, and a single atomic SQL statement (this one is
**still wrong**, and a test proves it). This is STAR story #1 for interviews.

**Invariant:** when `placeCaseHold` returns success along with the list of evidence it "froze", no evidence in that
list may be deleted.

## 2. Scope
**In scope:** case-level legal hold (new table) and evidence-level legal hold (existing column); retention job; 4
retention strategies (naive, A, B, C); reproduction tests, stress tests, benchmark; docs.
**Out of scope:** authorization for who may place a hold (Phase 5), custody events for holds (Phase 2 adds them).

## 3. Prerequisites
- Phase 0 step 0.1 done (tags `naive` and `bench` excluded from the default build).
- Docker running (Testcontainers).

## 4. Changes vs PLAN.md
1. **Case-level hold is a separate `legal_hold` table.** With the existing `evidence.legal_hold` column, the two
   transactions modify **the same row**. Postgres locks that row and re-checks the `WHERE` condition, so that is only
   a check-then-act bug, not write skew. A case-level hold lives in a different row from the evidence row, so it
   produces real write skew. The `evidence.legal_hold` column stays for evidence-level holds, handled with an atomic
   UPDATE plus a CHECK constraint.
2. **Fix C (a single `UPDATE … WHERE NOT EXISTS (hold)` statement) does not work under READ COMMITTED.** The
   subquery reads the snapshot taken when the statement starts, so it does not see a hold committed after that point.
   The plan keeps C as an **experiment with a test proving it fails**, not as a fix.
3. **Retry does not use the Spring Retry library.** Use a small `SerializableTx` helper built on
   `TransactionTemplate`, so the retry wraps the whole transaction. No new dependency.
4. **API follows the existing routes.** Case-level holds live under `/api/cases/{caseId}/legal-holds`. Evidence-level
   holds keep `/api/evidence/{id}/legal-hold` as in PLAN.md.

## 5. Design

### Schema – `V2__legal_hold.sql`
```sql
CREATE TABLE legal_hold (
    id          UUID PRIMARY KEY,
    case_id     UUID         NOT NULL REFERENCES case_file (id),
    reason      VARCHAR(500) NOT NULL,
    placed_by   VARCHAR(128) NOT NULL,
    placed_at   TIMESTAMPTZ  NOT NULL DEFAULT now(),
    released_at TIMESTAMPTZ,
    version     BIGINT       NOT NULL DEFAULT 0
);
-- Retention and hold checks only look at active holds.
CREATE INDEX idx_legal_hold_active_case ON legal_hold (case_id) WHERE released_at IS NULL;

-- Same-row invariant for evidence-level holds: enforced by the DB, not by Java.
ALTER TABLE evidence
    ADD CONSTRAINT chk_evidence_not_held_and_deleted CHECK (NOT (legal_hold AND deleted_at IS NOT NULL));
```

### API
| Method | Path | Success | Errors |
|---|---|---|---|
| POST | `/api/cases/{caseId}/legal-holds` `{reason, placedBy}` | 201 `{id, caseId, reason, placedBy, placedAt, frozenEvidenceIds}` | 400, 404 case |
| GET | `/api/cases/{caseId}/legal-holds` | 200 list of active holds | 404 |
| DELETE | `/api/cases/{caseId}/legal-holds/{holdId}` | 204 | 404, 409 already released |
| POST | `/api/evidence/{id}/legal-hold` | 204 | 404, 409 evidence already deleted |
| DELETE | `/api/evidence/{id}/legal-hold` | 204 | 404 |
| POST | `/api/admin/retention/run` | 200 `{strategy, deleted}`, runs 1 batch | – |

### New classes
| Class | Package | Role | Transaction / isolation |
|---|---|---|---|
| `LegalHold`, `LegalHoldRepository` | `retention` | Entity and repository | – |
| `LegalHoldService` | `retention` | `placeCaseHold`, `releaseCaseHold`, `holdEvidence`, `releaseEvidence` | `placeCaseHold`: **SERIALIZABLE + retry**, plus a `FOR NO KEY UPDATE` lock on the `case_file` row (to be safe with every retention strategy, see §6 step 1.4). Other operations: READ COMMITTED + a single atomic UPDATE |
| `LegalHoldController`, `EvidenceHoldController` | `retention` | REST | – |
| `RetentionStrategy` (interface) | `retention` | `int purgeExpired(Instant now, int batchSize)` | depends on implementation |
| `NaiveRetentionService` | `retention` | read → check hold → delete; has a `pauseAfterCheck` hook (package-private) for tests | READ COMMITTED, no locks |
| `LockingRetentionService` (A) | `retention` | `FOR UPDATE SKIP LOCKED` on evidence, `FOR SHARE` on the related cases, then check holds | READ COMMITTED + row locks |
| `SerializableRetentionService` (B) | `retention` | naive algorithm run inside `SerializableTx` | SERIALIZABLE + retry `40001` |
| `SingleStatementRetentionService` (C) | `retention` | a single `UPDATE … WHERE NOT EXISTS (…)` statement | READ COMMITTED (**experiment, still wrong**) |
| `RetentionJob` | `retention` | `@Scheduled`, calls the strategy selected by `evidence-vault.retention.strategy` | – |
| `SerializableTx` | `common/tx` | Runs a callback at SERIALIZABLE, retries on SQLState `40001` (at most N times, backoff with jitter), counts retries | SERIALIZABLE |
| `ClockConfig` | `common` | `Clock` bean so tests can control time | – |

Configuration (`application.yml`):
```yaml
evidence-vault:
  retention:
    enabled: true          # tests set false so the scheduler does not interfere
    strategy: locking      # naive | locking | serializable | single-statement
    batch-size: 500
    interval: PT1M
```

### Failure scenario (naive, READ COMMITTED)
| T1: retention | T2: placeCaseHold |
|---|---|
| read expired evidence of case X | |
| check holds on X: none | |
| | lock case X, read live evidence (also sees the evidence T1 is about to delete) |
| | insert hold, return `frozenEvidenceIds`, commit |
| soft-delete that evidence, commit | |
| **→ frozen evidence has been deleted** | |

## 6. Steps (code + tests together)

### - [ ] 1.1 Test infrastructure
- **Code:** `ClockConfig` (bean `Clock.systemUTC()`). In `src/test/.../support/`: `MutableClock` and
  `LockWaits.awaitBlocked(JdbcTemplate, int sessions, Duration timeout)`. This helper polls `pg_stat_activity` (`wait_event_type = 'Lock'`)
  to wait until a transaction is blocked on a lock, without `sleep`. Add `evidence-vault.retention.enabled=false` to the test properties.
- **Test:** `LockWaitsTest#detectsSessionBlockedOnRowLock` (DB): thread 1 holds `FOR UPDATE` on a row, thread 2 is blocked,
  the helper detects it within the timeout. T-1.1.
- **Done when:** T-1.1 passes twice in a row.

### - [ ] 1.2 Migration V2 and `LegalHold` entity
- **Code:** `/new-migration legal hold`, create `V2__legal_hold.sql` as in §5. Add the `LegalHold` entity (UUID, `Instant`,
  `@Version`) and `LegalHoldRepository`.
- **Test:** `LegalHoldSchemaTest` (DB): T-1.2 Hibernate validate passes (context load); T-1.3 a direct UPDATE setting
  `legal_hold = true` on a deleted evidence must throw a CHECK violation.
- **Done when:** T-1.2 and T-1.3 pass, `EvidenceVaultApplicationTests` stays green.

### - [ ] 1.3 `SerializableTx` (retry 40001)
- **Code:** `common/tx/SerializableTx` with `execute(Supplier<T>)`; it detects serialization failures by walking the
  `cause` chain looking for `SQLException.getSQLState() == "40001"`. Backoff `base * 2^attempt + jitter`, capped by `maxAttempts`
  (default 5). When retries exceed the limit, rethrow the original error. Exposes `retryCount()` for tests and benchmarks.
- **Test:**
  - `SerializableTxBackoffTest` (Unit): T-1.4 backoff is within the expected range, increasing, and capped.
  - `SerializationFailureClassifierTest` (Unit): T-1.5 detects `40001` nested deep in the cause chain; `23505` is not treated as retryable.
  - `SerializableTxTest` (DB): T-1.6 two SERIALIZABLE transactions create write skew on a scratch table, coordinated by latches,
    so one side gets a real `40001`. The helper retries and finishes correctly, `retryCount >= 1`. T-1.7 a callback that always throws
    `40001` gives up after `maxAttempts`.
- **Done when:** T-1.4 to T-1.7 pass.

### - [ ] 1.4 Case-level hold: `LegalHoldService` and API
- **Code:** `placeCaseHold(caseId, reason, placedBy)` runs inside `SerializableTx`:
  (1) `SELECT id FROM case_file WHERE id = :id FOR NO KEY UPDATE` (404 if missing);
  (2) read the ids of the case's live evidence, these are the `frozenEvidenceIds`; (3) insert `legal_hold`.
  Why both a lock and SERIALIZABLE: the case row lock is what makes strategy A work; SERIALIZABLE is what makes strategy B
  work, because SSI only detects conflicts when **both sides** run at SERIALIZABLE. Placing a hold is rare and
  important, so the cost is acceptable. Write this reasoning into the Javadoc.
  `releaseCaseHold`: `UPDATE legal_hold SET released_at = now() WHERE id = :id AND case_id = :caseId AND released_at IS NULL`,
  0 rows means 404 or 409. Add `LegalHoldController` and record DTOs.
- **Test:** `LegalHoldApiTest` (DB, MockMvc): T-1.8 placing a hold returns 201 with correct `frozenEvidenceIds`; T-1.9 unknown
  case returns 404 ProblemDetail; T-1.10 releasing twice returns 409 the second time; T-1.11 GET returns only active holds;
  T-1.12 empty `reason` returns 400.
- **Done when:** T-1.8 to T-1.12 pass.

### - [ ] 1.5 Evidence-level hold (same row)
- **Code:** `holdEvidence`: `UPDATE evidence SET legal_hold = true, version = version + 1 WHERE id = :id AND deleted_at IS NULL`.
  On 0 rows, distinguish not found (404) from already deleted (409). `releaseEvidence` works the same way. Add `EvidenceHoldController`.
- **Test:** `EvidenceHoldApiTest` (DB): T-1.13 hold then release returns 204, flag changes correctly; T-1.14 holding deleted evidence returns 409;
  T-1.15 20 threads holding and soft-deleting concurrently (`Concurrently`): no row has both `legal_hold` and `deleted_at`.
  This is the CHECK constraint and Postgres's condition re-check working together.
- **Done when:** T-1.13 to T-1.15 pass.

### - [ ] 1.6 `RetentionStrategy`, naive version, job, admin endpoint
- **Code:** interface `RetentionStrategy`, `NaiveRetentionService`, `RetentionJob`
  (`@ConditionalOnProperty(evidence-vault.retention.enabled)`, `@EnableScheduling`), `RetentionAdminController`,
  bean selected by `evidence-vault.retention.strategy`. Retention only deletes evidence with `retain_until < now`,
  `deleted_at IS NULL`, `legal_hold = false` and whose case has no active hold, limited by `batchSize`.
- **Test:**
  - `RetentionBehaviourTest` (DB, `@ParameterizedTest` across all 4 strategies, single-threaded): T-1.16 expired evidence
    is deleted; T-1.17 not-yet-expired evidence is kept; T-1.18 evidence of a held case is kept; T-1.19 held evidence is kept; T-1.20 `batchSize` is respected.
  - `WriteSkewReproductionTest#naive_holdPlacedDuringRetention_reproducesAnomaly` (DB): retention pauses at
    `pauseAfterCheck`, another thread places a hold and commits, then retention continues. **Assert that some evidence in
    `frozenEvidenceIds` has been deleted** (invariant violated; the test is green because it records the bug). T-1.21.
- **Done when:** T-1.16 to T-1.21 pass, T-1.21 gives the same result in 5/5 runs.

### - [ ] 1.7 Fix A: locking (`LockingRetentionService`)
- **Code:** each batch runs in this order:
  (1) `SELECT id, case_id FROM evidence WHERE <expired> ORDER BY retain_until LIMIT :n FOR UPDATE SKIP LOCKED`;
  (2) `SELECT id FROM case_file WHERE id = ANY(:caseIds) ORDER BY id FOR SHARE`, locking in id order to avoid deadlocks;
  (3) read active holds; this is a new statement, so it sees committed holds; (4) soft-delete the rest.
  `FOR SHARE` conflicts with the `FOR NO KEY UPDATE` in `placeCaseHold`, so the two operations are serialized.
- **Test:**
  - `WriteSkewReproductionTest#fixA_holdPlacedDuringRetention_preventsAnomaly` (DB): same scenario as T-1.21. Use
    `LockWaits` to confirm `placeCaseHold` **is blocked** until retention commits. Invariant holds. T-1.22.
  - `RetentionStressTest#locking_neverDeletesFrozenEvidence` (DB): 50 rounds, each with 8 retention and 8 placeCaseHold
    running concurrently on random cases. Invariant holds in every round. T-1.23.
- **Done when:** T-1.22 and T-1.23 pass twice in a row.

### - [ ] 1.8 Fix B: SERIALIZABLE (`SerializableRetentionService`)
- **Code:** naive algorithm wrapped in `SerializableTx`, no row locks.
- **Test:** `WriteSkewReproductionTest#fixB_…_preventsAnomaly` (DB): same scenario. One side gets `40001`, retries, the
  result is consistent, `retryCount >= 1`. T-1.24. Stress test like T-1.23 for strategy B, recording the total retry count. T-1.25.
- **Done when:** T-1.24 and T-1.25 pass twice in a row.

### - [ ] 1.9 Experiment C: single SQL statement (`SingleStatementRetentionService`)
- **Code:** `UPDATE evidence e SET deleted_at = :now, version = version + 1 WHERE e.id IN (SELECT id … LIMIT :n FOR UPDATE SKIP LOCKED)
  AND NOT EXISTS (SELECT 1 FROM legal_hold h WHERE h.case_id = e.case_id AND h.released_at IS NULL)`.
- **Test:** `WriteSkewReproductionTest#singleStatement_holdUncommittedDuringUpdate_reproducesAnomaly` (DB):
  T2 locks the case, reads evidence, inserts the hold and **does not commit yet** (waits on a latch); T1 runs the UPDATE and commits; T2 commits.
  **Assert the invariant is violated**: atomic does not mean serializable. T-1.26.
- **Done when:** T-1.26 passes 5/5 times. Write the explanation of statement snapshots into the doc.

### - [ ] 1.10 Naive version stress test (tag `naive`) and failure rate
- **Code:** `RetentionStressTest#naive_…` with `@Tag("naive")`, same configuration as T-1.23.
- **Test:** run separately with `./mvnw test -Dgroups=naive -DexcludedGroups= -Dtest=RetentionStressTest`, record the rate of rounds
  that violate the invariant. T-1.27.
- **Done when:** the failure rate (e.g. "37/50 rounds") is in the notes and in the doc.

### - [ ] 1.11 Benchmark A, B, C (tag `bench`)
- **Code:** `RetentionBenchmarkTest` (`@Tag("bench")`): 10k expired evidence across 200 cases, 8 retention threads, 4 threads
  placing holds continuously. Measure throughput (evidence deleted/second), p95 batch time, total retries (B), lock wait time (A).
  Print a Markdown table to the console and to `target/bench/retention.md`.
- **Test:** the benchmark itself, T-1.28 (not in the default build).
- **Done when:** there is a results table for A, B, C.

### - [ ] 1.12 Choose the default, write docs
- **Code:** set the default `strategy` based on the results (expected: `locking`).
- **Docs:** `docs/concurrency/01-write-skew-legal-hold.md` following `_TEMPLATE.md`, with the interleaving table, the naive failure rate,
  the benchmark table, why C fails, and the decision. `docs/adr/0001-retention-strategy.md`.
  `docs/ARCHITECTURE.md`: new endpoints, write path table (§4), data model. Tick Phase 1 in `docs/PLAN.md`.
- **Done when:** `./mvnw verify` is green, the docs exist and contain real numbers.

### - [ ] 1.13 (Optional) UI
- **Code:** in `static/`, add a "Place legal hold" button on the case page, a hold column, and a "Run retention" button that calls the admin endpoint.
- **Test:** manual check (static UI, no JUnit).
- **Done when:** holds can be placed and retention run from the UI, errors are shown as ProblemDetail.

## 7. Test cases
| ID | Type | Test | Scenario | Expected |
|----|------|------|----------|---------|
| T-1.1 | DB | `LockWaitsTest#detectsSessionBlockedOnRowLock` | thread 2 waits on a row lock | helper detects it within the timeout |
| T-1.2 | DB | `LegalHoldSchemaTest#migrationValidates` | Flyway V2 + Hibernate validate | context load |
| T-1.3 | DB | `LegalHoldSchemaTest#checkRejectsHeldAndDeleted` | SQL violating CHECK | error `23514` |
| T-1.4 | Unit | `SerializableTxBackoffTest` | backoff by attempt number | increasing, jittered, capped |
| T-1.5 | Unit | `SerializationFailureClassifierTest` | 40001 nested in cause, 23505 | true / false |
| T-1.6 | DB | `SerializableTxTest#retriesRealSerializationFailure` | real SERIALIZABLE write skew | completes, retry ≥ 1 |
| T-1.7 | DB | `SerializableTxTest#givesUpAfterMaxAttempts` | always 40001 | throws after N attempts |
| T-1.8 | DB | `LegalHoldApiTest#placeHold` | POST hold | 201, correct frozen ids |
| T-1.9 | DB | `LegalHoldApiTest#unknownCase` | case does not exist | 404 ProblemDetail |
| T-1.10 | DB | `LegalHoldApiTest#releaseTwice` | DELETE twice | 204 then 409 |
| T-1.11 | DB | `LegalHoldApiTest#listActiveOnly` | some holds released | returns only active holds |
| T-1.12 | DB | `LegalHoldApiTest#validation` | empty `reason` | 400 |
| T-1.13 | DB | `EvidenceHoldApiTest#holdAndRelease` | hold/release | 204, correct flag |
| T-1.14 | DB | `EvidenceHoldApiTest#holdDeletedEvidence` | evidence already deleted | 409 |
| T-1.15 | DB | `EvidenceHoldApiTest#concurrentHoldAndDelete` | 20 threads | no row both held and deleted |
| T-1.16 → T-1.20 | DB | `RetentionBehaviourTest` × 4 strategy | single-threaded | retention rules respected |
| T-1.21 | DB | `WriteSkewReproductionTest#naive_…` | deterministic interleaving | **invariant violated** |
| T-1.22 | DB | `…#fixA_…` | same interleaving | hold blocked, invariant holds |
| T-1.23 | DB | `RetentionStressTest#locking_…` | 50 rounds × 16 threads | invariant holds |
| T-1.24 | DB | `…#fixB_…` | same interleaving | 40001 → retry, invariant holds |
| T-1.25 | DB | `RetentionStressTest#serializable_…` | stress | invariant holds, retry count recorded |
| T-1.26 | DB | `…#singleStatement_…` | hold uncommitted during UPDATE | **invariant violated** |
| T-1.27 | DB `naive` | `RetentionStressTest#naive_…` | naive version stress | failure rate recorded |
| T-1.28 | DB `bench` | `RetentionBenchmarkTest` | mixed load | results table |

## 8. Docs to update
- `docs/concurrency/01-write-skew-legal-hold.md`, `docs/adr/0001-retention-strategy.md`, `docs/ARCHITECTURE.md`,
  tick `docs/PLAN.md`. Consider running `/log-ai-mistake` if you catch an AI mistake in this phase.

## 9. Definition of Done
- [ ] `./mvnw verify` green, including T-1.1 to T-1.26
- [ ] Naive failure rate (T-1.27) and benchmark table (T-1.28) recorded
- [ ] Doc 01 and ADR 0001 contain real numbers

## 10. Open questions
- Should a case-level hold block **registering new evidence** into a held case? Current plan: no blocking; new
  evidence is still protected because retention checks holds per case.
- `placedBy` is a free-form string for now. Phase 5 will take it from the JWT.
