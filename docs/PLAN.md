# Evidence Vault – Implementation Plan

> Goal: a Java "mini Evidence.com" backend to apply for **Axon Graduate Software Engineer 2026**.
> Focus: **concurrency, databases, correctness under load**, and a clearly documented **AI-assisted development process**.

## 1. Guiding principles

Every hard problem must have all 5 parts (written in `docs/concurrency/NN-*.md`, following `_TEMPLATE.md`):

1. **Problem**: which business flow, which anomaly (lost update, write skew, double processing...).
2. **Naive version**: the incorrect code is kept (e.g. `NaiveRetentionService`).
3. **Reproduction test**: runs on real Postgres (Testcontainers) using `support/Concurrently`. The naive version **must fail**.
4. **Fix**: the test **must pass**. If there are several fixes, compare all of them.
5. **Numbers and trade-offs**: throughput, p95 latency, retry rate, and the reason for the final choice.

Other rules:
- No H2. Every DB-related test runs on Postgres 17 via Testcontainers.
- DB constraints are the source of truth. No check-then-act in the Java layer.
- Every schema change goes through Flyway; never edit a merged migration.
- Every major architecture decision has an ADR in `docs/adr/`.
- All AI-generated code is reviewed. AI mistakes you catch are logged in `docs/AI_WORKFLOW.md`.

## 2. Architecture and package structure

Modular monolith, packaged by feature:

```
com.evidencevault
├── common/web     # error handling (ProblemDetail), shared exceptions
├── casefile       # Case (case file)                     [done]
├── evidence       # Evidence metadata                    [done]
├── retention      # Retention + legal hold               Phase 1
├── custody        # Chain of custody (hash chain)        Phase 2
├── jobs           # Job queue SKIP LOCKED, lease, fencing Phase 3
├── outbox         # Transactional outbox + relay         Phase 3
├── idempotency    # Idempotency-Key                      Phase 4
├── upload         # Chunked upload, MinIO, SHA-256       Phase 4
└── sharing        # Share links with a view limit        Phase 4
```

Stack: Java 25 (virtual threads), Spring Boot 4.1, Spring Data JPA (Hibernate 7), PostgreSQL 17, Flyway, Testcontainers.
Added over time: Spring Security + OAuth2 (Keycloak), Redis, MinIO, Micrometer/Prometheus/Grafana, k6, GitHub Actions.

## 3. Roadmap

> The detailed plan for each phase (code and tests together, with a list of test cases) lives in `plans/phase-N-*.md`.
> The "Changes vs PLAN.md" section in each plan records where it differs from the roadmap below and why
> (e.g. Phase 1: case-level hold to get a real write skew; Fix C does not work under READ COMMITTED).
> Implement with `/impl-phase N`, only when you invoke it.

### Phase 0 – Foundation ✅ (set up)
- [x] Spring Boot 4.1.1 + Java 25, Maven wrapper
- [x] Runs locally against a PostgreSQL installed on the machine, configured in `application-local.properties` (git-ignored)
- [x] Flyway `V1`: `case_file`, `evidence` (with `version`, `legal_hold`, `retain_until`, partial index for retention)
- [x] API: `POST/GET /api/cases`, `POST/GET /api/cases/{id}/evidence`
- [x] `GlobalExceptionHandler` returns RFC 9457 ProblemDetail, maps optimistic lock failures to 409
- [x] `support/Concurrently`: fires N tasks at once via a latch
- [x] Sample test: 20 requests creating the same `caseNumber` give exactly 1 success and 19 get 409
- [ ] `git init`, push to GitHub, GitHub Actions runs `./mvnw verify`

### Phase 1 – Write skew: Legal hold and Retention ⭐ (week 1)
- [ ] API `POST /api/evidence/{id}/legal-hold`, `DELETE .../legal-hold`
- [ ] `RetentionJob` (`@Scheduled`) soft-deletes expired evidence in batches
- [ ] `NaiveRetentionService`: read, then check, then delete at `READ COMMITTED`, **can delete evidence that is on hold**
- [ ] Reproduction test: run "place hold" and "run retention" in parallel N times, then assert that no evidence is both on hold and deleted (the naive version fails)
- [ ] Fix A: `SELECT ... FOR UPDATE` (pessimistic)
- [ ] Fix B: `@Transactional(isolation = SERIALIZABLE)` + retry on `40001` (Spring Retry)
- [ ] Fix C: a single atomic `UPDATE ... WHERE legal_hold = false AND retain_until < now()`
- [ ] Benchmark A/B/C, write `docs/concurrency/01-write-skew-legal-hold.md`

