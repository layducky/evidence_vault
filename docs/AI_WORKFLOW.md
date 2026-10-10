# AI-Assisted Development Workflow

This project is built with an AI coding assistant. This document records **how** it was used
and, more importantly, **where its output was wrong** and how that was caught.

## Workflow
1. Write the failing test first (often a concurrency reproduction), by hand.
2. Ask the assistant for an implementation, with the constraints in `CLAUDE.md` in context.
3. Review every line: transaction boundaries, isolation level, locking, constraint usage, N+1 queries.
4. Run the test suite against real Postgres. Iterate.
5. Log notable mistakes below.

## What the AI got wrong (and how it was caught)

| # | Area | AI output | Problem | Caught by | Fix |
|---|------|-----------|---------|-----------|-----|
| e.g. | Case creation | `existsByCaseNumber` check before `save` | Check-then-act race: concurrent requests both pass the check | `CaseFileConcurrencyTest` | Rely on the unique constraint, map violation to 409 |
| 1 | Evidence entity mapping | `V1` declared `sha256 CHAR(64)`, entity mapped it as `@Column(length = 64) String` | Hibernate `ddl-auto: validate` expects `varchar(64)`, so the app context never starts. Generated code compiled, but the tests had never been run | First real `./mvnw verify` against Postgres (Testcontainers) | Keep the migration (never edit `V1`), add `@JdbcTypeCode(SqlTypes.CHAR)` to the field |

## What worked well
- 
