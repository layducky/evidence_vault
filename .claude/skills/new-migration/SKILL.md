---
name: new-migration
description: Create the next Flyway migration (V<N>__name.sql) for a schema change, following rules/coding/db.md. Use whenever a table, column, index, constraint or trigger must be added or changed.
argument-hint: "<short description, e.g. add legal_hold table>"
---

# /new-migration

1. List `src/main/resources/db/migration/` and take the highest `V<N>`. The new file is `V<N+1>__<snake_case>.sql`
   (description from `$ARGUMENTS`, lowercase, words joined by `_`).
2. **Never edit an existing migration.** The `guard-edits` hook blocks it. To change existing schema, write
   `ALTER …` in the new file.
3. Write the SQL following `rules/coding/db.md`:
   - invariants as constraints (`NOT NULL`, `UNIQUE`, FK, `CHECK`, partial unique index);
   - `UUID` PKs, `TIMESTAMPTZ`, `snake_case`, `version BIGINT NOT NULL DEFAULT 0` on mutable aggregates;
   - index every FK and every column a job scans; comment non-obvious indexes/constraints.
4. Update the JPA entity so it matches exactly (Hibernate runs with `ddl-auto: validate`). For non-default
   types, annotate explicitly (e.g. `@JdbcTypeCode(SqlTypes.CHAR)`, `SqlTypes.JSON` for `jsonb`).
5. Verify on a fresh database: run a DB test, e.g.
   `./mvnw -B -q test -Dtest=EvidenceVaultApplicationTests`. Flyway applies all migrations to the Testcontainers
   Postgres and Hibernate validates the mapping.
6. Update the data model section of `docs/ARCHITECTURE.md` if tables or columns changed.
