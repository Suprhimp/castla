# Claude Code Instructions

## PR Creation Rules

When creating a PR to `master`, you MUST add exactly one version label based on the changes:

- **`major`** — Breaking changes, major new features, architecture overhaul
- **`minor`** — New features, significant enhancements, new UI screens
- **`patch`** — Bug fixes, small tweaks, translation updates, dependency bumps

Use `gh pr create --label <label>` to include the label when creating the PR.

Analyze the diff to determine the appropriate version bump:
- If the change adds new user-facing functionality → `minor`
- If the change fixes a bug or makes small adjustments → `patch`
- If the change breaks backward compatibility or is a major overhaul → `major`
- When in doubt, default to `patch`
