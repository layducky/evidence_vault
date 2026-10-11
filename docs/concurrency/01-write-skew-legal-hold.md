# 01 – Write skew: legal hold vs retention purge

## Problem
Evidence has a retention date. A background job soft-deletes evidence once `retain_until` has passed. A lawyer
can place a **legal hold** on a case; from then on no evidence of that case may be deleted. Placing a hold
returns the list of evidence it froze (`frozenEvidenceIds`).

**Invariant:** evidence listed in `frozenEvidenceIds` of a successful hold is never deleted.

The hold is a row in `legal_hold`; the purge writes rows in `evidence`. The two transactions write **different
rows**, so no row lock and no constraint ever sees both. Each transaction is correct on its own; together they
break the invariant. That is **write skew**, and READ COMMITTED (the Postgres default) does nothing about it.

(An evidence-level hold, the `evidence.legal_hold` column, is a *same-row* race instead. Postgres handles it:
the delete re-checks `legal_hold = false` after waiting for the row lock, and
`CHECK (NOT (legal_hold AND deleted_at IS NOT NULL))` rejects anything that slips through. `EvidenceHoldApiTest`
covers it.)

## Naive implementation
`NaiveRetentionService`, READ COMMITTED, no locks: select expired candidates whose case has no active hold, then
soft-delete them. It looks correct because the hold check is right there in the query.

| T1: retention (naive) | T2: `placeCaseHold` |
|----|----|
| select candidates: case X has no hold, so evidence E qualifies | |
| *(paused between check and delete)* | |
| | lock case X, read live evidence (T1 holds no locks) → E is frozen |
| | insert the hold, commit, return `frozenEvidenceIds = [E]` |
| soft-delete E, commit | |
| **frozen evidence E is gone** | |

## Reproduction
- Deterministic: `WriteSkewReproductionTest#naive_holdPlacedDuringRetention_reproducesAnomaly` pauses the purge
  in a `pauseAfterCheck` hook (latches, no sleeps), places the hold, then lets the purge finish. It reproduces the
  anomaly **5/5** runs.
- Stress: `RetentionStressTest#naive_deletesFrozenEvidence_failureRate` (`@Tag("naive")`): 50 rounds, each with
  4 cases × 10 expired evidence, 8 purges and 8 holds released at once.

Observed failure rate with the naive version: **151 / 200 rounds (75.5 %)** over four runs (35, 39, 40, 37 of 50).

## Fix(es)

| Approach | How | Deleted/s | p95 batch | p95 hold | Retries | Failed calls | Frozen deleted |
|----------|-----|----------:|----------:|---------:|--------:|-------------:|---------------:|
| A – locking | case `FOR SHARE` (id order) → evidence `FOR UPDATE SKIP LOCKED` → re-read holds → delete | 3 478 | 36 ms | 349 ms | 45 | 1 | **0** |
| B – serializable | the naive algorithm at SERIALIZABLE, retry 40001 | 5 259 | 302 ms | 15 ms | 186 | 20 | **0** |
| C – single statement | one `UPDATE … WHERE id IN (SELECT … NOT EXISTS (hold) …)` | 6 476 | 36 ms | 115 ms | 26 | 0 | 24 |
| naive (baseline) | check, then delete | 6 458 | 30 ms | 109 ms | 27 | 0 | 21 |

`RetentionBenchmarkTest` (`@Tag("bench")`): 10 000 expired evidence across 200 cases; 8 purge threads (batch 100);
4 threads placing up to 40 holds, paced by purge progress. Median of 3 runs, Postgres 17 in Testcontainers on
Docker Desktop (Windows), JDK 25, 8 CPUs. "Retries" counts every `SerializableTx` retry, including the holds'.
"Failed calls" are calls that exhausted their 5 attempts (`40001`). Absolute numbers are noisy on this machine;
the comparison between rows is what matters.

Correctness evidence for the fixes (default build, every CI run):
`WriteSkewReproductionTest#fixA_…` and `#fixB_…` replay the exact naive interleaving and keep the invariant;
`RetentionStressTest#locking_…` and `#serializable_…` hold it over 50 rounds (0 violating rounds in every run).

