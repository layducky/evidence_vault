# Phase 1 – Write skew: Legal hold and Retention ⭐

> Status: ✅ Done except optional 1.13 manual check · M-13 open (found by the benchmark, not blocking) · Roadmap: `docs/PLAN.md` §3 Phase 1 ·
> Implement with `/impl-phase 1 [step]`
> Rules: `rules/coding/backend-rules.md`, `rules/coding/concurrency.md`, `rules/coding/db.md`, `rules/testing/TEST_STRATEGY.md`

## 0. Review findings (decide before `/impl-phase`)
Blocker = the plan as originally written would give wrong code or a test that cannot behave as specified.
Minor = rule deviation, ambiguity or a default choice. The design below already applies the proposed
resolutions, marked with the finding id. `/impl-phase` refuses to run while any Decision is `⬜ open`.

| ID | Severity | Finding | Proposed resolution | Decision |
|----|----------|---------|---------------------|----------|
| B-1 | Blocker | **Fix A still loses frozen evidence.** `placeCaseHold` runs at SERIALIZABLE, and Postgres takes its snapshot at the start of the first statement, *before* that statement waits for the case lock held (`FOR SHARE`) by retention A. A deletes the evidence and commits. The case row was only locked, not updated, so `placeCaseHold` gets the lock with no error and then reads evidence from its old snapshot: the deleted evidence is still "live" and ends up in `frozenEvidenceIds`. SSI does not help, because A runs at READ COMMITTED. | `placeCaseHold` reads the frozen evidence with `SELECT … FOR SHARE`. A locking read of a row updated after the snapshot raises `40001` at SERIALIZABLE, so `SerializableTx` retries with a fresh snapshot. The share locks also block deletion of the frozen rows until the hold commits. | ✅ accepted (2026-10-10) |
| B-2 | Blocker | Once B-1 is applied, the original A order (lock evidence `FOR UPDATE SKIP LOCKED`, then case `FOR SHARE`) is the reverse of `placeCaseHold` (case, then evidence) and deadlocks (`40P01`). | A locks in the same order as `placeCaseHold`: read candidates without locks → `FOR SHARE` on their cases ordered by id → `FOR UPDATE SKIP LOCKED` on the evidence, re-checking the conditions → read active holds → soft-delete. | ✅ accepted (2026-10-10) |
| B-3 | Blocker | Once B-1 is applied, experiment C with `FOR UPDATE SKIP LOCKED` simply **skips** evidence share-locked by an in-flight hold, so the planned reproduction (T-1.28) shows no anomaly. | C's subquery uses `FOR UPDATE` **without** SKIP LOCKED. The statement waits on the hold's share lock, then (the row was locked, not updated, so there is no re-check) deletes using its statement snapshot, in which the hold did not exist yet. Reproduction order: hold inserted (uncommitted) → C blocks → hold commits → C deletes. Deterministic, and it still proves "atomic ≠ serializable". | ✅ accepted (2026-10-10) |
| B-4 | Blocker | **Retention can stall (liveness).** As planned, a batch selects the oldest expired evidence and only *then* drops those in held cases. If the oldest `batchSize` rows all belong to held cases, every batch deletes 0 rows forever and nothing else is ever purged. | Every strategy's candidate query excludes held cases and held evidence in SQL (`NOT EXISTS (active hold)`, `legal_hold = false`). Strategies differ only in how that check is made safe. New test T-1.22. | ✅ accepted (2026-10-10) |
| B-5 | Blocker | **Retention tests are not isolated.** Retention purges *all* expired evidence in the database, and every DB test class shares one container. Leftover expired rows from earlier tests change `deleted` counts and batch contents, so T-1.21 and the stress tests become order-dependent. | Retention tests (`RetentionBehaviourTest`, `WriteSkewReproductionTest`, `RetentionStressTest`, `RetentionAdminApiTest`, benchmark) call `RetentionTestData.reset(jdbc)` (`TRUNCATE legal_hold, evidence`) in `@BeforeEach`, and assert only on rows they created. This is a documented exception to "unique keys per test". | ✅ accepted (2026-10-10) |
| M-1 | Minor | Test hooks (`pauseAfterCheck`, `pauseBeforeCommit`) as package-private fields on `@Transactional` beans: a field set through the CGLIB proxy never reaches the target, so the hook would silently never fire. | Hooks are package-private fields on the target class. Tests set them on `AopTestUtils.getTargetObject(bean)` and reset them in `@AfterEach`. A retried transaction re-runs the hook, so hooks must be idempotent (latches that are already open). | ✅ accepted (2026-10-10) |
| M-2 | Minor | `rules/coding/backend-rules.md`: cross features only via services. Retention strategies run SQL directly on `evidence` and `case_file`. | Allowed for `retention` only, because lock modes, lock order and statement shape *are* the subject of this phase. Documented in `retention/package-info.java`. 404 checks still go through `CaseFileService`. | ✅ accepted (2026-10-10) |
| M-3 | Minor | Retrying inside an outer transaction is meaningless: the outer transaction is already aborted. Entities created outside the callback would also be reused across attempts. | `SerializableTx.execute` throws `IllegalStateException` if a transaction is already active (T-1.8). Callbacks create their entities inside the callback. | ✅ accepted (2026-10-10) |
| M-4 | Minor | If the naive delete statement updated by id only, a concurrent *evidence-level* hold would make it fail on the CHECK constraint and roll back the whole batch, mixing two problems. | Every strategy's delete is `UPDATE evidence SET deleted_at = :now, status = 'DELETED', version = version + 1 WHERE id = ANY(:ids) AND deleted_at IS NULL AND legal_hold = false`. The naive version is then wrong **only** about the case-level hold (the write skew). | ✅ accepted (2026-10-10) |
| M-5 | Minor | There is no soft-delete API, so T-1.16 has nothing to race `holdEvidence` against. | T-1.16 races `holdEvidence` against a raw SQL soft-delete that deliberately ignores `legal_hold`, so that only the CHECK constraint and the row re-check protect the invariant. | ✅ accepted (2026-10-10) |
| M-6 | Minor | `MutableClock` in the original step 1.1 is unused: strategies take `now` as a parameter. | Drop `MutableClock`. Keep `ClockConfig` for the job, the admin endpoint and `placedAt`/`released_at`. | ✅ accepted (2026-10-10) |
| M-7 | Minor | `LockWaits` must wait between polls of `pg_stat_activity`; the rule bans `Thread.sleep` for interleavings. | A 5 ms pause between polls, documented as polling a condition, not creating an interleaving. The interleaving itself still uses latches. | ✅ accepted (2026-10-10) |
| M-8 | Minor | Original plan: "the job soft-deletes in batches", with no bound. | Each tick loops batches (one transaction each) until a batch deletes fewer than `batch-size`, capped by `max-batches-per-run` (default 100). | ✅ accepted (2026-10-10) |
| M-9 | Minor | `POST /api/admin/retention/run` is unauthenticated, and `placedBy` is a free-form string. | Accept for now (local only). Phase 5 adds auth and takes `placedBy` from the JWT. | ✅ accepted (2026-10-10) |
| M-10 | Minor | Should a held case reject newly registered evidence? | No. New evidence is still protected, because retention checks holds per case. | ✅ accepted (2026-10-10) |
| M-11 | Minor | Benchmark numbers on Docker Desktop (Windows) are noisy. | Run each strategy 3 times, report the median and the machine spec next to the table. | ✅ accepted (2026-10-10) |
| B-6 | Blocker | *Found by T-1.28 during `/impl-phase` (2026-10-10).* **Deadlock between B and `placeCaseHold`** (4 × `40P01` in 50 rounds). The shared soft-delete `UPDATE … WHERE id IN (:ids)` locks evidence rows in scan (physical) order, while `placeCaseHold` locks them `FOR SHARE … ORDER BY id`. A and the naive version avoid it only by luck of timing (A locks with SKIP LOCKED first). C's subquery locks in `retain_until` order and has the same problem. | The shared soft-delete takes its row locks in id order: `UPDATE … WHERE id IN (SELECT id FROM evidence WHERE id IN (:ids) AND deleted_at IS NULL AND legal_hold = false ORDER BY id FOR UPDATE)`. C becomes `… WHERE id IN (SELECT e.id FROM evidence e WHERE e.id IN (<oldest-n candidates, no lock>) ORDER BY e.id FOR UPDATE OF e)`: still one statement with one snapshot, so T-1.29 still reproduces. | ✅ accepted (2026-10-10) |
| M-12 | Minor | *Found by T-1.28.* Under the stress load, B sometimes exhausts its 5 attempts (1 × `40001` out of ~400 purges, 1024 retries in 50 rounds). That is B's real cost under contention, not a bug. | Keep `maxAttempts = 5`. T-1.28 asserts the invariant and that the **only** errors are exhausted `40001`s; it reports their count and the retry count, which go into the doc and the benchmark. | ✅ accepted (2026-10-10) |
| M-13 | Minor | *Found by T-1.31 (benchmark), 2026-10-10.* Under a heavy purge with strategy A, `placeCaseHold` can exhaust its 5 attempts (1 hold in ~120): each purge it waits behind deletes evidence of the case after its snapshot, so each wait ends in `40001` (the B-1 mechanism working). The invariant holds, but the client gets an unmapped `CannotAcquireLockException` → **500**. Not in any step of this plan. | Map exhausted serialization failures (`40001` after the last attempt) to **503** ProblemDetail with `Retry-After: 1` in `GlobalExceptionHandler`, plus a MockMvc test. Optionally raise `maxAttempts` for holds only. | ⬜ open |

