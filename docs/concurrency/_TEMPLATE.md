# NN – <Problem name>

## Problem
Business scenario and the anomaly (lost update / write skew / double processing / ...).

## Naive implementation
Class: `...Naive...`. Why it looks correct, and the exact interleaving that breaks it:

| T1 | T2 |
|----|----|
|    |    |

## Reproduction
Test: `...Test#...` (Postgres via Testcontainers, `Concurrently.run(...)`).
Observed failure rate with the naive version: __ / __ runs.

## Fix(es)
| Approach | How | Throughput | p95 | Retries |
|----------|-----|-----------:|----:|--------:|
| A        |     |            |     |         |
| B        |     |            |     |         |

## Decision and trade-offs
Why this approach, and what it costs.

## AI notes
What the AI assistant generated here, and what was wrong with it.
