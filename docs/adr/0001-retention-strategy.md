# ADR 0001 – Retention purge uses row locking (strategy A)

- Status: accepted
- Date: 2026-10-10
- Context doc: [`concurrency/01-write-skew-legal-hold.md`](../concurrency/01-write-skew-legal-hold.md)

## Context
The retention purge and "place a case legal hold" form a write skew: they write different rows (`evidence` and
`legal_hold`), so READ COMMITTED alone lets the purge delete evidence a hold has just reported as frozen
(75.5 % of stress rounds with the naive version). Three approaches were implemented behind `RetentionStrategy`
and measured with `RetentionBenchmarkTest`:

| | Deleted/s | p95 batch | p95 hold | Failed calls / run | Frozen deleted |
|---|---:|---:|---:|---:|---:|
| A – locking | 3 478 | 36 ms | 349 ms | 1 (a hold) | 0 |
| B – serializable | 5 259 | 302 ms | 15 ms | 20 (purges) | 0 |
| C – single statement | 6 476 | 36 ms | 115 ms | 0 | 24 |

## Decision
Use **A** (`evidence-vault.retention.strategy: locking`): per batch, lock the candidates' cases `FOR SHARE` in id
order, lock the evidence `FOR UPDATE SKIP LOCKED`, re-read active holds, delete. `placeCaseHold` locks the case
`FOR NO KEY UPDATE`, reads the frozen evidence `FOR SHARE`, and runs at SERIALIZABLE with retry.

C is rejected: it is incorrect. B stays in the code as a configurable alternative and for comparison.

## Consequences
- Correctness is local and explicit: the locks are visible in the SQL of the two paths that conflict.
  B's correctness would instead depend on every present and future writer of these rows running at SERIALIZABLE.
- The purge is a background job, so its lower throughput is acceptable. It never throws batches away, and its
  batch latency stays predictable.
- Holds wait behind purges of the same case (p95 ≈ 0.35 s) and can, rarely, exhaust their retries. Holds are rare
  and user-facing, so exhausted retries should become a clear "try again" error instead of a 500 (finding M-13).
- Every path that locks both a case and its evidence must lock the case first, then the evidence by id.
  `rules/coding/concurrency.md` requires a deterministic lock order; this ADR fixes which one.