## 1. Goal
Prove with a test that the retention purge job **can delete evidence of a case that has just been placed on legal
hold**: two transactions are each valid on their own and write to **two different rows**, but the combined result
violates the invariant. Then compare 3 approaches: locking, SERIALIZABLE, and a single atomic SQL statement (this one is
**still wrong**, and a test proves it). This is STAR story #1 for interviews. Bonus story: the first locking design
was also wrong, because a SERIALIZABLE snapshot is taken before the transaction waits for a lock (B-1).

**Invariant:** when `placeCaseHold` returns success along with the list of evidence it "froze", no evidence in that
list may be deleted.

## 2. Scope
**In scope:** case-level legal hold (new table) and evidence-level legal hold (existing column); retention job; 4
retention strategies (naive, A, B, C); reproduction tests, stress tests, benchmark; docs.
**Out of scope:** authorization for who may place a hold (Phase 5), custody events for holds (Phase 2 adds them).

## 3. Prerequisites
- Phase 0 done (tags `naive` and `bench` excluded from the default build, CI green).
- Docker running (Testcontainers).
- All findings in §0 decided.

## 4. Changes vs PLAN.md
1. **Case-level hold is a separate `legal_hold` table.** With the existing `evidence.legal_hold` column, the two
   transactions modify **the same row**. Postgres locks that row and re-checks the `WHERE` condition, so that is only
   a check-then-act bug, not write skew. A case-level hold lives in a different row from the evidence row, so it
   produces real write skew. The `evidence.legal_hold` column stays for evidence-level holds, handled with an atomic
   UPDATE plus a CHECK constraint.
