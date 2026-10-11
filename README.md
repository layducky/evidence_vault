# Evidence Vault

[![CI](https://github.com/layducky/evidence_vault/actions/workflows/ci.yml/badge.svg)](https://github.com/layducky/evidence_vault/actions/workflows/ci.yml)

A backend for managing digital evidence (body-cam video, photos, documents) where **correctness under
concurrency is the product**: evidence under legal hold must never be deleted, the chain of custody must
be tamper-evident, and background jobs must never be lost or run twice.

**Stack:** Java 25 · Spring Boot 4.1 · PostgreSQL (16+ locally, 17 in tests) · Flyway · Testcontainers

## Quick start
Requires JDK 25, a PostgreSQL database named `evidence_vault`, and Docker for the tests (not for running the app).
Create the database once, then copy `application-local.properties.example` to `application-local.properties`
if your credentials differ from the defaults:
```sql
CREATE ROLE ev LOGIN PASSWORD 'ev';
CREATE DATABASE evidence_vault OWNER ev;
```
```bash
./mvnw spring-boot:run      # run the app (or press ▶ next to main() in EvidenceVaultApplication)
./mvnw verify               # tests against real Postgres via Testcontainers (needs Docker)
curl -X POST localhost:8080/api/cases -H 'Content-Type: application/json' \
     -d '{"caseNumber":"C-2026-001","title":"Burglary at 5th Ave"}'
```
Once running, a minimal UI is at http://localhost:8080 (static HTML/CSS/JS in `src/main/resources/static`):
cases, evidence, legal holds and a "Run retention" button.

On-demand test suites, excluded from the default build:
```bash
./mvnw test -Dgroups=naive -DexcludedGroups= -Dtest=RetentionStressTest     # failure rate of the naive version
./mvnw test -Dgroups=bench -DexcludedGroups= -Dtest=RetentionBenchmarkTest  # writes target/bench/retention.md
```

## Hard problems solved
| # | Problem | Status |
|---|---------|--------|
| 1 | Write skew: legal hold vs retention delete | [done](docs/concurrency/01-write-skew-legal-hold.md): naive loses frozen evidence in 75 % of stress rounds; locking fix, 0 losses |
| 2 | Hash-chained custody log under concurrent appends | planned |
| 3 | Postgres job queue: SKIP LOCKED, leases, fencing tokens | planned |
| 4 | Transactional outbox | planned |

## Repository layout
| Path | What |
|---|---|
| `src/` | Application and tests |
| `docs/` | [Architecture and usage](docs/ARCHITECTURE.md), [roadmap](docs/PLAN.md), [concurrency write-ups](docs/concurrency/), [ADRs](docs/adr/), [AI workflow](docs/AI_WORKFLOW.md) |
| `plans/` | Step-by-step implementation plan per phase (code and tests together, review findings) |
| `rules/` | Coding, database, concurrency and test conventions |
| `.claude/` | AI assistant setup: `CLAUDE.md`, skills, hooks that enforce some of the rules |
