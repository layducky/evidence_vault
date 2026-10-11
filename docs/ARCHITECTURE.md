# Architecture and usage

How Evidence Vault is wired, how a request flows through it, and how to use it locally.
Roadmap and planned modules: [`PLAN.md`](PLAN.md). Concurrency write-ups: [`concurrency/`](concurrency/).

## 1. System overview

One Spring Boot process (a modular monolith) serves both the REST API and a minimal static UI,
and talks to a single PostgreSQL database.

```mermaid
flowchart LR
    subgraph Clients
        UI["Browser UI<br/>static/index.html + app.js"]
        CURL["curl / any REST client"]
    end

    subgraph App["Spring Boot 4.1 app · Java 25 · port 8080"]
        direction TB
        TC["Embedded Tomcat<br/>virtual threads"]
        STATIC["Static resources<br/>/ , /app.js, /style.css"]
        ACT["Actuator<br/>/actuator/health"]
        subgraph Features["Feature packages"]
            CASE["casefile<br/>Controller → Service → Repository"]
            EVID["evidence<br/>Controller → Service → Repository"]
            RET["retention<br/>legal holds · retention strategies<br/>RetentionJob (@Scheduled)"]
        end
        GEH["GlobalExceptionHandler<br/>→ ProblemDetail (RFC 9457)"]
        JPA["Spring Data JPA · Hibernate 7"]
        POOL["HikariCP<br/>max 20 connections"]
        FLY["Flyway<br/>runs on startup"]
    end

    DB[("PostgreSQL<br/>evidence_vault<br/>case_file, evidence, legal_hold")]
    CFG["application.yml<br/>+ application-local.properties<br/>+ env vars"]

    UI -- "fetch JSON" --> TC
    CURL --> TC
    TC --> STATIC
    TC --> ACT
    TC --> CASE
    TC --> EVID
    TC --> RET
    RET -- "CaseFileService.get()" --> CASE
    RET -- "SQL with explicit locks" --> POOL
    EVID -- "CaseFileService.get()" --> CASE
    CASE -. exceptions .-> GEH
    EVID -. exceptions .-> GEH
    CASE --> JPA
    EVID --> JPA
    JPA --> POOL
    POOL -- "JDBC :5432" --> DB
    FLY -- "V1, V2 migrations" --> DB
    CFG -. "DB_URL, DB_USERNAME, DB_PASSWORD" .-> POOL
```

**Key points**
- **Package by feature.** Each feature (`casefile`, `evidence`, and later `retention`, `custody`, `jobs`, …) owns
  its controller, service, repository and entity. A feature calls another feature only through its service,
  e.g. `EvidenceService` → `CaseFileService.get()`. One documented exception: `retention` runs SQL directly on
  `evidence` and `case_file`, because lock modes and lock order are what that feature is about.
- **Schema is owned by Flyway.** Hibernate only validates it (`ddl-auto: validate`), so entity and SQL
  mismatches fail fast at startup.
- **Invariants live in the database.** Uniqueness, foreign keys and `CHECK` constraints are the source of
  truth. Java never does check-then-act for invariants.
- **One error format.** Every error, including Spring MVC's own (validation, malformed JSON, 405), is returned
  as `application/problem+json`.

## 2. Request lifecycle

Example: `POST /api/cases`, including the concurrent-duplicate case.

```mermaid
sequenceDiagram
    autonumber
    participant C as Client (UI / curl)
    participant T as Tomcat (virtual thread)
    participant Ctl as CaseFileController
    participant S as CaseFileService
    participant R as CaseFileRepository
    participant DB as PostgreSQL
    participant E as GlobalExceptionHandler

    C->>T: POST /api/cases {caseNumber, title}
    T->>Ctl: deserialize + @Valid
    alt validation fails
        Ctl-->>E: MethodArgumentNotValidException
        E-->>C: 400 ProblemDetail "caseNumber must not be blank"
    end
    Ctl->>S: create(caseNumber, title)
    Note over S,DB: @Transactional, READ COMMITTED
    S->>R: saveAndFlush(new CaseFile)
    R->>DB: INSERT INTO case_file …
    alt unique(case_number) violated
        DB-->>R: 23505 unique_violation
        R-->>S: DataIntegrityViolationException
        S-->>E: ConflictException
        E-->>C: 409 ProblemDetail "Case number already exists"
    else inserted
        DB-->>R: OK
        S-->>Ctl: CaseFile
        Ctl-->>C: 201 Created + Location: /api/cases/{id}
    end
```

There is no `existsByCaseNumber()` pre-check. With 20 concurrent requests for the same case number,
the unique constraint lets exactly one through and the other 19 get 409. `CaseFileConcurrencyTest` proves this.

## 3. Data model

