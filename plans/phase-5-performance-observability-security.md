# Phase 5 – Performance, observability, security

> Status: ⬜ Not started · Roadmap: `docs/PLAN.md` §3 Phase 5 · Implement with `/impl-phase 5 [step]`
> Rules: `rules/coding/concurrency.md`, `rules/coding/db.md`, `rules/testing/TEST_STRATEGY.md`

## 1. Goal
Prove the system is **correct with large data, high load and an unreliable network**, with measurements to back the story:
EXPLAIN before and after, p95/p99, pool exhaustion rescued by a bulkhead, no lost or duplicated data when the DB is slow or drops.
Add authentication and per-case authorization, with a last line of defense in the DB (Row-Level Security).

## 2. Scope
**In scope:** partition `custody_event`; full-text search; Spring Security + JWT; per-case authorization + RLS; Micrometer
metrics with a Prometheus endpoint; reproduce pool exhaustion and the bulkhead; k6 load test; chaos with Toxiproxy (in tests).
**Out of scope:** running Keycloak, a Prometheus server or Grafana on the dev machine (see §4).

## 3. Prerequisites
- Phases 1 to 4 done. k6 installed on the machine (`C:\Program Files\k6`).

## 4. Changes vs PLAN.md
1. **No Keycloak.** The app is an OAuth2 Resource Server that validates JWTs with a preconfigured RSA public key. The
   `local` profile has a `POST /api/dev/token` endpoint that issues test tokens (never enabled outside `local`). Tests sign
   their own tokens with a test key. The result is equivalent in terms of API security, but needs no extra infrastructure.
   To use Keycloak later, only `issuer-uri` changes.
2. **No Prometheus or Grafana running.** Only expose `/actuator/prometheus` and test the metrics. A dashboard is
   optional.
3. **Partition `custody_event` by `HASH(case_id)`, not by month.** A partitioned table requires every unique constraint
   to include the partition key. Monthly partitioning would force dropping `UNIQUE(case_id, seq)`, which is the basis of
   Phase 2 correctness. Hashing by `case_id` keeps the constraint, and querying a case's chain touches only one partition. Record
   the trade-off (data cannot be deleted by month with `DROP PARTITION`) in an ADR.
4. **Chaos runs in JUnit** with Testcontainers Toxiproxy, part of the build like regular DB tests. No manual chaos
   testing.

## 5. Design

### Dependencies to add
`spring-boot-starter-security`, `spring-boot-starter-oauth2-resource-server`, `micrometer-registry-prometheus`,
`org.testcontainers:testcontainers-toxiproxy` (test), `spring-security-test` (test).

### Schema (tentative)
- `V9__custody_event_partitioned.sql`: create `custody_event_p` `PARTITION BY HASH (case_id)` with 16 partitions, copy
  the data over, rename in the same transaction, reattach the append-only trigger and `UNIQUE (case_id, seq)`.
- `V10__evidence_search.sql`: `evidence.description TEXT`, `evidence.tags TEXT[]`,
  `search tsvector GENERATED ALWAYS AS (to_tsvector('simple', coalesce(file_name,'') || ' ' || coalesce(description,'') || ' ' || array_to_string(tags,' '))) STORED`,
  `CREATE INDEX … USING GIN (search)`.
- `V11__case_membership_rls.sql`: `case_member(case_id, user_id, role)`, enable `ROW LEVEL SECURITY` + `FORCE` on
  `case_file`, `evidence`, `custody_event`. Policies use `current_setting('app.user_id', true)`. Create a separate DB role
  `evidence_app` (not the owner) for the app, because the owner bypasses RLS without `FORCE`.

### Security
- Every `/api/**` requires a JWT, except `/share/{token}` and `/actuator/health`. The `sub` claim is the user id; `roles` includes `INVESTIGATOR`, `RECORDS_ADMIN`.
- Per-case authorization in the service layer (`CaseAccess.require(caseId, role)`). **RLS is the second layer**: at the start of every transaction
  run `SET LOCAL app.user_id = :sub`, so even if the code forgets a check, the DB still returns no rows from other cases.
