# Phase 3 – Job queue (SKIP LOCKED, lease, fencing) and Transactional Outbox

> Status: ⬜ Not started · Roadmap: `docs/PLAN.md` §3 Phase 3 · Implement with `/impl-phase 3 [step]`
> Rules: `rules/coding/concurrency.md`, `rules/coding/db.md`, `rules/testing/TEST_STRATEGY.md`

## 1. Goal
A job queue that runs directly on Postgres, with no broker:
- multiple workers **never run the same job twice**;
- if a worker dies midway, the job is **not lost** (once the lease expires, another worker reclaims it);
- a "zombie worker" (paused for a long time, then wakes up) **cannot overwrite** the result, thanks to a fencing token.

Alongside it is an outbox: events are written **in the same transaction** as the business change, the relay
publishes them with at-least-once semantics, and handlers are idempotent. This is STAR story #2 (fencing token).

**Invariant:** (job) every `DONE` job has exactly one accepted completion, and no job is lost;
(outbox) every committed change has an event, and the handler's effect is applied exactly once.

## 2. Scope
**In scope:** `job` table, worker pool, lease, fencing, backoff, `DEAD` status; `outbox_event` table, relay,
`processed_event`; one real job type, `EVIDENCE_PROCESS` (moves evidence from `PROCESSING` to `AVAILABLE`).
**Out of scope:** an external message broker (Kafka/RabbitMQ). The relay publishes through the `EventPublisher`
interface; the default implementation calls handlers in the same process. No extra infrastructure needed.

## 3. Prerequisites
- Phase 1 done (`Clock`, `LockWaits`, test tags exist). Phase 2 should be done so jobs can also write custody events.

## 4. Changes vs PLAN.md
- The "kill worker" and "crash between commit and publish" scenarios are simulated **in JUnit**: stop the worker
  thread before complete, or throw right after publish and before marking as published. No need to kill a real process.
- The relay preserves per-aggregate order by allowing only **one active relay** at a time, via
  `pg_try_advisory_lock` (leader). The trade-off is recorded in an ADR.

## 5. Design

### Schema – `V4__job_queue.sql`
```sql
CREATE TABLE job (
    id           UUID PRIMARY KEY,
    type         VARCHAR(64)  NOT NULL,
    payload      JSONB        NOT NULL,
    status       VARCHAR(16)  NOT NULL CHECK (status IN ('PENDING','RUNNING','DONE','DEAD')),
    attempts     INT          NOT NULL DEFAULT 0,
    max_attempts INT          NOT NULL DEFAULT 5,
    run_at       TIMESTAMPTZ  NOT NULL DEFAULT now(),
    locked_by    VARCHAR(64),
    lease_until  TIMESTAMPTZ,
    fence        BIGINT       NOT NULL DEFAULT 0,   -- incremented on every claim
    last_error   TEXT,
    created_at   TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at   TIMESTAMPTZ  NOT NULL DEFAULT now()
);
-- Claim scan: runnable pending jobs, plus running jobs whose lease expired.
CREATE INDEX idx_job_claimable ON job (run_at) WHERE status IN ('PENDING', 'RUNNING');
```
### Schema – `V5__outbox.sql`
```sql
CREATE TABLE outbox_event (
    id             BIGSERIAL PRIMARY KEY,          -- commit-independent order inside one aggregate
    event_id       UUID         NOT NULL UNIQUE,
    aggregate_type VARCHAR(64)  NOT NULL,
    aggregate_id   UUID         NOT NULL,
    type           VARCHAR(64)  NOT NULL,
    payload        JSONB        NOT NULL,
    created_at     TIMESTAMPTZ  NOT NULL DEFAULT now(),
    published_at   TIMESTAMPTZ
);
CREATE INDEX idx_outbox_unpublished ON outbox_event (id) WHERE published_at IS NULL;

CREATE TABLE processed_event (
    consumer   VARCHAR(64) NOT NULL,
    event_id   UUID        NOT NULL,
    processed_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (consumer, event_id)
);
```

### Core algorithms
- **Claim** (a single statement, READ COMMITTED):
  ```sql
  UPDATE job SET status='RUNNING', locked_by=:worker, lease_until=:now + :lease, fence=fence+1, attempts=attempts+1, updated_at=:now
  WHERE id IN (SELECT id FROM job
               WHERE (status='PENDING' AND run_at <= :now) OR (status='RUNNING' AND lease_until < :now)
               ORDER BY run_at LIMIT :n FOR UPDATE SKIP LOCKED)
  RETURNING id, type, payload, fence, attempts;
  ```
