# Phase N – <Phase name>

> Status: ⬜ Not started · Roadmap: `docs/PLAN.md` §3 · Implement with `/impl-phase N [step]`

## 1. Goal
One short paragraph: what this phase proves (the story to tell in an interview).

## 2. Scope
**In scope:** …
**Out of scope:** …

## 3. Prerequisites
- Earlier phases and steps are done; required tools.

## 4. Changes vs PLAN.md
- (If any) which item changed, and why.

## 5. Design
### Schema (migration `V<N>__…`)
### API
| Method | Path | Success | Errors |
### New / changed classes
| Class | Package | Role | Transaction / isolation |

## 6. Steps (code + tests together)
### - [ ] N.1 <Step name>
- **Code:** …
- **Test:** `ClassTest#method` (Unit | DB) — T-N.x
- **Done when:** …

## 7. Test cases
| ID | Type | Test | Scenario | Expected |
|----|------|------|----------|---------|

## 8. Docs to update
- `docs/concurrency/NN-*.md`, `docs/ARCHITECTURE.md`, `docs/PLAN.md` (tick), ADR if any

## 9. Definition of Done
- [ ] `./mvnw verify` green
- [ ] …

## 10. Open questions
- …
