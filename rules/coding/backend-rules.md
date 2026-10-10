# Backend rules (Spring Boot / Java)

Severity: **MUST** = never break; **SHOULD** = default, deviate only with a reason in the PR/commit.
When unsure, read an existing feature (`casefile`, `evidence`) and mirror it.

## Structure
- **MUST** package by feature under `com.evidencevault.<feature>`: controller, service, repository, entity and
  enums live together. No `controller/`, `service/`, `repository/` layer packages.
- **MUST** cross feature boundaries only through the other feature's **service**, never its repository.
  Example: `EvidenceService` → `CaseFileService.get()`.
- **SHOULD** keep shared web plumbing in `common/web` (exceptions, `GlobalExceptionHandler`) and nothing else there.

## Controllers
- **MUST** use `record` DTOs nested in the controller (`CreateCaseRequest`, `CaseResponse`) with Bean Validation
  annotations and `@Valid` on the body.
- **MUST NOT** return entities from controllers. Map with a static `Response.from(entity)`.
- **MUST** return `201 Created` for creates (`ResponseEntity.created(...)` or `@ResponseStatus(CREATED)`).

## Services
- **MUST** put every write in a `@Transactional` service method and state the isolation level in its Javadoc
  (see `concurrency.md`). Reads use `@Transactional(readOnly = true)`.
- **MUST NOT** enforce invariants with check-then-act in Java (`existsBy…` then `save`). Use a DB constraint,
  an atomic `UPDATE … WHERE`, or an explicit lock.
- **MUST** translate expected DB failures to domain exceptions (`ConflictException`, `NotFoundException`).

## Entities
- **MUST** have a `protected` no-arg constructor, a `UUID id` assigned in Java, `Instant` timestamps,
  and a `@Version long version` on mutable aggregates.
- **MUST** match the Flyway schema exactly (Hibernate runs with `ddl-auto: validate`). For non-default column
  types, say so explicitly, e.g. `@JdbcTypeCode(SqlTypes.CHAR)` for `CHAR(n)`.

## Errors
- **MUST** return every error as `ProblemDetail` through `GlobalExceptionHandler`.
  Add a new `@ExceptionHandler` there instead of building error bodies in controllers.

## Style
- **MUST** use constructor injection only (no field `@Autowired`).
- **MUST** write code, comments and identifiers in English.
- **SHOULD** comment *why* (the race being prevented, the invariant relied on), not *what*.
