---
name: build-regenerates-dependency-reports
description: A full `./gradlew build` regenerates docs/dependencies/* in the working tree; the changes are NOT auto-committed — commit them manually
metadata:
  type: project
---
Running a full `./gradlew build` regenerates the license/dependency reports under
`docs/dependencies/` (via `LicenseReporter` / the dependency-report tasks). The build only
**modifies the files in the working tree** — it does **not** commit them. Capture them with
an explicit `git add` + `git commit`; the convention is a separate **"Update dependency
reports"** commit.

**Why:** the regenerated reports carry environment-specific churn (timestamp, locally
resolved versions), so they surface as unexpected working-tree changes after any full build
and do not belong in an unrelated feature PR.

**How to apply:** when preparing a feature PR, build first, then inspect `git status` /
`git diff` before committing; discard the `docs/dependencies/*` churn with
`git restore docs/dependencies/` so the feature commit stays focused, and let the canonical
release / version-bump flow regenerate them deliberately. For a PR targeting `master`, that
same regeneration is what satisfies the [[license-reports-ci-gate]] — there, commit the
reports rather than discarding them.
