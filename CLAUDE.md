# Evidence Vault

Spring Boot 4.1 / Java 25 backend for managing digital evidence. Focus: correctness under concurrency.
Roadmap: `docs/PLAN.md`. Per-phase implementation plans: `plans/`. Architecture: `docs/ARCHITECTURE.md`.

## Read the rules before changing code
`rules/` is the source of truth for conventions. It is more specific than this file. Read the relevant one first:

| Area | Rule file |
|---|---|
| Java structure, controllers, services, entities, errors | `rules/coding/backend-rules.md` |
| Schema, Flyway migrations, SQL | `rules/coding/db.md` |
| Transactions, isolation, locks, retries | `rules/coding/concurrency.md` |
| Tests (JUnit only: Unit + DB/Testcontainers) | `rules/testing/TEST_STRATEGY.md` |

Rules use **MUST / MUST NOT / SHOULD**. When in doubt, mirror an existing feature (`casefile`, `evidence`).

## Commands
- `./mvnw spring-boot:run` – run the app against the DB in `application-local.properties` (git-ignored; defaults in `application.yml`)
- `./mvnw verify` – build and all tests (Testcontainers, needs Docker)
- `./mvnw -B -q test -Dtest='Class#method' -Dsurefire.failIfNoSpecifiedTests=false` – one test

## Workflow (skills)
- `/impl-plan <phase>` – write or refresh `plans/phase-N-*.md`. User-invoked only.
- `/impl-phase <phase> [steps]` – implement plan steps, code and tests together; no step ids = all unticked steps of the phase. User-invoked only.
  Never implement a plan unless the user runs this.
- `/new-migration <desc>` – next `V<N>__*.sql`.
- `/log-ai-mistake` – add a verified entry to `docs/AI_WORKFLOW.md`. User-invoked only.

## Core rules (summary)
- Package by feature (`casefile`, `evidence`, `custody`, ...), not by layer.
- Never check-then-act in Java for invariants. Enforce them with DB constraints, atomic `UPDATE ... WHERE`, or explicit locks.
- State the transaction boundary and isolation level for every write path. Retry `40001` (serialization failure) only where SERIALIZABLE is used.
- Schema changes go through new Flyway migrations. Never edit an existing migration (enforced by hook).
- Tests touching the DB use real Postgres via `TestcontainersConfiguration`, never H2 (enforced by hook).
- Concurrency fixes ship with a reproduction test using `support/Concurrently`, plus a doc in `docs/concurrency/`.
- Errors are returned as `ProblemDetail` via `GlobalExceptionHandler`.

## Hooks (`.claude/settings.json`)
- `guard-edits.sh` (PreToolUse): blocks editing tracked migrations and adding H2.
- `track-changes.sh` (PostToolUse) + `compile-check.sh` (Stop): runs `./mvnw test-compile` when Java, pom or
  resources changed, and blocks finishing on a compile error. `EV_HOOK_VERIFY=1` runs `verify` instead.
- Machine-specific settings (`JAVA_HOME`) go in `.claude/settings.local.json` (git-ignored).
