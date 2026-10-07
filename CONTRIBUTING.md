# Contributing

## Development docs

- Java code: [docs/ai-conventions/CODE_CONVENTIONS.md](docs/ai-conventions/CODE_CONVENTIONS.md)
- Tests: [docs/ai-conventions/TEST_CONVENTIONS.md](docs/ai-conventions/TEST_CONVENTIONS.md)

Run the full check from the repository root:

```bash
./gradlew build
```

Run library tests only:

```bash
./gradlew :lib:test
```

Generate coverage reports for all subprojects with tests:

```bash
./gradlew coverageReport
```

The aggregated JaCoCo XML report is written to `aggregation/build/reports/jacoco/coverage/coverage.xml`
(the same file CI uploads to Codecov).

## Conventional Commits

Commit messages must meet the [conventional commit format](https://conventionalcommits.org):

```
<type>[optional scope]: <subject>

[optional body, explain why rather than what]
```

- **subject**: imperative, short; header total length ≤ 100 characters.
- **scope**: optional; when present, use one of the table below.
- PR titles must follow the same format (validated by workflow).

### type

| type | When to use |
|------|-------------|
| `feat` | New feature or module |
| `fix` | Bug fix |
| `docs` | Documentation only |
| `style` | Formatting, no semantic change |
| `refactor` | Restructuring, no semantic change |
| `perf` | Performance related |
| `test` | Tests only |
| `build` | Build, Gradle, dependency versions |
| `ci` | CI, git hooks |
| `chore` | Miscellaneous maintenance |
| `revert` | Revert a previous commit |

### scope

| scope | Area |
|-------|------|
| `deps` | Third-party dependency bumps |
| `lib` | `lib/` ordered executor library |
| `docs` | `README.md`, `README.zh.md`, `CONTRIBUTING.md` |
| `ci` | `.githooks/`, `scripts/`, `.github/` |
| `build` | `build.gradle.kts`, `settings.gradle.kts`, Gradle wrapper |

### Examples

```
feat(lib): support multi-key ordered execution

fix(deps): update all non-major dependencies

chore: bump version to 0.0.11-SNAPSHOT
```

## Create a commit

No npm install is needed at the repository root; git hooks and release scripts are plain shell + Gradle.

## Local git hooks (shell, no npm)

Clone or update, then enable once (by a human, not by Agent):

```bash
./scripts/setup-git-hooks.sh
```

Trial run:

```bash
echo "feat(lib): test message" | ./scripts/validate-commit-msg.sh /dev/stdin
```

- `commit-msg` validates the message via `scripts/validate-commit-msg.sh`.
- `pre-commit` / `pre-push` refuse direct commits/pushes to `master`.

## Workflow validation

Commit message will be validated by workflow. If the validation is fail, amend the commit and rerun validation action.
