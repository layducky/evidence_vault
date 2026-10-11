# Phase 0 – Finish the foundation: Git and CI

> Status: ✅ Done · Roadmap: `docs/PLAN.md` §3 Phase 0 · Implement with `/impl-phase 0 [step]`

## 1. Goal
Every push runs `./mvnw verify` (including Testcontainers tests) on GitHub Actions. A green badge on the README
lets reviewers see right away that the project builds.

## 2. Scope
**In scope:** CI workflow, Surefire configuration for test tags (`naive`, `bench`), badge.
**Out of scope:** deploy, Docker image, release.

## 3. Prerequisites
- The repo is already `git init`-ed (done). **You** create the first commit and the GitHub repo yourself. `/impl-phase` does not commit or push.

## 4. Changes vs PLAN.md
- Add configuration that excludes the `naive` and `bench` tags from the default build. All later phases need it (see `rules/testing/TEST_STRATEGY.md`).

## 5. Design
- `.github/workflows/ci.yml`: `ubuntu-latest` (Docker preinstalled for Testcontainers), `actions/setup-java` Temurin 25,
  Maven cache, runs `./mvnw -B verify`. Uploads `target/surefire-reports` on failure.
- `pom.xml`: property `<excludedGroups>naive,bench</excludedGroups>` so Surefire skips the naive-version stress tests
  and benchmarks. Run them separately: `./mvnw test -Dgroups=bench -DexcludedGroups=`.

## 6. Steps (code + tests together)
### - [x] 0.1 Exclude the `naive`/`bench` tags from the default build
> Done 2026-10-10: `<excludedGroups>naive,bench</excludedGroups>` in `pom.xml`. With `TagConfigTest` present,
> `./mvnw verify` was green (3 tests, `excludedByDefault` not run); `-Dgroups=naive -DexcludedGroups=` ran only
> `excludedByDefault`, which failed as expected. `TagConfigTest` was then deleted (its regular test asserted nothing useful).
- **Code:** add the `excludedGroups` property to `pom.xml`.
- **Test:** `support/TagConfigTest` (Unit) with one `@Tag("naive")` test that always fails (`fail("must be excluded")`) and
  one regular test. T-0.1, T-0.2.
- **Done when:** `./mvnw verify` is green and the report shows the `naive` test did not run. Running
  `./mvnw test -Dgroups=naive -DexcludedGroups= -Dtest=TagConfigTest` shows exactly that test failing.
  Then **delete the always-failing test** (keep the regular test if needed) and record the result in a note.

### - [x] 0.2 GitHub Actions workflow
- **Code:** `.github/workflows/ci.yml` as designed. `chmod +x mvnw` via `git update-index --chmod=+x mvnw`
  (Windows does not keep the executable bit). You run this step, since it changes the git index.
- **Test:** no JUnit (configuration step). Verify with a real CI run after you push.
- **Done when:** the first run on GitHub is green.
> 2026-10-10: `.github/workflows/ci.yml` written (checkout v4, setup-java v4 Temurin 25 + Maven cache,
> `./mvnw -B verify`, upload surefire reports on failure). `mvnw` is already `100755` in the index.
> First run on GitHub green for commit `d88dd9a` (checked via the Actions API).

### - [x] 0.3 Badge and notes
- **Code:** CI badge at the top of `README.md`. In `docs/PLAN.md`, tick the git/CI item.
- **Done when:** the badge shows the status of the latest run.
> 2026-10-10: badge added for `layducky/evidence_vault`; it reflects the green run of `d88dd9a`.
> `docs/PLAN.md` git/CI item ticked.

## 7. Test cases
| ID | Type | Test | Scenario | Expected |
|----|------|------|----------|---------|
| T-0.1 | Unit | `TagConfigTest#excludedByDefault` | `@Tag("naive")`, `fail()` | Does not run in `verify` |
| T-0.2 | Unit | `TagConfigTest#runsByDefault` | regular test | Runs and passes |

## 8. Docs to update
- `README.md` (badge), `docs/PLAN.md` (tick Phase 0).

## 9. Definition of Done
- [x] CI green on GitHub
- [x] Tags `naive`, `bench` are excluded from the default build and run when specified

## 10. Open questions
- GitHub repo name, public or private? (For a portfolio, public is better, but only make it public after checking that no personal information is exposed.)