- **Complete or fail** is accepted only if the fence still matches:
  `UPDATE job SET status='DONE' … WHERE id=:id AND fence=:fence AND status='RUNNING'`. 0 rows means this worker has
  been superseded, so discard the result and increment a metric. On fail, set `run_at = now + backoff(attempts)`, or
  move to `DEAD` when `attempts >= max_attempts`.
- **Handler side effects** must also be fence-protected. `EVIDENCE_PROCESS` updates the evidence **in the same
  transaction** as the fence-conditioned complete, so a zombie cannot write anything.

### New classes
| Class | Package | Role | Transaction / isolation |
|---|---|---|---|
| `JobQueue` | `jobs` | `enqueue`, `claim`, `complete`, `fail` | each operation is one atomic UPDATE, READ COMMITTED |
| `NaiveJobQueue` | `jobs` | claim = `SELECT … LIMIT n` (no lock), then `UPDATE status='RUNNING' WHERE id=?` | READ COMMITTED (wrong) |
| `Backoff` | `jobs` | `delay(attempt)` exponential + full jitter, capped | – |
| `JobWorker`, `JobWorkerPool` | `jobs` | claim, process, complete loop; configurable worker count; uses virtual threads | handler + complete in one transaction |
| `JobHandler` (interface), `EvidenceProcessHandler` | `jobs`, `evidence` | processing by `type` | – |
| `Outbox` | `outbox` | `record(aggregate, type, payload)`, `propagation = MANDATORY` | same transaction as the business change |
| `OutboxRelay` | `outbox` | leader via `pg_try_advisory_lock`, reads by `id`, publishes, marks `published_at` | READ COMMITTED |
| `EventPublisher`, `InProcessEventPublisher` | `outbox` | dispatches events to `EventHandler`s | – |
| `IdempotentHandlerSupport` | `outbox` | `INSERT INTO processed_event … ON CONFLICT DO NOTHING`, skip on 0 rows | same transaction as the handler's effect |

## 6. Steps (code + tests together)

### - [ ] 3.1 `Backoff`
- **Test:** `BackoffTest` (Unit): T-3.1 within `[0, min(cap, base·2^n)]`; T-3.2 statistical mean increases with `n`; T-3.3 no overflow for large `n`.

### - [ ] 3.2 Migration V4 and basic `JobQueue`
- **Code:** `/new-migration job queue`, `JobQueue.enqueue/claim/complete/fail`.
- **Test:** `JobQueueTest` (DB, with `MutableClock`): T-3.4 claims in `run_at` order; T-3.5 a job before its `run_at`
  is not claimed; T-3.6 on fail, `run_at` moves forward by backoff; T-3.7 exceeding `max_attempts` gives `DEAD`; T-3.8 complete with a stale fence is rejected.
- **Done when:** T-3.1 to T-3.8 pass.

### - [ ] 3.3 Naive version and duplicate-run test
- **Code:** `NaiveJobQueue` with a `pauseAfterSelect` hook.
- **Test:** `JobQueueReproductionTest#naive_twoWorkersClaimSameJob` (DB, deterministic interleaving): **asserts that two
  workers claim the same job**. T-3.9.
- **Done when:** T-3.9 passes 5/5 runs.

### - [ ] 3.4 SKIP LOCKED under load
- **Test:** `JobQueueStressTest#skipLocked_10kJobs8Workers_noDuplicateNoLoss` (DB): the handler records each execution
  in a test table `job_execution(job_id, fence)`. Asserts: all 10k jobs are `DONE`, and each job has exactly one
  accepted completion. T-3.10.
- **Done when:** T-3.10 passes twice in a row.

### - [ ] 3.5 Lease and fencing (dead worker, zombie worker)
- **Code:** `JobWorkerPool` uses virtual threads, configurable lease, heartbeat extends the lease during long processing (optional).
- **Test:** `JobLeaseTest` (DB):
  T-3.11 worker A claims and then "dies" (does not complete); advance `MutableClock` past `lease_until`; worker B
  reclaims with fence + 1 and completes. **No job is lost.**
  T-3.12 (zombie) A wakes up and calls complete together with its side effect using the stale fence: 0 rows, the
  evidence is **not** overwritten by A, and the "rejected" metric increases.
  T-3.13 stress check: 8 workers, 20% of jobs randomly abandoned midway; in the end every job is `DONE`.
