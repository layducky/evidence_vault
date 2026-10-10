# Evidence Vault

[![CI](https://github.com/layducky/evidence_vault/actions/workflows/ci.yml/badge.svg)](https://github.com/layducky/evidence_vault/actions/workflows/ci.yml)

A backend for managing digital evidence (body-cam video, photos, documents) where **correctness under
concurrency is the product**: evidence under legal hold must never be deleted, the chain of custody must
be tamper-evident, and background jobs must never be lost or run twice.

**Stack:** Java 25 · Spring Boot 4.1 · PostgreSQL 17 · Flyway · Testcontainers

## Quick start
Requires JDK 25, a PostgreSQL database named `evidence_vault`, and Docker for the tests.
Create the database once, then copy `application-local.properties.example` to `application-local.properties`
if your credentials differ from the defaults:
```sql
CREATE ROLE ev LOGIN PASSWORD 'ev';
CREATE DATABASE evidence_vault OWNER ev;
```
Once running, a minimal UI is at http://localhost:8080 (static HTML/CSS/JS in `src/main/resources/static`),
and `http/api.http` has ready-made API requests for IntelliJ's HTTP client.

In IntelliJ IDEA, the shared run configurations in `.run/` appear in the toolbar: **Evidence Vault** (app)
and **All tests**. From a terminal:
```bash
./mvnw spring-boot:run      # run the app
./mvnw verify               # tests against real Postgres via Testcontainers (needs Docker)
curl -X POST localhost:8080/api/cases -H 'Content-Type: application/json' \
     -d '{"caseNumber":"C-2026-001","title":"Burglary at 5th Ave"}'
```

## Hard problems solved
| # | Problem | Status |
|---|---------|--------|
| 1 | Write skew: legal hold vs retention delete | planned |
| 2 | Hash-chained custody log under concurrent appends | planned |
| 3 | Postgres job queue: SKIP LOCKED, leases, fencing tokens | planned |
| 4 | Transactional outbox | planned |

See [`docs/ARCHITECTURE.md`](docs/ARCHITECTURE.md) for diagrams and a usage guide, [`docs/PLAN.md`](docs/PLAN.md) for the roadmap and [`docs/AI_WORKFLOW.md`](docs/AI_WORKFLOW.md)
for how AI-assisted development is used and reviewed.
