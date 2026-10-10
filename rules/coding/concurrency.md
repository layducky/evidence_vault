# Concurrency rules

This project's selling point is correctness under concurrency. Every rule here is checked in review.

## Transactions
- **MUST** state, for every write path, the transaction boundary (which method is `@Transactional`) and the
  isolation level. Put it in the method Javadoc and in `docs/ARCHITECTURE.md` §4.
  Default is READ COMMITTED (Postgres default). Say so explicitly.
- **MUST NOT** call a `@Transactional` method on `this` and expect a new transaction (proxy bypass).
- **MUST NOT** do remote calls or long work while holding row locks.

## Choosing a fix
Prefer the cheapest correct option, in this order, and justify the choice in the problem's doc:

| Need | Use |
|---|---|
| Uniqueness / existence | DB constraint (`UNIQUE`, FK, partial unique index) + map the violation to 409 |
| Conditional state change on one row | Single atomic `UPDATE … WHERE <condition>`, check the row count |
| Read-modify-write on one row | Optimistic lock (`@Version`) → 409, or `SELECT … FOR UPDATE` |
| Invariant spanning several rows (write skew) | `SELECT … FOR UPDATE` on all involved rows, or SERIALIZABLE |
| Work queue | `FOR UPDATE SKIP LOCKED` + lease/fencing token |
| Serialize on a key with no row to lock | `pg_advisory_xact_lock(hashtext(key))` |

## Retries
- **MUST** retry SQLState `40001` (serialization failure) **only** for methods running at SERIALIZABLE,
  with a bounded number of attempts and jittered backoff. The whole transaction is retried, not one statement.
- **MUST NOT** add blanket retries to hide races at READ COMMITTED.
- **SHOULD** retry `40P01` (deadlock) only after fixing lock ordering. A retry is a fallback, not a fix.

## Locks
- **MUST** acquire multiple row locks in a deterministic order (e.g. by `id`) to avoid deadlocks.

## Proof
- **MUST** ship every concurrency fix with a reproduction test (`support/Concurrently`) that fails on the naive
  version and passes on the fix, plus `docs/concurrency/NN-*.md` from `_TEMPLATE.md`.
  Use the `/concurrency-problem` skill.