- **Done when:** T-3.11 to T-3.13 pass.

### - [ ] 3.6 Real job: `EVIDENCE_PROCESS`
- **Code:** `EvidenceService.register` enqueues the job **in the same transaction**. The handler moves the evidence to
  `AVAILABLE` (Phase 4 adds real SHA-256 computation). The pool is enabled in the app and disabled in tests unless a test needs it.
- **Test:** `EvidenceProcessingTest` (DB): T-3.14 registering evidence creates a job; running the pool makes the evidence `AVAILABLE`;
  T-3.15 a failed registration (rollback) creates no job.
- **Done when:** T-3.14, T-3.15 pass.

### - [ ] 3.7 Outbox and relay
- **Code:** `/new-migration outbox`, `Outbox.record` called from `EvidenceService.register` (event `EvidenceRegistered`),
  `OutboxRelay` (leader advisory lock, `@Scheduled`), `InProcessEventPublisher`.
- **Test:** `OutboxTest` (DB):
  T-3.16 business transaction rolls back, so there is no event;
  T-3.17 on commit, the relay publishes exactly once and marks `published_at`;
  T-3.18 (crash after publish, before marking) the relay runs again and publishes **again**; the handler is idempotent, so the effect happens exactly once;
  T-3.19 two relays run in parallel, only one does work (advisory lock), and `id` order is preserved per aggregate.
- **Done when:** T-3.16 to T-3.19 pass.

### - [ ] 3.8 Naive stress, benchmark, docs
- **Code:** `JobQueueStressTest#naive_…` with `@Tag("naive")` (rate of duplicate job runs), `JobQueueBenchmarkTest` with
  `@Tag("bench")` (jobs/second for 1, 2, 4, 8, 16 workers; p95 queue latency).
- **Docs:** `docs/concurrency/03-job-queue.md`, `04-outbox.md`, `docs/adr/0003-postgres-job-queue.md`,
  `0004-outbox-single-relay.md`, `docs/ARCHITECTURE.md`, tick `docs/PLAN.md`.
- **Done when:** `./mvnw verify` is green, docs contain numbers.

## 7. Test cases
| ID | Type | Test | Scenario | Expected |
|----|------|------|----------|---------|
| T-3.1 → T-3.3 | Unit | `BackoffTest` | bounds, trend, overflow | in range, increasing, no overflow |
| T-3.4 → T-3.8 | DB | `JobQueueTest` | order, run_at, backoff, DEAD, stale fence | correct status |
| T-3.9 | DB | `JobQueueReproductionTest#naive_…` | 2 workers SELECT at once | **duplicate run** |
| T-3.10 | DB | `JobQueueStressTest#skipLocked_…` | 10k jobs, 8 workers | no duplicates, no loss |
| T-3.11 | DB | `JobLeaseTest#deadWorkerJobIsReclaimed` | lease expired | another worker completes |
| T-3.12 | DB | `JobLeaseTest#zombieWriteIsFenced` | stale fence | rejected, data unchanged |
| T-3.13 | DB | `JobLeaseTest#randomAbandonStress` | 20% of jobs abandoned | all DONE |
| T-3.14, T-3.15 | DB | `EvidenceProcessingTest` | real flow, rollback | AVAILABLE / no job |
| T-3.16 → T-3.19 | DB | `OutboxTest` | rollback, publish, crash, 2 relays | exactly one effect, correct order |

## 8. Docs to update
- `docs/concurrency/03-job-queue.md`, `04-outbox.md`, ADR 0003 and 0004, `docs/ARCHITECTURE.md`, `docs/PLAN.md`.

## 9. Definition of Done
- [ ] `./mvnw verify` green
- [ ] Numbers: duplicate-run rate of the naive version, throughput by worker count
- [ ] Fencing token story written as STAR in doc 03

## 10. Open questions
- Should `RetentionJob` (Phase 1) move to the job queue? Proposal: keep `@Scheduled`, since SKIP LOCKED is already
  safe when multiple instances run at once.
- Is a heartbeat to extend the lease needed, or is a long enough lease sufficient? Proposal: do it later, if benchmarks show long jobs.
