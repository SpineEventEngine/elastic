---
name: singular-package-names
description: Package names are singular (io.spine.elastic.benchmark, not …benchmarks) unless the owner explicitly approves a plural
metadata:
  type: feedback
---

Use **singular** nouns for package names — `io.spine.elastic.benchmark`, not
`io.spine.elastic.benchmarks` — unless a plural is specifically requested or
approved.

**Why:** owner correction (2026-07, Phase 4 PR): the benchmark harness packages were
created as `io.spine.elastic.benchmarks`/`…benchmarks.jmh` and had to be renamed to
`…benchmark`/`…benchmark.jmh`.

**How to apply:** when creating a package, name it after the singular concept it
holds (`benchmark`, `dependency`, `report`). Module/directory names (e.g. the
`benchmarks` Gradle module) are a separate concern and keep their existing names —
this rule is about Kotlin/Java package identifiers. When in doubt between singular
and plural, pick singular; ask only if a plural seems genuinely required.