```mermaid
erDiagram
    case_file ||--o{ evidence : "has"
    case_file ||--o{ legal_hold : "frozen by"
    legal_hold {
        uuid id PK
        uuid case_id FK
        varchar reason
        varchar placed_by
        timestamptz placed_at
        timestamptz released_at "null = active"
        bigint version
    }
    case_file {
        uuid id PK
        varchar case_number UK
        varchar title
        varchar status "OPEN, ..."
        bigint version "optimistic lock"
        timestamptz created_at
    }
    evidence {
        uuid id PK
        uuid case_id FK
        varchar file_name
        varchar content_type
        bigint size_bytes "CHECK >= 0"
        char sha256 "set after upload"
        varchar status "PENDING_UPLOAD, PROCESSING, AVAILABLE, ..."
        boolean legal_hold
        timestamptz retain_until
        timestamptz deleted_at "soft delete"
        bigint version "optimistic lock"
        timestamptz created_at
    }
```

- `idx_evidence_retention` is a partial index on `retain_until WHERE deleted_at IS NULL AND legal_hold = FALSE`,
  used by the retention purge.
- `legal_hold` (V2) is a case-level hold, active while `released_at IS NULL` (partial index
  `idx_legal_hold_active_case`). It is a separate table on purpose; see [`concurrency/01`](concurrency/01-write-skew-legal-hold.md).
- `evidence.legal_hold` is the evidence-level hold. `chk_evidence_not_held_and_deleted` makes "held and deleted"
  impossible at the database level.
- `version` columns back JPA optimistic locking. A lost update surfaces as
  `ObjectOptimisticLockingFailureException`, which is returned as 409.

## 4. Write paths: transaction boundaries

| Endpoint | Service method | Transaction | Isolation | Invariant enforced by |
|---|---|---|---|---|
| `POST /api/cases` | `CaseFileService.create` | `@Transactional` | READ COMMITTED (Postgres default) | `UNIQUE (case_number)` |
| `POST /api/cases/{caseId}/evidence` | `EvidenceService.register` | `@Transactional` | READ COMMITTED | `FOREIGN KEY (case_id)`, `CHECK (size_bytes >= 0)` |
| `POST /api/cases/{caseId}/legal-holds` | `LegalHoldService.placeCaseHold` | `SerializableTx` (retry 40001, max 5) | SERIALIZABLE | case `FOR NO KEY UPDATE` → evidence `FOR SHARE ORDER BY id` → insert hold |
| `DELETE /api/cases/{caseId}/legal-holds/{holdId}` | `LegalHoldService.releaseCaseHold` | `@Transactional` | READ COMMITTED | atomic `UPDATE … WHERE released_at IS NULL` (0 rows → 404/409) |
| `POST/DELETE /api/evidence/{id}/legal-hold` | `LegalHoldService.holdEvidence` / `releaseEvidence` | `@Transactional` | READ COMMITTED | atomic `UPDATE … WHERE deleted_at IS NULL` + `chk_evidence_not_held_and_deleted` |
| `POST /api/admin/retention/run`, `RetentionJob` | `RetentionStrategy.purgeExpired` (configured: `locking`) | one transaction per batch | READ COMMITTED | case `FOR SHARE ORDER BY id` → evidence `FOR UPDATE SKIP LOCKED` → re-read holds → delete (`ORDER BY id FOR UPDATE`) |

Lock order across these paths is always **case, then evidence by id** ([ADR 0001](adr/0001-retention-strategy.md)).
The other strategies (`naive`, `serializable`, `single-statement`) exist for comparison; `naive` and
`single-statement` are knowingly wrong.

Read endpoints use `@Transactional(readOnly = true)`. `open-in-view` is disabled, so no lazy loading happens
outside a service transaction.

## 5. Configuration

Values are resolved in this order (first wins):

```mermaid
flowchart LR
    ENV["Environment variables<br/>DB_URL, DB_USERNAME, DB_PASSWORD<br/>(CI / prod)"] --> LOCAL
    LOCAL["application-local.properties<br/>(project root, git-ignored)"] --> YML
    YML["application.yml defaults<br/>localhost:5432/evidence_vault, ev/ev"]
```

| File | Committed | Purpose |
|---|---|---|
| `src/main/resources/application.yml` | yes | All settings, with `${DB_…:default}` placeholders |
| `application-local.properties` | no | Your machine's DB credentials |
| `application-local.properties.example` | yes | Template for the above |

## 6. Tests

```mermaid
flowchart LR
    MVN["./mvnw verify"] --> JUNIT["JUnit 6 + Spring Boot Test"]
    JUNIT --> TCC["TestcontainersConfiguration<br/>@ServiceConnection"]
    TCC -- "starts" --> PG[("postgres:17-alpine<br/>throwaway container")]
    JUNIT --> CONC["support/Concurrently<br/>fires N tasks at once"]
    JUNIT --> LW["support/LockWaits<br/>waits until a session blocks on a lock"]
```

Tests never touch your local database. Each run gets a fresh Postgres in Docker, and Flyway migrates it.
Docker must be running for tests, but not for running the app.