2. **Fix A is not "`SELECT … FOR UPDATE`" on evidence alone.** It locks the case `FOR SHARE` and then the evidence
   (B-2), and it only works together with the `FOR SHARE` read of evidence in `placeCaseHold` (B-1).
3. **C (a single `UPDATE … WHERE NOT EXISTS (hold)` statement) does not work under READ COMMITTED.** The statement
   uses the snapshot taken when it starts, so it does not see a hold committed after that point. The plan keeps C
   as an **experiment with a test proving it fails**, not as a fix. Its subquery uses `FOR UPDATE` without
   SKIP LOCKED (B-3).
4. **Retry does not use the Spring Retry library.** Use a small `SerializableTx` helper built on
   `TransactionTemplate`, so the retry wraps the whole transaction. No new dependency.
5. **API follows the existing routes.** Case-level holds live under `/api/cases/{caseId}/legal-holds`. Evidence-level
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
| POST | `/api/evidence/{id}/legal-hold` | 204 (idempotent) | 404, 409 evidence already deleted |
| DELETE | `/api/evidence/{id}/legal-hold` | 204 | 404, 409 evidence already deleted |
| POST | `/api/admin/retention/run` | 200 `{strategy, deleted}`, runs 1 batch with the configured strategy | – |

### Locks per write path (READ COMMITTED unless stated)
| Path | Isolation | Locks, in order | Why |
|---|---|---|---|
| `placeCaseHold` | SERIALIZABLE + retry | `case_file` row `FOR NO KEY UPDATE` → live evidence of the case `FOR SHARE` (B-1) → insert `legal_hold` | Case lock serializes against A; the evidence share lock forces a fresh snapshot after waiting (`40001` → retry) and blocks deletion of frozen rows; SERIALIZABLE lets SSI catch B |
| Naive | READ COMMITTED | none | the bug |
| A – locking | READ COMMITTED | cases `FOR SHARE` (by id) → evidence `FOR UPDATE SKIP LOCKED` (B-2) | same order as `placeCaseHold`, so no deadlock |
| B – serializable | SERIALIZABLE + retry | none | SSI detects the rw-cycle with `placeCaseHold` |
| C – single statement | READ COMMITTED | evidence `FOR UPDATE` inside the statement (B-3) | experiment: still wrong |
| `holdEvidence` / `releaseEvidence` | READ COMMITTED | the evidence row (atomic UPDATE) | same row: Postgres re-checks `deleted_at IS NULL` after waiting |
| `releaseCaseHold` | READ COMMITTED | the hold row (atomic UPDATE) | releasing only ever allows more deletion |

### New classes
| Class | Package | Role | Transaction / isolation |
|---|---|---|---|
| `ClockConfig` | `common` | `Clock` bean (`Clock.systemUTC()`) | – |
| `SerializableTx`, `SerializationFailures` | `common/tx` | Runs a callback at SERIALIZABLE, retries SQLState `40001` (at most `maxAttempts`, exponential backoff with jitter), counts retries; refuses to run inside an active transaction (M-3). `SerializationFailures.isRetryable(Throwable)` walks the cause chain | SERIALIZABLE |
| `LegalHold`, `LegalHoldRepository` | `retention` | Entity and repository | – |
| `LegalHoldService` | `retention` | `placeCaseHold`, `listActive`, `releaseCaseHold`, `holdEvidence`, `releaseEvidence`; test hook `pauseBeforeCommit` (M-1) | see lock table |
| `LegalHoldController`, `EvidenceHoldController` | `retention` | REST, record DTOs | – |
| `RetentionStrategy` (interface) | `retention` | `Kind kind()`, `int purgeExpired(Instant now, int batchSize)` | per implementation |
| `NaiveRetentionService` | `retention` | candidates (with hold check in SQL, B-4) → hook `pauseAfterCheck` → delete (M-4) | `@Transactional` READ COMMITTED |
| `LockingRetentionService` (A) | `retention` | see step 1.7; hook `pauseAfterCheck` | `@Transactional` READ COMMITTED + row locks |
| `SerializableRetentionService` (B) | `retention` | the naive algorithm inside `SerializableTx`; hook `pauseAfterCheck` | SERIALIZABLE + retry |
| `SingleStatementRetentionService` (C) | `retention` | one `UPDATE` statement (B-3), no hook | `@Transactional` READ COMMITTED (**experiment, still wrong**) |
| `RetentionProperties` | `retention` | `@ConfigurationProperties("evidence-vault.retention")` record: `enabled`, `strategy`, `batchSize`, `interval`, `maxBatchesPerRun` | – |
| `RetentionStrategies` | `retention` | maps `Kind` → bean, `active()` returns the configured one | – |
| `RetentionJob` | `retention` | `@Scheduled(fixedDelayString = "${evidence-vault.retention.interval}")`, loops batches (M-8). Registered only when `enabled=true`, together with `@EnableScheduling` | one transaction per batch |
| `RetentionAdminController` | `retention` | `POST /api/admin/retention/run` | – |

