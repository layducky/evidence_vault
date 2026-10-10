---
name: impl-plan
description: Write or refresh the implementation plan for one roadmap phase (plans/phase-N-*.md) — code changes and their JUnit tests side by side, step by step. Planning only; never writes production or test code.
argument-hint: "<phase number 0-6> [--refresh]"
disable-model-invocation: true
---

# /impl-plan — plan one phase

Produce `plans/phase-<N>-<slug>.md` for the phase given in `$ARGUMENTS`, following `plans/_TEMPLATE.md` exactly.
This skill **only writes the plan file**. It MUST NOT create or edit anything under `src/`, `pom.xml`,
migrations or docs other than the plan itself.

## Inputs to read first
1. `docs/PLAN.md` §3: the phase's checklist is the scope. Nothing outside it unless it is a prerequisite.
2. `CLAUDE.md` and every file in `rules/`: the plan must obey them. Quote the rule when a step depends on it.
3. The current code: list `src/main/java/com/evidencevault/**`, `src/main/resources/db/migration/`, and existing
   tests. The plan must start from what exists (next migration number, existing services to extend).
4. The existing plan file, if any. With `--refresh`, keep ticked steps and their notes and re-plan only the rest.
5. Plans of earlier phases: reuse their decisions; do not contradict them silently.

## Rules for the plan
- **Code and test together.** Every step lists the production change **and** the JUnit test(s) that prove it,
  so `/impl-phase` can implement both in one go. A step without a test is only allowed for docs or config.
- Tests are JUnit only: `Unit` (plain JUnit, no Spring, no DB) or `DB` (JUnit + `TestcontainersConfiguration`,
  real Postgres). No E2E, browser or k6 tests in the test suite (k6 may be used only for benchmarks).
- Every concurrency problem gets: naive implementation → reproduction test (deterministic interleaving where
  possible, plus a stress run with `support/Concurrently`) → fix(es) → same test passing → doc in
  `docs/concurrency/NN-*.md` from `_TEMPLATE.md`.
- Every write path states its transaction boundary and isolation level.
- Name concrete things: file paths, class and method names, SQL, endpoint + status codes, test class#method.
- Number steps `N.1, N.2, …` and test cases `T-N.x`. Each step ends with a **Done when** line that can be checked.
- Fit the current machine/project: app runs locally on native PostgreSQL 16 without Docker; tests use Docker
  (Testcontainers). Prefer no new infrastructure; if one is unavoidable, isolate it behind an interface and
  list it under "Dependencies to add".
- If the roadmap item is wrong or imprecise (e.g. a fix that cannot work), say so in "Changes vs PLAN.md" with
  the reason, instead of planning something incorrect.
- Unknowns go to "Open questions". Do not guess silently.

## Output
Write the file, then reply with: the path, the number of steps and test cases, and the open questions.
Do not start implementing; tell the user to run `/impl-phase <N>` when ready.