- `placedBy` and `actor` (Phases 1, 2) come from the JWT instead of a header.

### Metrics
`retention.purged`, `tx.serialization.retries{operation}`, `jobs.queue.lag` (gauge: `now - min(run_at)` of pending jobs),
`jobs.dead`, `jobs.fence.rejected`, `db.lock.wait` (timer, recorded in `LockingRetentionService`), `share.views.rejected`.

## 6. Steps (code + tests together)

### - [ ] 5.1 Partition `custody_event`
- **Code:** migration V9; script `perf/seed/custody_10m.sql` generates about 10 million events with `generate_series` (run
  manually on local Postgres, not part of the tests).
- **Test:** `CustodyPartitionTest` (DB): T-5.1 all Phase 2 tests stay green on the partitioned table; T-5.2 querying the chain
  of one case scans only 1 partition in `EXPLAIN` (asserted on JSON output); T-5.3 the append-only trigger still blocks.
- **Done when:** T-5.1 to T-5.3 pass, EXPLAIN ANALYZE before and after (10 million rows) is recorded in `docs/perf/partitioning.md`.

### - [ ] 5.2 Full-text search
- **Code:** migration V10, `GET /api/evidence/search?q=…&caseId=…` using `websearch_to_tsquery`, ranked by `ts_rank`, with pagination.
- **Test:** `EvidenceSearchTest` (DB): T-5.4 search by a word in the description or a tag; T-5.5 ranking order; T-5.6 deleted
  evidence is not returned; T-5.7 EXPLAIN uses the GIN index (seed about 5k rows, `ANALYZE`).
- **Done when:** T-5.4 to T-5.7 pass.

### - [ ] 5.3 Spring Security + JWT
- **Code:** `SecurityConfig` (resource server, RSA key from configuration), `DevTokenController` (`@Profile("local")`),
  401 and 403 returned as ProblemDetail.
- **Test:** `SecurityTest` (DB, MockMvc + `spring-security-test`): T-5.8 no token gives 401; T-5.9 expired token
  or invalid signature gives 401; T-5.10 `/share/{token}` and `/actuator/health` are public; T-5.11 `DevTokenController`
  does not exist outside the `local` profile.
- **Done when:** T-5.8 to T-5.11 pass, existing tests are updated to send a token.

### - [ ] 5.4 Per-case authorization + RLS
- **Code:** migration V11, `CaseAccess`, `RlsTransactionInterceptor` (runs `SET LOCAL app.user_id` at the start of the transaction).
  Add datasource configuration that uses the `evidence_app` role.
- **Test:** `CaseAccessTest` (DB): T-5.12 a non-member user gets 404 (does not reveal that the case exists); T-5.13 insufficient
  permission gives 403 (e.g. `INVESTIGATOR` cannot place a hold, only `RECORDS_ADMIN` can);
  T-5.14 **RLS**: calling the repository directly, bypassing `CaseAccess`, still shows no rows from other cases;
  T-5.15 retention (system actor) still runs through a dedicated policy for `system`.
- **Done when:** T-5.12 to T-5.15 pass.

### - [ ] 5.5 Metrics
- **Code:** register the metrics from §5 where they originate; expose `/actuator/prometheus`.
- **Test:** `MetricsTest` (DB): T-5.16 running retention increments `retention.purged` correctly; T-5.17 forcing a `40001`
  increments `tx.serialization.retries`; T-5.18 a zombie worker (Phase 3) increments `jobs.fence.rejected`; T-5.19 the
  Prometheus endpoint contains the metric names.
- **Done when:** T-5.16 to T-5.19 pass.

