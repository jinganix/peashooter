---
name: review-tdd-fix
description: >-
  Audit code for bugs and improvements, prioritize findings, then fix each issue
  with test-first TDD one at a time. Use when the user asks to review or inspect
  implementation, check algorithms for bugs, cross-check with multiple workers,
  or fix issues with tests before code (test-first TDD, one issue at a time).
---

# Review → Prioritize → TDD Fix (One Issue at a Time)

## When to Use

- User asks to **review**, **audit**, or **inspect** code for bugs, optimizations, or usability
- User wants fixes done **test-first** (reproduce with a test, then fix)
- User asks to proceed **one issue at a time**
- User allows **parallel cross-check** workers for the initial audit only

## Phase 1 — Audit (read-only)

### Scope

1. Identify target paths (package, module, or directory)
2. Read all relevant source files; skim tests and README for intended behavior
3. Optional: launch **2–3 parallel explore/review workers** with distinct lenses:
   - **Correctness**: bugs, edge cases, concurrency, resource leaks
   - **Performance**: complexity, allocations, algorithm choice
   - **Usability**: API ergonomics, docs, error semantics
4. **Reconcile** worker reports: verify each claim against source before treating as real

### Deliverable: prioritized issue list

Group by severity (Critical → High → Medium → Low). Each item must include:

| Field | Content |
|-------|---------|
| Title | One-line problem statement |
| Location | File + method |
| Impact | What breaks and when |
| Suggested fix | Minimal direction, not full implementation |
| Test gap | What existing tests miss |

Present a **phased plan** (e.g. Phase 1 correctness, Phase 2 perf, Phase 3 API). **Do not start fixing** until the user confirms scope—or they explicitly said to proceed issue-by-issue.

## Phase 2 — Fix Loop (strictly sequential)

**One issue per iteration.** Never batch multiple unrelated fixes in one commit unless the user asks.

For issue *N*:

```
[ ] 1. Re-read the affected code
[ ] 2. Write a failing reproduction test
[ ] 3. Run test → confirm RED
[ ] 4. Implement minimal fix
[ ] 5. Run targeted tests → confirm GREEN
[ ] 6. Run related suite (package/module) for regressions
[ ] 7. Report: test name, root cause, fix summary
[ ] 8. Only then start issue N+1
```

### Writing reproduction tests

**Goal**: fail for the *right reason* before the fix, pass after.

| Bug type | Test technique |
|----------|----------------|
| Wrong return / state | Assert exact state after operation |
| Hang / no progress | `assertTimeout` or short timeout + expect fast failure |
| Silent swallow | Assert exception propagates or callback invoked |
| Ordering / concurrency | Two threads, latches, `awaitCountDown` |
| Regression guard | Name test `should…When…` describing the bug scenario |

**Conventions** (match project style):

- Place test in existing test class for the component under test
- Use `@DisplayName` with readable scenario description
- Given / When / Then structure
- Reuse project test utilities (`TestUtils`, mocks, providers)
- Prefer **unit** tests; integration only when unit cannot reproduce

### Running tests

```bash
# Single reproduction test (RED then GREEN)
./gradlew :<module>:test --tests "full.package.ClassTest.methodName"

# Related package after fix
./gradlew :<module>:test --tests "full.package.component.*"
```

Adapt command to project build tool (Gradle, Maven, npm, etc.).

### Fix discipline

- **Minimal diff** — fix only what the failing test demands
- **No drive-by refactors** while fixing bugs
- If fix *A* unblocks test for issue *B* (dependent bugs), finish *A*, then handle *B* with its own test
- If reproduction test is flaky, stabilize setup before fixing production code
- **Do not commit** unless the user asks

## Phase 3 — Wrap-up

After a batch of fixes:

1. Summarize completed issues in a table (issue → test → fix)
2. List remaining items from the audit not yet addressed
3. Note tests added and any intentional behavior left undocumented

## Audit Report Template

```markdown
## Summary
[1–2 sentences]

## Findings
### P0 — Critical
- **Title** (`File.method`): impact. Fix: …

### P1 — High
…

## Recommended fix order
1. …
2. …

## Test coverage gaps
- …
```

## Per-Issue Completion Template

```markdown
### Issue N: [title]
- **Test**: `ClassTest.should…`
- **RED**: [what failed — timeout, wrong assertion, etc.]
- **Fix**: [1 sentence]
- **GREEN**: [tests run]
```

## Example (abbreviated)

**Audit**: `LockableTaskQueue.run()` returns when `tryLock()` fails without clearing `current` → queue stalled.

**Test** (`shouldRunEnqueuedTaskAfterTryLockFailsThenSucceeds`):
- `tryLock` fails first call, succeeds second
- `execute(task)` → task must eventually run (`awaitCountDown`)

**RED**: 10s timeout — task never runs.

**Fix**: On lock failure with pending tasks, clear `current` and reschedule runner.

**GREEN**: all `LockableTaskQueueTest` pass.

---

## Anti-patterns

- Fixing multiple bugs before any test runs
- Writing a test that passes on broken code (vacuous assertion)
- Skipping RED verification (“I'm sure it fails”)
- Large refactor mixed with bug fix
- Treating worker audit output as fact without reading source
- Committing without user request

## Optional: parallel workers

Use **only in Phase 1**. Give each worker:
- Same path scope
- A single review lens
- Instruction to cite file + line + severity

Merge, deduplicate, then **parent agent verifies** before presenting the plan.
