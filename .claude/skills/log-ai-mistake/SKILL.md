---
name: log-ai-mistake
description: Append a verified AI mistake to docs/AI_WORKFLOW.md. Only for mistakes that actually happened and were caught, with evidence.
argument-hint: "<what the AI got wrong, in a few words>"
disable-model-invocation: true
---

# /log-ai-mistake

`docs/AI_WORKFLOW.md` is portfolio evidence. A fabricated or vague entry destroys its value.

1. Collect the facts from this conversation and the code. Ask the user for anything missing:
   - **Area**: feature or file.
   - **AI output**: what was generated (class/method, or a short quote).
   - **Problem**: why it was wrong, concretely (which invariant, race, mapping or rule it broke).
   - **Caught by**: the test, hook, review or error message that exposed it. It must name something real.
   - **Fix**: what was changed, with file or test name.
2. If there is no concrete evidence (no failing test, error or review note), **do not write the entry**.
   Tell the user what evidence is missing.
3. Append one row to the "What the AI got wrong" table with the next number. Keep each cell to one or two
   sentences. Do not edit earlier rows. Remove the placeholder `e.g.` row only once real rows exist.
4. If the mistake belongs to a concurrency problem, also add it to the "AI notes" section of that
   `docs/concurrency/NN-*.md`.
5. Show the user the added row.
