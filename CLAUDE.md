# Claude Code Instructions

## PR Creation Rules

When creating a PR to `master`, you MUST add exactly one label based on the changes:

- **`major`** — Breaking changes, major new features, architecture overhaul
- **`minor`** — New features, significant enhancements, new UI screens
- **`patch`** — Bug fixes, small tweaks, translation updates, dependency bumps
- **`chore`** — CI/workflow changes, docs-only edits, relicensing, repo housekeeping (no release is cut)

Use `gh pr create --label <label>` to include the label when creating the PR.

Analyze the diff to determine the appropriate label:
- If the change adds new user-facing functionality → `minor`
- If the change fixes a bug or makes small adjustments → `patch`
- If the change breaks backward compatibility or is a major overhaul → `major`
- If the change does not affect the shipped APK (CI, README, LICENSE, `.github/`, `docs/`) → `chore`
- When in doubt, default to `patch`

## Development Approach

Follow **TDD (Test-Driven Development)** when writing code:

1. **Write tests first** — before implementing new logic, add unit tests that define expected behavior
2. **Make tests pass** — implement the minimum code to satisfy the tests
3. **Refactor** — clean up while keeping tests green

Place testable logic in pure utility classes (e.g., `StreamMath`) rather than embedding it in Android components. Unit tests go in `app/src/test/` using JUnit. Run tests with `./gradlew :app:testDebugUnitTest`.
