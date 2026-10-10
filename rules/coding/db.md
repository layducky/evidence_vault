# Database rules (PostgreSQL / Flyway)

Use the `/new-migration` skill to add a migration.

## Migrations
- **MUST** change the schema only through a new file `src/main/resources/db/migration/V<N>__<snake_case>.sql`,
  where `N` = highest existing version + 1.
- **MUST NOT** edit a migration once it is committed or staged. A `PreToolUse` hook blocks this.
  Fix mistakes with a new migration.
- **MUST** keep migrations forward-only and runnable on an empty database and on the current schema.
- **SHOULD** put one logical change in each migration and comment any non-obvious index or constraint.

## Schema design
- **MUST** encode invariants in the schema whenever possible: `NOT NULL`, `UNIQUE`, `FOREIGN KEY`, `CHECK`,
  partial unique indexes. The database is the source of truth, Java is not.
- **MUST** use `UUID` primary keys, `TIMESTAMPTZ` for time, and `snake_case` for tables and columns.
- **MUST** add `version BIGINT NOT NULL DEFAULT 0` to tables backing mutable aggregates (optimistic locking).
- **SHOULD** index every foreign key and every column a background job scans. Use partial indexes for
  "live rows only" scans (see `idx_evidence_retention`).
- **SHOULD** soft-delete evidence (`deleted_at`) instead of removing rows.

## Queries
- **MUST** write invariant-sensitive updates as a single atomic statement, e.g.
  `UPDATE … SET … WHERE id = :id AND legal_hold = false`, and check the affected row count.
- **SHOULD** use `FOR UPDATE SKIP LOCKED` for work queues and `pg_advisory_xact_lock` only when no row exists to lock.