Tests tagged `naive` (stress runs of the deliberately wrong versions) and `bench` (benchmarks) are excluded
from the default build and CI. Run them with `-Dgroups=naive -DexcludedGroups=` or `-Dgroups=bench -DexcludedGroups=`.

## 7. How to use

### First-time setup
1. Install JDK 25 and PostgreSQL. Docker is needed only for the tests.
2. Create the database and a login:
   ```sql
   CREATE ROLE ev LOGIN PASSWORD 'ev';
   CREATE DATABASE evidence_vault OWNER ev;
   ```
3. If your credentials differ, copy `application-local.properties.example` to `application-local.properties`
   and edit it.

### Run
- **IntelliJ:** press ▶ next to `main()` in `EvidenceVaultApplication`; right-click `src/test/java` → *Run All Tests* for the suite.
- **Terminal:** `./mvnw spring-boot:run`, and `./mvnw verify` for the tests.

Flyway creates or updates the tables on startup. Restart the app after changing Java code or files in `static/`.

### Use the UI: http://localhost:8080
1. The header badge shows `API UP` once the backend is healthy.
2. **New case:** enter a case number and a title. The case opens automatically.
3. **Register evidence:** file name, content type, size and an optional retain-until date, which must be in
   the future. The table shows each item's status (`PENDING_UPLOAD`), legal hold and retention date.
4. **Legal holds:** on an open case, *Place legal hold* (reason, placed by) freezes its evidence; active holds are
   listed with a *Release* button. Each evidence row has a *Hold* / *Release hold* button (evidence-level hold).
5. **Retention:** *Run retention* runs one batch with the configured strategy and reports how many evidence were
   soft-deleted. The API only accepts a future `retainUntil`, so freshly registered evidence is never expired yet.
6. **Cases:** paste a case ID, or click one of the 10 recently opened cases. This list is stored in the browser.
7. Errors appear bottom-right with the server's `ProblemDetail` message.
   Creating the same case number twice shows the 409 conflict.

### Use the API directly
With curl or any REST client:

```bash
# create a case
curl -i -X POST localhost:8080/api/cases -H 'Content-Type: application/json' \
     -d '{"caseNumber":"C-2026-001","title":"Burglary at 5th Ave"}'

# register evidence (use the id from the response above)
curl -X POST localhost:8080/api/cases/{caseId}/evidence -H 'Content-Type: application/json' \
     -d '{"fileName":"bodycam-0001.mp4","contentType":"video/mp4","sizeBytes":104857600,"retainUntil":"2030-01-01T00:00:00Z"}'

# list evidence
curl localhost:8080/api/cases/{caseId}/evidence

# place a case-level legal hold (returns frozenEvidenceIds), list, release
curl -X POST localhost:8080/api/cases/{caseId}/legal-holds -H 'Content-Type: application/json' \
     -d '{"reason":"Litigation 2026-17","placedBy":"counsel@example.com"}'
curl localhost:8080/api/cases/{caseId}/legal-holds
curl -X DELETE localhost:8080/api/cases/{caseId}/legal-holds/{holdId}

# hold / release one evidence
curl -X POST   localhost:8080/api/evidence/{evidenceId}/legal-hold
curl -X DELETE localhost:8080/api/evidence/{evidenceId}/legal-hold

# run one retention batch with the configured strategy
curl -X POST localhost:8080/api/admin/retention/run
```

| Method | Path | Success | Errors |
|---|---|---|---|
| GET | `/actuator/health` | 200 `{"status":"UP"}` | 503 if DB is down |
| POST | `/api/cases` | 201 + `Location` | 400 validation, 409 duplicate `caseNumber` |
| GET | `/api/cases/{id}` | 200 | 404 |
| POST | `/api/cases/{caseId}/evidence` | 201 | 400 validation, 404 unknown case |
| GET | `/api/cases/{caseId}/evidence` | 200 (excludes soft-deleted) | 404 unknown case |
| POST | `/api/cases/{caseId}/legal-holds` `{reason, placedBy}` | 201 + `frozenEvidenceIds` | 400, 404 |
| GET | `/api/cases/{caseId}/legal-holds` | 200 active holds | 404 |
| DELETE | `/api/cases/{caseId}/legal-holds/{holdId}` | 204 | 404, 409 already released |
| POST / DELETE | `/api/evidence/{id}/legal-hold` | 204 | 404, 409 evidence deleted |
| POST | `/api/admin/retention/run` | 200 `{strategy, deleted}` (one batch) | – |

Error body example:
```json
{ "title": "Conflict", "status": 409, "detail": "Case number already exists: C-2026-001", "instance": "/api/cases" }
```

## 8. Where this is going

Phase 1 (`retention`) is done: the legal-hold vs retention-delete write skew, written up in
[`concurrency/01`](concurrency/01-write-skew-legal-hold.md). The empty packages (`custody`, `jobs`, `outbox`,
`idempotency`, `upload`, `sharing`) each hold a `package-info.java` describing the concurrency problem they will
solve; see `PLAN.md` §3.