Configuration (`application.yml`):
```yaml
evidence-vault:
  retention:
    enabled: true            # src/test/resources/application.properties sets false so the scheduler does not interfere
    strategy: locking        # naive | locking | serializable | single-statement
    batch-size: 500
    interval: PT1M
    max-batches-per-run: 100
```

Shared SQL. Every strategy uses this candidate filter (B-4):
```sql
e.retain_until < :now AND e.deleted_at IS NULL AND e.legal_hold = false
AND NOT EXISTS (SELECT 1 FROM legal_hold h WHERE h.case_id = e.case_id AND h.released_at IS NULL)
```
Every strategy uses this soft-delete (M-4):
```sql
UPDATE evidence SET deleted_at = :now, status = 'DELETED', version = version + 1
WHERE id = ANY(:ids) AND deleted_at IS NULL AND legal_hold = false
```

### Failure scenario (naive, READ COMMITTED)
| T1: retention (naive) | T2: placeCaseHold |
|---|---|
| select candidates; case X has no hold, so evidence E is a candidate | |
| *(paused at `pauseAfterCheck`)* | |
| | lock case X, read live evidence `FOR SHARE` (T1 holds no locks) → E is frozen |
| | insert hold, commit, return `frozenEvidenceIds = [E]` |
| soft-delete E, commit | |
| **→ frozen evidence E has been deleted** | |

### Why the first Fix A design failed (B-1)
| T1: retention A (original design) | T2: placeCaseHold (SERIALIZABLE) |
|---|---|
| lock E `FOR UPDATE`, lock case X `FOR SHARE`, no hold found | |
| | first statement: **snapshot taken**, then waits for case X |
| soft-delete E, commit | |
| | gets the lock on X (only locked by T1, not updated → no error) |
| | reads evidence **from its old snapshot**: E looks live → frozen = [E] ✗ |
With B-1, T2 reads evidence `FOR SHARE`. E was updated after T2's snapshot, so T2 gets `40001`, retries with a fresh
snapshot, and E is not frozen.

## 6. Steps (code + tests together)

### - [x] 1.1 Test infrastructure
> Done 2026-10-10: `ClockConfig`, `support/LockWaits`, test `application.properties`. T-1.1 green 2/2 runs.
- **Code:** `common/ClockConfig`. In `src/test/java/com/evidencevault/support/`: `LockWaits.awaitBlocked(JdbcTemplate, int sessions, Duration timeout)`,
  which polls `pg_stat_activity` (`wait_event_type = 'Lock'`, same database, excluding its own pid) every 5 ms (M-7) until
  at least `sessions` sessions wait on a lock, and fails on timeout. `src/test/resources/application.properties` with
  `evidence-vault.retention.enabled=false`. No `MutableClock` (M-6).
- **Test:** `LockWaitsTest#detectsSessionBlockedOnRowLock` (DB): thread 1 holds `FOR UPDATE` on a `case_file` row, thread 2
  requests the same lock and blocks, the helper returns within the timeout; thread 1 commits, thread 2 finishes. T-1.1.
- **Done when:** T-1.1 passes twice in a row.

### - [x] 1.2 Migration V2 and `LegalHold` entity
> Done 2026-10-10: `V2__legal_hold.sql`, `LegalHold`, `LegalHoldRepository`. T-1.2, T-1.3 green; `EvidenceVaultApplicationTests` green.
- **Code:** `/new-migration legal hold` → `V2__legal_hold.sql` as in §5. `LegalHold` entity (UUID, `Instant`, `@Version`,
  `caseId` as a plain `UUID` column) and `LegalHoldRepository` (`findByCaseIdAndReleasedAtIsNullOrderByPlacedAt`).
- **Test:** `LegalHoldSchemaTest` (DB): T-1.2 Hibernate validate passes (context load); T-1.3 a direct UPDATE setting
  `legal_hold = true` on a deleted evidence fails with SQLState `23514`.
