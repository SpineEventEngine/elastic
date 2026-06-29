---
name: license-reports-ci-gate
description: The "License Reports" check only requires docs/dependencies/{pom.xml,dependencies.md} to differ from the base branch; regenerate to satisfy it — no version bump required
metadata:
  type: project
---
The `License Reports` workflow (`.github/workflows/ensure-reports-updated.yml`) runs on
every PR targeting `master`/`main` or a release-line branch, calling
`config/scripts/ensure-reports-updated.sh`. That script is a **file-modification gate, not
a content comparison**: it only checks that both `docs/dependencies/pom.xml` **and**
`docs/dependencies/dependencies.md` appear in
`git diff --name-only origin/<base>...origin/<head>`. It never regenerates the reports or
diffs them against a fresh build.

**Why:** the reports embed a generation timestamp, and the `config`-submodule reporter
drifts (e.g. `generatePom` now omits BOMs and tooling deps such as `checkstyle`,
`detekt-cli`, `kotlin-bom`, `kotlin-stdlib`), so `master`'s committed reports are routinely
stale. A PR that touches neither report file — including a **docs-only** PR — fails the
check even though nothing is actually wrong with it.

**How to apply:** regenerate on the PR branch and commit the result —
`JAVA_HOME=<JDK 17+> ./gradlew generatePom mergeAllLicenseReports`, then commit
`docs/dependencies/{pom.xml,dependencies.md}` as a separate **"Update dependency reports"**
commit. A **version bump is not required**: regenerating against stale `master` already
yields a diff (timestamp + reporter drift). A version-bumping PR refreshes the same files
as a side effect, which is why such PRs clear this check for free. See
[[build-auto-commits-dependency-reports]].
