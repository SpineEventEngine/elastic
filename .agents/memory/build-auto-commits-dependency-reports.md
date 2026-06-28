---
name: build-auto-commits-dependency-reports
description: A full `./gradlew build` regenerates and auto-commits docs/dependencies/* as a separate "Update dependency reports" commit
metadata:
  type: project
---
Running a full `./gradlew build` regenerates the license/dependency reports under
`docs/dependencies/` (via `LicenseReporter` / the dependency-report tasks) and creates
a separate **"Update dependency reports"** commit for them.

**Why:** the regenerated reports carry environment-specific churn (timestamp, locally
resolved versions), so they do not belong in an unrelated feature PR — and the surprise
commit can make a follow-up `git commit --amend` land on *it* instead of your own
commit.

**How to apply:** when preparing a feature PR, build first, then inspect `git log` /
`git status` before committing; reset or revert the `docs/dependencies/*` churn (and the
auto-commit) so the feature commit stays focused. Let the canonical release / version-
bump flow regenerate the reports.