- **Done when:** T-1.2 and T-1.3 pass, `EvidenceVaultApplicationTests` stays green.

### - [x] 1.3 `SerializableTx` (retry 40001)
> Done 2026-10-10: `common/tx/SerializableTx` (+ `Backoff`), `SerializationFailures`. T-1.4 to T-1.8 green; T-1.6 hit a real `40001` and retried.
- **Code:** `common/tx/SerializableTx#execute(Supplier<T>)` on a `TransactionTemplate` with `ISOLATION_SERIALIZABLE`.
  `SerializationFailures.isRetryable` finds an `SQLException` with SQLState `40001` anywhere in the cause chain.
  Backoff for attempt `n` (0-based): `d = min(cap, base * 2^n)`, sleep `d + random(0..d)`, capped at `cap`.
  Defaults: `maxAttempts = 5`, `base = 10 ms`, `cap = 200 ms`; a second constructor takes custom values for tests.
  After `maxAttempts`, rethrow the last error. `retryCount()` returns the total retries since startup.
  Throws `IllegalStateException` when called inside an active transaction (M-3).
- **Test:**
  - `SerializableTxBackoffTest` (Unit): T-1.4 each attempt's delay is in `[d, min(cap, 2d)]`, the lower bound grows, nothing exceeds `cap`.
  - `SerializationFailuresTest` (Unit): T-1.5 `40001` nested three causes deep → true; `23505` and `40P01` → false.
  - `SerializableTxTest` (DB): T-1.6 two SERIALIZABLE transactions on a scratch table (`on_call(doctor, on_call)`), each reads
    "how many on call" and takes itself off call, with a latch so both read before either writes. One side gets a real `40001`;
    after retry it sees one doctor left and does nothing. At least one doctor stays on call, `retryCount` grows by ≥ 1.
    T-1.7 a callback that always throws a `40001` cause is called exactly `maxAttempts` times, then the error is rethrown.
    T-1.8 calling `execute` inside a `TransactionTemplate` throws `IllegalStateException`.
- **Done when:** T-1.4 to T-1.8 pass.

### - [x] 1.4 Case-level hold: `LegalHoldService` and API
> Done 2026-10-10: `LegalHoldService` (`placeCaseHold` with case lock + evidence `FOR SHARE` + SERIALIZABLE, hook `pauseBeforeCommit`), `LegalHoldController`, `package-info` (M-2). T-1.9 to T-1.13 green.
- **Code:** `placeCaseHold(caseId, reason, placedBy)` inside `SerializableTx`, building everything inside the callback (M-3):
  (1) `SELECT id FROM case_file WHERE id = :id FOR NO KEY UPDATE` (empty → `NotFoundException`);
  (2) `SELECT id FROM evidence WHERE case_id = :id AND deleted_at IS NULL ORDER BY id FOR SHARE` → `frozenEvidenceIds` (B-1);
  (3) insert `legal_hold` (`placedAt = clock.instant()`); (4) run the `pauseBeforeCommit` hook (M-1).
  The Javadoc explains why all three mechanisms are needed (lock table in §5).
  `releaseCaseHold`: `UPDATE legal_hold SET released_at = :now, version = version + 1 WHERE id = :id AND case_id = :caseId AND released_at IS NULL`;
  0 rows → 404 if the hold does not exist for that case, else 409. `listActive(caseId)`: 404 via `CaseFileService.get`.
  `LegalHoldController` with records `PlaceHoldRequest(@NotBlank @Size(max=500) reason, @NotBlank @Size(max=128) placedBy)` and `HoldResponse`.
  Write `retention/package-info.java` noting the direct SQL on `evidence`/`case_file` (M-2).
- **Test:** `LegalHoldApiTest` (DB, MockMvc): T-1.9 placing a hold returns 201 with `frozenEvidenceIds` = the live evidence
  (excluding already deleted ones); T-1.10 unknown case → 404 ProblemDetail; T-1.11 releasing twice → 204 then 409;
  T-1.12 GET returns only active holds; T-1.13 blank `reason` → 400 ProblemDetail.
- **Done when:** T-1.9 to T-1.13 pass.

### - [x] 1.5 Evidence-level hold (same row)
> Done 2026-10-10: `holdEvidence`/`releaseEvidence` (atomic UPDATE), `EvidenceHoldController`, test helper `support/SqlStates`. T-1.14 to T-1.16 green (T-1.16 twice).
- **Code:** `holdEvidence`: `UPDATE evidence SET legal_hold = true, version = version + 1 WHERE id = :id AND deleted_at IS NULL`;
  0 rows → 404 if the evidence does not exist, 409 if it is deleted. `releaseEvidence`: same, with `legal_hold = false`.
  `EvidenceHoldController`. Both `@Transactional`, READ COMMITTED.
- **Test:** `EvidenceHoldApiTest` (DB): T-1.14 hold then release → 204, the flag changes; T-1.15 holding deleted evidence → 409;
  T-1.16 10 evidence × (1 `holdEvidence` + 1 raw unguarded soft-delete) = 20 threads with `Concurrently` (M-5): each operation
  either succeeds, gets 409 (hold lost), or fails with `23514` (delete lost); **every evidence ends up exactly one of held / deleted**.
