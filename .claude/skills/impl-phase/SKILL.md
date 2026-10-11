---
name: impl-phase
description: Implement steps of a phase plan (plans/phase-N-*.md), writing production code and its JUnit tests together, verifying each step, and ticking the plan. Only runs when the user invokes it.
argument-hint: "<phase number> [step ids, e.g. 1.3 1.4] (no ids = all unticked steps)"
disable-model-invocation: true
---

# /impl-phase — implement a plan (code + tests together)

`$ARGUMENTS` = phase number, optionally followed by step ids (`1.2 1.3`).
- **No step ids** (e.g. `/impl-phase 0`): implement **every unticked step of that phase**, in plan order.
- **With step ids**: implement only those steps.

Ticked steps are skipped. If a step needs an action only the user can take (push, run CI, record a GIF, choose
an open question), do everything around it that you can, leave it unticked with a note saying what the user must
do, and continue with the next step if it does not depend on that one. If a later step depends on it, stop there.

## Before writing code
1. Read the plan `plans/phase-<N>-*.md`, `.claude/CLAUDE.md`, and the `rules/` files the plan references.
2. Check section **0. Review findings**. If any finding has Decision `⬜ open`, **stop**: list the open findings
   (id, severity, one line each) and ask the user to decide them. Do not write code, and do not change a
   Decision yourself unless the user has just decided it in this conversation (then record their decision
   and the date).
3. Check the step's prerequisites (earlier steps ticked, migration numbers still free, classes it extends exist).
   If the code has drifted from the plan, **stop and report the conflict**. Do not improvise a new design.
4. If a step is ambiguous or the plan lists an open question that affects it, ask the user (AskUserQuestion).

## For each requested step, in order
1. Write the **test(s) listed for the step and the production code together**. Follow the names in the plan.
2. Run only the relevant tests:
   `./mvnw -B -q test -Dtest='<TestClass>[#method]' -Dsurefire.failIfNoSpecifiedTests=false`
   - Naive-implementation steps: the reproduction test MUST demonstrate the anomaly as the plan describes.
     Report the observed failure rate.
   - Fix steps: the same invariant test MUST pass. Run stress tests at least twice.
3. If a test fails for a reason other than the one the plan expects, debug the cause. Never weaken an
   assertion, add sleeps, or catch-and-ignore to make it green.
4. Tick the step's checkbox in the plan and add a one-line note (what changed, test result, failure rate).

## After the requested steps
- Run the full suite: `./mvnw -B verify` (needs Docker). Report the real result.
- If the phase is now complete, tick the matching items in `docs/PLAN.md` §3 and update
  `docs/ARCHITECTURE.md` (endpoints, write-path table, data model) if they changed.
- If the user caught an AI mistake during this run, suggest `/log-ai-mistake`. Do not log it yourself.
- Never commit or push. Summarise: steps done, tests added (class#method), results, anything left open.

## Hard rules
- Migrations: new `V<N>__*.sql` only (see `/new-migration`); the hook blocks edits to tracked ones.
- No H2 or mocks of the database in concurrency tests; use `TestcontainersConfiguration`.
- Do not implement steps that were not requested, and do not start the next phase.