### Phase 2 – Chain of custody with a hash chain (week 2)
- [ ] `V2`: `custody_event(case_id, seq, actor, action, payload jsonb, prev_hash, hash, created_at)`, `UNIQUE(case_id, seq)`
- [ ] Every operation on a case/evidence writes a custody event (`hash = sha256(prev_hash || canonical(payload))`)
- [ ] The naive version reads `last_hash` then inserts, which forks the chain. A test with 1000 parallel appends across 10 cases must detect this
- [ ] Fix A: `pg_advisory_xact_lock(hashtext(case_id))`
- [ ] Fix B: rely on `UNIQUE(case_id, seq)` and retry on conflict
- [ ] `GET /api/cases/{id}/custody/verify` walks the whole chain and reports where it was tampered with
- [ ] Tampering test: editing a row directly with SQL must be detected by verify
- [ ] Trigger blocks `UPDATE/DELETE` on the `custody_event` table (append-only at the DB level)
- [ ] `docs/concurrency/02-hash-chain.md`

### Phase 3 – Job queue and Outbox (week 3)
- [ ] `V3`: `job(id, type, payload, status, attempts, run_at, locked_by, lease_until, fence)`, `outbox_event(...)`
- [ ] Worker pool claims jobs with `FOR UPDATE SKIP LOCKED LIMIT n`
- [ ] Lease: when a job passes `lease_until`, another worker takes it over. A **fencing token** stops a "zombie" worker (after a GC pause) from overwriting the result
- [ ] Retry with exponential backoff + jitter; after `max_attempts` the job moves to `DEAD`
- [ ] Outbox: create evidence and write the `EvidenceRegistered` event in the **same transaction**. The relay reads in `(aggregate_id, seq)` order
- [ ] Idempotent handlers (`processed_event` table), since delivery is only at-least-once
- [ ] Test: 8 workers process 10k jobs with no job run twice and no job lost. Killing a worker midway still gets the job done
- [ ] Test a crash between commit and publish: the event is not lost
- [ ] `docs/concurrency/03-job-queue.md`, `04-outbox.md`

### Phase 4 – Idempotency, Upload, Sharing (week 4)
- [ ] `Idempotency-Key`: `INSERT ... ON CONFLICT DO NOTHING` with status `IN_PROGRESS`. A duplicate request still in progress gets 409. A duplicate request already finished gets the stored response. Same key with a different body (compared by hash) gets 422
- [ ] Chunked upload to MinIO (S3 multipart, presigned URL). The finalize step runs **exactly once** (`UPDATE ... SET status='FINALIZING' WHERE status='UPLOADING'`)
- [ ] Dedup by SHA-256 with a unique constraint, not check-then-insert
- [ ] Share link: `UPDATE ... SET remaining = remaining - 1 WHERE remaining > 0 AND expires_at > now() RETURNING`
- [ ] Optimistic locking for metadata via `ETag`/`If-Match`; a stale version gets 412
- [ ] Move evidence between two cases: lock in id order. Reproduce the deadlock in a test before fixing
- [ ] `docs/concurrency/05-*.md` … `08-*.md`

### Phase 5 – Performance, observability, security (week 5)
- [ ] Partition `custody_event` by month. Seed about 10 million rows, put `EXPLAIN ANALYZE` before and after in the README
- [ ] Full-text search (`tsvector` + GIN) on evidence description and tags
- [ ] Spring Security + Keycloak (JWT). Per-case authorization (try Postgres Row-Level Security)
- [ ] Micrometer + Prometheus + Grafana: retry count, queue latency, dead job count, lock wait time
- [ ] Virtual threads: demonstrate pool exhaustion (10k requests, 20 connections), then add a `Semaphore` bulkhead
- [ ] k6 load test, record p95/p99 and throughput
- [ ] Chaos: Toxiproxy slows down or drops the DB connection; verify no data is lost or duplicated

### Phase 6 – Packaging for the application
- [ ] README: architecture diagram, run instructions, demo GIF, benchmark table, a "Hard problems solved" section linking to each `docs/concurrency/*.md`
- [ ] Complete `docs/AI_WORKFLOW.md`, with at least 5 examples of AI mistakes you caught
- [ ] CV: 3 bullets with numbers (e.g. "zero lost/duplicate jobs across 10k jobs with 8 workers under worker crashes")
- [ ] Prepare 2 STAR stories: write skew (Phase 1) and fencing token (Phase 3)

## 4. Definition of "done" for each phase
- `./mvnw verify` green on CI
- A test that proves the bug (naive version) and a test that proves the fix
- A doc in `docs/concurrency/`, with numbers
- At least one new entry in `AI_WORKFLOW.md`

## 5. Running the project
```bash
./mvnw spring-boot:run        # app at http://localhost:8080, DB configured in application-local.properties
./mvnw verify                 # tests (needs Docker for Testcontainers)
```
Or run `TestEvidenceVaultApplication` in the IDE: the app starts Postgres itself via Testcontainers.
