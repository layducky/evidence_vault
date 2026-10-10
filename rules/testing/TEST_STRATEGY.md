# Test strategy

**JUnit only.** No E2E, browser, Postman or k6 suites in the build. (k6 may be used for benchmarks, outside the
test suite.) There are exactly two kinds of test:

| Kind | Setup | Use for | Naming |
|---|---|---|---|
| **Unit** | Plain JUnit 6 (+ Mockito only for collaborators that are not the DB) | Pure logic: hashing, canonical JSON, backoff, state machines, validation, mapping | `<Class>Test` |
| **DB** | `@SpringBootTest` + `@Import(TestcontainersConfiguration.class)`, real Postgres 17 | Repositories, services, constraints, migrations, concurrency, HTTP status/`ProblemDetail` via MockMvc | `<Feature>…Test` |

## Database
- **MUST** run every test that touches the DB against real PostgreSQL via `TestcontainersConfiguration`.
  In-memory databases and mocked repositories are banned for anything involving SQL, locking, isolation or
  constraints. A `PreToolUse` hook blocks adding an in-memory DB.
- **MUST NOT** depend on the developer's local database or `application-local.properties`.
- **SHOULD** make tests independent of each other's data: unique keys per test (random `caseNumber` suffix),
  no reliance on execution order.

## Concurrency tests
Each concurrency problem has three kinds of test, all JUnit + Testcontainers:

1. **Deterministic reproduction**: force the exact bad interleaving with latches (e.g. a package-private
   `pauseAfterCheck` hook in the naive service that the test blocks on). Asserts that the naive version
   **produces the anomaly** (green, stable), and that each fix **prevents it** under the same interleaving.
   Name: `naive_<scenario>_reproducesAnomaly`, `fixA_<scenario>_preventsAnomaly`.
2. **Stress**: `support/Concurrently` with N threads × M rounds against each fix, asserting the **invariant**
   (no evidence both held and deleted; no fork in the hash chain; no job run twice). Must pass every run.
3. **Naive stress** (optional): the same stress test against the naive version, tagged `@Tag("naive")` and
   excluded from the default build. Run on demand to measure the failure rate:
   `./mvnw test -Dgroups=naive -DexcludedGroups=none`.

- **MUST** assert the invariant itself, not just "no exception was thrown".
- **MUST NOT** use `Thread.sleep` to create interleavings. Use latches or barriers.
- **MUST** record the observed failure rate of the naive version in the problem's doc.

## Running
- Full suite: `./mvnw verify` or IntelliJ **All tests** (Docker must be running).
- One class or method: `./mvnw -B -q test -Dtest='ClassName#method' -Dsurefire.failIfNoSpecifiedTests=false`.
- The `Stop` hook only compiles (`test-compile`). Run the relevant tests before saying work is done.
