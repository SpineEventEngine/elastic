---
name: pre-pr-gate
description: `gh pr create` is blocked until the /pre-pr skill writes a PASS sentinel for the current HEAD
metadata:
  type: project
---
A PreToolUse hook (`.agents/scripts/pre-pr-gate.sh`) blocks `gh pr create` unless
`.git/pre-pr.ok` exists with `status=PASS` and `head=<current HEAD SHA>`.

**Why:** it guarantees the build/check and reviewers ran for the exact commit being
proposed, so a PR cannot skip the local gate.

**How to apply:** run the `/pre-pr` skill **after** the final commit — the sentinel is
tied to HEAD, so any later commit or `--amend` invalidates it and the gate re-blocks.
Its Gradle steps need a JDK 17+ `JAVA_HOME` (see [[macos-build-prerequisites]]). The
sentinel lives under `.git/`, so it is per-clone and never committed.
