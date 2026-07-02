---
name: lincheck-and-native-test-gotchas
description: Concurrency-test gotchas — no commas in backticked test names on Native; Lincheck random scenarios miss narrow races (pin with addCustomScenario); Lincheck's transitive byte-buddy/atomicfu need force()
metadata:
  type: project
---

Lessons from adding the Phase 4 concurrency suite (`SingleWriterSwissLongMapLincheckSpec`,
the common stress spec).

**Why:** each of these failed a build or silently weakened a test before being found.

**How to apply:**

- **Kotlin/Native rejects commas in backticked test names** (`Name contains illegal
  characters: ","`). A `commonTest` function named `` `clears all, and is reusable` ``
  compiles on JVM but fails `compileTestKotlinMacosArm64`. Rephrase without commas.
  Commas remain fine in `jvmTest`-only suites.
- **Lincheck's random scenario generation does not reach narrow multi-context-switch
  races.** A deliberately re-introduced bug (zeroing the key slot on `remove`) passed
  25×2000 stress AND model-checking invocations — the race needs two switches plus a
  fingerprint collision. An explicit `addCustomScenario { initial {...} parallel {
  thread {...} thread {...} } }` caught it deterministically. When a design guards a
  specific interleaving, pin that interleaving as a custom scenario; do not trust
  coverage from iterations alone. Verify a new Lincheck harness catches a planted
  mutation before trusting it.
- **Lincheck 3.x lives at `org.jetbrains.lincheck:lincheck`** (the
  `org.jetbrains.kotlinx` line is frozen at 2.39); DSL in
  `org.jetbrains.lincheck.datastructures`. No `--add-opens` needed on JDK 17. Its
  transitive `byte-buddy` conflicts with `kotlinx-coroutines-debug`'s (via Kotest) and
  its `atomicfu-jvm 0.27.0` with config's forced 0.29.0 — the kmp convention fails on
  conflicts, so `elastic/build.gradle.kts` `force()`s `Lincheck.byteBuddy(-Agent)` and
  `${AtomicFu.std}-jvm`. Expressing a single-writer contract: put every mutating
  operation in one `@Operation(nonParallelGroup = "...")` group; keep `size`-style
  weakly-consistent estimates OUT of the operation set (the verifier rightly rejects
  them).