### The hold side needs three mechanisms
`LegalHoldService.placeCaseHold` runs at **SERIALIZABLE** and, inside that, locks the case `FOR NO KEY UPDATE`
and reads the frozen evidence `FOR SHARE`:
- the **case lock** conflicts with A's `FOR SHARE`, so a hold and a purge of the same case run one after the other;
- **SERIALIZABLE** lets SSI see the read/write cycle with B; SSI ignores transactions at other isolation levels;
- the **`FOR SHARE` read** fixes a hole in the first version of A, described next.

### Why the first locking design was wrong
The plan's first Fix A only locked the case. Review (finding B-1) showed it still loses frozen evidence:

| T1: retention A (first design) | T2: `placeCaseHold` (SERIALIZABLE) |
|----|----|
| lock evidence E, lock case X `FOR SHARE`, no hold found | |
| | first statement starts: **snapshot taken**, then waits for case X |
| soft-delete E, commit | |
| | gets the lock on X: the row was locked, not updated, so no error |
| | reads evidence **from its old snapshot**: E looks live → frozen = [E] ✗ |

A SERIALIZABLE (or REPEATABLE READ) transaction takes its snapshot when its first statement *starts*, before
that statement waits for a lock. With the `FOR SHARE` read, Postgres sees that E was updated after the snapshot
and raises `40001`; the retry gets a fresh snapshot without E. `#fixA_…` asserts exactly this: the hold blocks
(`LockWaits` watches `pg_stat_activity`), retries once, and returns an empty frozen list.

Two more issues showed up on the way, both fixed:
- **Deadlocks (finding B-2, B-6).** Once the hold locks evidence, every path must lock in the same order:
  case before evidence, evidence by id. The first B run in the stress test hit 4 × `40P01` because a plain
  `UPDATE … WHERE id IN (…)` locks rows in scan order. The shared soft-delete now locks `ORDER BY id FOR UPDATE`
  in a subquery. After that: 0 deadlocks.
- **Starvation (B-4).** If the purge selected the oldest expired rows and only then skipped held cases, a batch
  made only of held rows would delete nothing, forever. The hold check is part of the candidate query, and
  `RetentionBehaviourTest#heldCaseDoesNotStarveLaterEvidence` covers it for all four strategies.

### Why C (one statement) is still wrong
"Atomic" is not "serializable". At READ COMMITTED each statement reads the snapshot taken when *it* starts.
`#singleStatement_holdCommittedWhileUpdateWaits_reproducesAnomaly`:

| T1: C (one UPDATE) | T2: `placeCaseHold` |
|----|----|
| | lock case X, share-lock E, insert hold (not committed yet) |
| statement starts: snapshot has no hold; tries to lock E → waits | |
| | commit |
| gets E: it was only *locked* by T2, not updated, so Postgres does not re-check the row; the `NOT EXISTS (hold)` result from the old snapshot stands → deletes E ✗ | |

Reproduced 5/5 runs, and the benchmark shows C losing ~24 frozen evidence per run, as often as the naive version.
Postgres' re-check after a lock wait (EvalPlanQual) only re-evaluates the conditions on the row that was updated;
it never re-runs a subquery against a newer snapshot.

## Decision and trade-offs
**Default: A (locking).** See [`ADR 0001`](../adr/0001-retention-strategy.md).

- A never wastes a batch: 0–2 failed calls per run, all of them *holds*, not purges, and a stable p95 batch time.
  Its cost lands on throughput (purges of a case wait for a hold in progress) and on hold latency
  (a hold waits behind purges and may retry once per purge it waited for).
- B has the best throughput among the correct options and the fastest holds, but under contention it throws away
  work: ~186 retries and ~20 purge batches that exhausted 5 attempts per run, with a p95 batch time 8× higher.
  It also stays correct only while *every* transaction on these rows runs at SERIALIZABLE; one future READ
  COMMITTED writer would silently reintroduce the bug, and nothing would fail loudly.
- C and the naive version are fast because they do not coordinate at all, and they are wrong.

Known cost of A, open as finding M-13: under a heavy purge a hold can run out of retries (seen 1 in ~120 holds)
and the client gets a 500. The invariant still holds, since nothing was promised.

## AI notes
- The AI-written plan proposed Fix A as "lock the case row" and Fix C as a valid fix. Self-review against
  Postgres snapshot timing found that both were wrong (B-1, B-3) before any code was written.
- The deadlock between B and the hold (B-6) was not predicted by the review. The stress test caught it.