- **Done when:** T-1.14 to T-1.16 pass.

### - [x] 1.6 `RetentionStrategy`, naive version, job, admin endpoint
> Done 2026-10-10: `RetentionStrategy`, `RetentionSql` (shared filter B-4 + guarded delete M-4), all 4 strategy classes (behaviour test needs them),
> `RetentionProperties`, `RetentionStrategies`, `RetentionJob` + `RetentionConfig`, `RetentionAdminController`, `support/RetentionTestData`.
> T-1.17 to T-1.22 green × 4 strategies (24 cases); T-1.23 green, plus two job tests (`jobRunsBatchesUntilOneIsShort`, `jobStopsAtMaxBatchesPerRun`).
> T-1.24 reproduced the anomaly 5/5 runs (frozen evidence deleted).
- **Code:** `RetentionStrategy`, `RetentionProperties`, `RetentionStrategies`, `NaiveRetentionService`
  (candidates `SELECT id FROM evidence e WHERE <filter> ORDER BY retain_until, id LIMIT :n` → hook → delete), `RetentionJob` (M-8),
  `RetentionAdminController`, config in `application.yml`. `RetentionTestData` in test `support/`: `reset(jdbc)` (B-5)
  and helpers to create a case with N expired evidence (insert via JDBC, `retain_until` in the past).
- **Test:**
  - `RetentionBehaviourTest` (DB, `@ParameterizedTest` over the 4 strategies, single-threaded): T-1.17 expired evidence
    is deleted (`deleted_at`, `status = DELETED`); T-1.18 not-yet-expired evidence is kept; T-1.19 evidence of a case with an
    active hold is kept, and deleted after the hold is released; T-1.20 evidence with `legal_hold = true` is kept;
    T-1.21 `batchSize` is respected; T-1.22 `batchSize` held-case rows that expired first do **not** stop later evidence from
    being deleted (B-4).
  - `RetentionAdminApiTest#runsOneBatch` (DB, MockMvc): T-1.23 returns 200 `{strategy: "LOCKING", deleted: n}`.
  - `WriteSkewReproductionTest#naive_holdPlacedDuringRetention_reproducesAnomaly` (DB): retention waits in
    `pauseAfterCheck`, the test thread places a hold (which succeeds without blocking, frozen = [E]), then releases retention.
    **Assert that E is in `frozenEvidenceIds` and is deleted** (invariant violated; the test is green because it records the bug). T-1.24.
- **Done when:** T-1.17 to T-1.24 pass, T-1.24 gives the same result in 5/5 runs.

### - [x] 1.7 Fix A: locking (`LockingRetentionService`)
> Done 2026-10-10: T-1.25 green (hold blocked on the case lock, then one `40001` retry, frozen list empty). T-1.26 green 4 runs
> (2 before and 2 after B-6): 0/50 violating rounds, 0 errors, ~200 hold retries per run.
- **Code:** each batch, in one READ COMMITTED transaction (B-2):
  (1) `SELECT id, case_id FROM evidence e WHERE <filter> ORDER BY retain_until, id LIMIT :n` (no lock);
  (2) `SELECT id FROM case_file WHERE id = ANY(:caseIds) ORDER BY id FOR SHARE`;
  (3) `SELECT id, case_id FROM evidence WHERE id = ANY(:ids) AND retain_until < :now AND deleted_at IS NULL AND legal_hold = false FOR UPDATE SKIP LOCKED`;
  (4) `SELECT DISTINCT case_id FROM legal_hold WHERE case_id = ANY(:caseIds) AND released_at IS NULL` (a new statement, so it sees
  every hold committed before the case lock was granted); (5) hook `pauseAfterCheck`; (6) soft-delete the rest.
  `FOR SHARE` on the case conflicts with `placeCaseHold`'s `FOR NO KEY UPDATE`, so the two are serialized per case.
- **Test:**
  - `WriteSkewReproductionTest#fixA_holdPlacedDuringRetention_preventsAnomaly` (DB): retention waits in `pauseAfterCheck`; a second
    thread calls `placeCaseHold`; `LockWaits` confirms it **is blocked**; retention is released and commits; the hold then gets
    `40001` once (B-1), retries, and returns frozen ids **without** E. Invariant holds, `retryCount` grows by ≥ 1. T-1.25.
  - `RetentionStressTest#locking_neverDeletesFrozenEvidence` (DB): 50 rounds; each round has 4 cases × 10 expired evidence,
    8 retention calls (batch 5) and 8 `placeCaseHold` calls on random cases at once. Every call succeeds (no deadlock, retries
    within the limit) and the invariant holds in every round. T-1.26.
- **Done when:** T-1.25 and T-1.26 pass twice in a row.