### - [ ] 5.6 Virtual threads: pool exhaustion and bulkhead
- **Code:** `Bulkhead` (limited by a `Semaphore` with permits less than or equal to the Hikari pool, waits with a timeout,
  on timeout returns 503 ProblemDetail with `Retry-After`), applied to heavy endpoints (search, upload complete).
- **Test:** `PoolExhaustionTest` (DB, `@SpringBootTest(properties = "spring.datasource.hikari.maximum-pool-size=5")`):
  T-5.20 **without bulkhead**: 200 concurrent requests, each holding a connection (`pg_sleep(0.2)`), produce
  `SQLTransientConnectionException` errors (Hikari timeout); T-5.21 **with bulkhead**: the same load produces no connection errors,
  requests over the limit get a fast 503, and p95 of successful requests is recorded.
- **Done when:** T-5.20 and T-5.21 pass, numbers recorded in `docs/concurrency/10-virtual-thread-bulkhead.md`.

### - [ ] 5.7 k6 load test (outside the test suite)
- **Code:** `perf/k6/cases_and_evidence.js`, `perf/k6/share_links.js`, `perf/README.md` (how to run against the local app with a token from `/api/dev/token`).
- **Test:** no JUnit. Run `k6 run …`, record p95, p99 and throughput in `docs/perf/load-test.md`.
- **Done when:** there is a table of numbers and a description of the machine configuration.

### - [ ] 5.8 Chaos with Toxiproxy
- **Code:** `support/ToxiproxyPostgres` (Testcontainers: Postgres behind Toxiproxy, on the same network).
- **Test:** `ChaosTest` (DB):
  T-5.22 adding 300 ms latency while the job queue runs 2k jobs still gives no duplicates and no loss;
  T-5.23 cutting the connection for 2 seconds while the outbox relay is publishing: after recovery every event has its effect exactly once;
  T-5.24 cutting the connection while retention is running: the legal hold invariant still holds.
- **Done when:** T-5.22 to T-5.24 pass twice in a row.

### - [ ] 5.9 Docs
- `docs/perf/partitioning.md`, `docs/perf/load-test.md`, `docs/concurrency/10-virtual-thread-bulkhead.md`,
  `docs/adr/0005-hash-partitioning.md`, `0006-jwt-without-keycloak.md`, `0007-rls-defense-in-depth.md`,
  `docs/ARCHITECTURE.md` (security, metrics), tick `docs/PLAN.md`.

## 7. Test cases
| ID | Type | Test | Scenario | Expected |
|----|------|------|----------|---------|
| T-5.1 → T-5.3 | DB | `CustodyPartitionTest` | existing tests, partition pruning, trigger | green, scans 1 partition |
| T-5.4 → T-5.7 | DB | `EvidenceSearchTest` | search, ranking, deleted, index | correct, uses GIN |
| T-5.8 → T-5.11 | DB | `SecurityTest` | 401, public, dev token | correct status codes |
| T-5.12 → T-5.15 | DB | `CaseAccessTest` | 404, 403, RLS, system | no data leak |
| T-5.16 → T-5.19 | DB | `MetricsTest` | metric counts | correct increments |
| T-5.20, T-5.21 | DB | `PoolExhaustionTest` | without and with bulkhead | pool errors, then controlled 503 |
| T-5.22 → T-5.24 | DB | `ChaosTest` | latency, network cut | no loss, no duplicates |

## 8. Docs to update
- Same as step 5.9.

## 9. Definition of Done
- [ ] `./mvnw verify` green (including Toxiproxy)
- [ ] Numbers: EXPLAIN before and after, k6 p95/p99, pool exhaustion vs bulkhead
- [ ] RLS has a test proving it is the second layer of defense

## 10. Open questions
- Where to put the RSA key for `local`? Proposal: generate it at `local` profile startup and store it in `./data/dev-keys/` (git-ignored).
- RLS requires the app to use a non-owner role. On local Postgres the `evidence_app` role must be created; migrations run as the owner
  (Flyway uses its own datasource, separate from the app). Do we want to split into two datasources?