### - [x] 1.8 Fix B: SERIALIZABLE (`SerializableRetentionService`)
> Done 2026-10-10: T-1.27 green. T-1.28 first failed with 4 × `40P01` + 1 × exhausted `40001` → findings B-6 (soft-delete
> locks in id order, also C) and M-12, accepted. After B-6: green 2 runs, 0/50 violating rounds, 0 deadlocks, ~900 retries,
> 4 exhausted `40001` per run (B's cost under contention).
- **Code:** the naive algorithm inside `SerializableTx`, no row locks.
- **Test:** `WriteSkewReproductionTest#fixB_holdPlacedDuringRetention_preventsAnomaly` (DB): same interleaving as T-1.24. The hold
  commits; retention's delete (or commit) gets `40001`, retries (the hook re-runs but its latches are already open, M-1), and
  now sees the hold: E is not deleted, `retryCount` grows by ≥ 1. T-1.27. `RetentionStressTest#serializable_neverDeletesFrozenEvidence`:
  same as T-1.26 for strategy B, and records the total retry count. T-1.28.
- **Done when:** T-1.27 and T-1.28 pass twice in a row.

### - [x] 1.9 Experiment C: single SQL statement (`SingleStatementRetentionService`)
> Done 2026-10-10: class written in 1.6. T-1.29 reproduced the anomaly 5/5 runs (UPDATE waited on the hold's share lock, then deleted with its old snapshot).
- **Code (B-3):**
  ```sql
  UPDATE evidence SET deleted_at = :now, status = 'DELETED', version = version + 1
  WHERE id IN (SELECT e.id FROM evidence e WHERE <filter> ORDER BY e.retain_until, e.id LIMIT :n FOR UPDATE)
  ```
- **Test:** `WriteSkewReproductionTest#singleStatement_holdCommittedWhileUpdateWaits_reproducesAnomaly` (DB): T2 runs `placeCaseHold`
  and waits in `pauseBeforeCommit` (hold inserted, evidence share-locked, not committed). T1 runs C, which blocks on E;
  `LockWaits` confirms it. T2 commits; T1 continues and deletes E. **Assert the invariant is violated**: atomic does not mean
  serializable. T-1.29.
- **Done when:** T-1.29 passes 5/5 times. The doc explains statement snapshots and why there is no re-check of a row that was
  locked but not updated.

### - [x] 1.10 Naive version stress test (tag `naive`) and failure rate
> Done 2026-10-10: `RetentionStressTest#naive_deletesFrozenEvidence_failureRate` (`@Tag("naive")`). T-1.30: **35/50** and **39/50** rounds violated the invariant (two runs), 0 errors;
> after B-6: **40/50** and **37/50**. Overall 151/200 rounds (75.5 %).
- **Code:** `RetentionStressTest#naive_…` with `@Tag("naive")`, same configuration as T-1.26.
- **Test:** run separately with `./mvnw test -Dgroups=naive -DexcludedGroups= -Dtest=RetentionStressTest` and record the rate of
  rounds that violate the invariant. T-1.30.
- **Done when:** the failure rate (e.g. "37/50 rounds") is in the notes and in the doc.

### - [x] 1.11 Benchmark A, B, C (tag `bench`)
> Done 2026-10-10: `RetentionBenchmarkTest` (+ naive baseline, `-Dbench.strategies=` to run a subset, failures by SQLState). Median of 3:
> A 3 478 deleted/s, p95 batch 36 ms, p95 hold 349 ms, 0 frozen lost; B 5 259/s, 302 ms, 15 ms, ~20 exhausted purges; C 6 476/s, 24 frozen lost;
> naive 6 458/s, 21 lost. Table in `target/bench/retention.md` and in doc 01. Found M-13.
- **Code:** `RetentionBenchmarkTest` (`@Tag("bench")`): 10k expired evidence across 200 cases, 8 retention threads, 4 threads
  placing holds continuously. Measure throughput (evidence deleted/second), p95 batch time, total retries (B), lock wait time (A),
  and invariant violations (C). Print a Markdown table to the console and to `target/bench/retention.md`. 3 runs, median (M-11).
- **Test:** the benchmark itself, T-1.31 (not in the default build).
- **Done when:** there is a results table for A, B, C.

### - [x] 1.12 Choose the default, write docs
> Done 2026-10-10: default stays `locking` (ADR 0001: no wasted batches, correctness does not depend on every writer being SERIALIZABLE).
> `docs/concurrency/01-write-skew-legal-hold.md`, `docs/adr/0001-retention-strategy.md`, `docs/ARCHITECTURE.md` (diagram, data model, write paths, endpoints, UI),
> `docs/PLAN.md` Phase 1 ticked, README row 1 done. `./mvnw verify` green: 56 tests.
- **Code:** set the default `strategy` based on the results (expected: `locking`).
- **Docs:** `docs/concurrency/01-write-skew-legal-hold.md` following `_TEMPLATE.md`, with both interleaving tables from §5,
  the naive failure rate, the benchmark table, why C fails, the B-1 story, and the decision. `docs/adr/0001-retention-strategy.md`.
  `docs/ARCHITECTURE.md`: new endpoints, write path table (§4) from the lock table above, data model. Tick Phase 1 in `docs/PLAN.md`.
  README "Hard problems solved" row 1 → done.
- **Done when:** `./mvnw verify` is green, the docs exist and contain real numbers.

### - [ ] 1.13 (Optional) UI
> 2026-10-10: code done (`index.html`, `app.js`, `style.css`: place/release case hold, hold/release per evidence, "Run retention").
> **Left unticked: needs your manual check in the browser** (restart the app; Flyway applies V2 to your local DB).
- **Code:** in `static/`, a "Place legal hold" button on the case page, a hold column, and a "Run retention" button that calls the admin endpoint.
- **Test:** manual check (static UI, no JUnit).
- **Done when:** holds can be placed and retention run from the UI, errors are shown as ProblemDetail.

## 7. Test cases
| ID | Type | Test | Scenario | Expected |
|----|------|------|----------|---------|
| T-1.1 | DB | `LockWaitsTest#detectsSessionBlockedOnRowLock` | thread 2 waits on a row lock | helper detects it within the timeout |
| T-1.2 | DB | `LegalHoldSchemaTest#migrationValidates` | Flyway V2 + Hibernate validate | context loads |
| T-1.3 | DB | `LegalHoldSchemaTest#checkRejectsHeldAndDeleted` | SQL violating the CHECK | `23514` |
| T-1.4 | Unit | `SerializableTxBackoffTest` | delay by attempt | in range, growing, capped |
| T-1.5 | Unit | `SerializationFailuresTest` | nested 40001; 23505; 40P01 | true / false / false |
| T-1.6 | DB | `SerializableTxTest#retriesRealSerializationFailure` | real SERIALIZABLE write skew | invariant holds, retry ≥ 1 |
| T-1.7 | DB | `SerializableTxTest#givesUpAfterMaxAttempts` | always 40001 | `maxAttempts` calls, then rethrows |
| T-1.8 | DB | `SerializableTxTest#refusesToRunInsideTransaction` | outer transaction active | `IllegalStateException` |
| T-1.9 | DB | `LegalHoldApiTest#placeHold` | POST hold | 201, frozen ids = live evidence |
| T-1.10 | DB | `LegalHoldApiTest#unknownCase` | case does not exist | 404 ProblemDetail |
| T-1.11 | DB | `LegalHoldApiTest#releaseTwice` | DELETE twice | 204 then 409 |
| T-1.12 | DB | `LegalHoldApiTest#listActiveOnly` | some holds released | only active holds |
| T-1.13 | DB | `LegalHoldApiTest#validation` | blank `reason` | 400 ProblemDetail |
| T-1.14 | DB | `EvidenceHoldApiTest#holdAndRelease` | hold / release | 204, flag changes |
| T-1.15 | DB | `EvidenceHoldApiTest#holdDeletedEvidence` | evidence already deleted | 409 |
| T-1.16 | DB | `EvidenceHoldApiTest#concurrentHoldAndDelete` | 20 threads | each evidence exactly one of held / deleted |
| T-1.17 → T-1.22 | DB | `RetentionBehaviourTest` × 4 strategies | single-threaded | retention rules respected, no starvation (T-1.22) |
| T-1.23 | DB | `RetentionAdminApiTest#runsOneBatch` | POST run | 200 `{strategy, deleted}` |
| T-1.24 | DB | `WriteSkewReproductionTest#naive_…` | deterministic interleaving | **invariant violated** |
| T-1.25 | DB | `…#fixA_…` | same interleaving | hold blocked, then 40001 → retry, invariant holds |
| T-1.26 | DB | `RetentionStressTest#locking_…` | 50 rounds × 16 threads | invariant holds, no errors |
| T-1.27 | DB | `…#fixB_…` | same interleaving | retention 40001 → retry, invariant holds |
| T-1.28 | DB | `RetentionStressTest#serializable_…` | stress | invariant holds, retries recorded |
| T-1.29 | DB | `…#singleStatement_…` | hold commits while C waits | **invariant violated** |
| T-1.30 | DB `naive` | `RetentionStressTest#naive_…` | naive stress | failure rate recorded |
| T-1.31 | DB `bench` | `RetentionBenchmarkTest` | mixed load | results table |

## 8. Docs to update
- `docs/concurrency/01-write-skew-legal-hold.md`, `docs/adr/0001-retention-strategy.md`, `docs/ARCHITECTURE.md`,
  tick `docs/PLAN.md`, README table. Consider `/log-ai-mistake` for B-1 (the original plan's Fix A was wrong).

## 9. Definition of Done
- [x] All findings in §0 decided (M-13 was found afterwards by the benchmark and is still open)
- [x] `./mvnw verify` green, including T-1.1 to T-1.29 (56 tests, 2026-10-10)
- [x] Naive failure rate (T-1.30) and benchmark table (T-1.31) recorded
- [x] Doc 01 and ADR 0001 contain real numbers

## 10. Open questions
- After a hold is released, expired evidence of that case becomes deletable on the next run. Is a grace period wanted?
  Default: no grace period (out of scope for this phase).
