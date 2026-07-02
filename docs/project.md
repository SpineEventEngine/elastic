# Project: elastic

## Overview

`elastic` is a Kotlin Multiplatform library of high-performance hash-table data
structures. It pursues two deliberately separate goals. The first is the
*provable speed win*: primitive-specialized, SwissTable-style open-addressing
maps (leading with `Long → V`) that are much faster and more compact than the
platform standard-library maps by eliminating key boxing — the project's stated
hard requirement. The second is the *research contribution*: the first credible
JVM/Kotlin clean-room implementation of **Elastic Hashing** and **Funnel
Hashing** from Farach-Colton, Krapivin & Kuszmaul
([arXiv:2501.02305](https://arxiv.org/pdf/2501.02305), 2025), positioned not as
a general-purpose fast map but as bounded-worst-case specialists for very high
load factors (≈0.99). These goals partly pull against each other — the paper
structures optimise *probe counts*, not nanoseconds, and lose to `HashMap` on
ordinary workloads — so the roadmap ships the speed win first and frames the
namesake structures honestly. See
[`performance-goals.md`](performance-goals.md),
[`related-papers.md`](related-papers.md), and the phased
[implementation plan](../.agents/tasks/elastic-hashing-implementation-plan.md).

## Architecture

**Role in the org:** a standalone Kotlin Multiplatform **library** (not a tool
or Gradle plugin). It depends on the shared Spine `config` submodule for build
conventions but has no runtime dependency on Spine SDK modules.

**Targets:** JVM plus Kotlin/Native — `macosArm64`, `linuxX64`,
`linuxArm64`, `mingwX64`, `iosArm64`, `iosSimulatorArm64`. JS and Wasm are
deferred until there is demand, as the speed win is not credible there.

**Modules:**
- `elastic` — the library. Public API lives in `io.spine.elastic`
  (e.g. `OpenAddressingLongMap<V>`, `LongHasher`/`fmix64`); sizing math from the
  paper lives in `io.spine.elastic.internal` (`ElasticSizing`, `FunnelSizing`).
- `benchmarks` — a two-tier benchmark harness: `kotlinx-benchmark`
  driving real JMH on the JVM and host-native runs, with a raw-JMH JVM tier
  added when authoritative GC/allocation profiling is needed.
- `config` — the shared Spine repository-configuration submodule.

**Design principles:**
- **Common-core + `expect`/`actual`.** One algorithm in `commonMain` is the
  source of truth on every target; platform seams exist only where a target
  offers a real speedup (e.g. an optional, flagged JVM SIMD scan), never as the
  correctness path.
- **On-heap primitive arrays only** (`LongArray` keys, with control bytes packed
  eight-to-a-`Long` for single-load SWAR scans) — no off-heap, no `Unsafe`, no
  FFM, keeping storage first-class in common code.
- **SWAR, not SIMD.** Group scans use a single-`Long` 8-byte SWAR word; the JDK
  Vector API is unreachable from common Kotlin and measured slower, so it stays
  an optional extra at most.
- **Primitive specialization is the product.** A `value class` boxes the moment
  it crosses a generic boundary, so specializations are hand-written first, with
  code generation a possible later step.
- **Concurrency-aware cores.** Single-threaded structures are designed so a
  single-writer / multi-reader, lock-free-read variant can be *derived* (atomic
  table publication, immutable group snapshots), not retrofitted —
  `SingleWriterSwissLongMap` is that derivation for the SwissTable map.

**Honest positioning (load-bearing):** the baseline this project commits to
beating is the *platform standard library* (boxed `java.util.HashMap`; the
Kotlin/Native `HashMap`), **not** best-in-class native maps like abseil or Rust
`hashbrown`, which are out of reach from common Kotlin. The win comes from
boxing elimination and is *gated on primitive keys*; object-key maps may only
break even with `HashMap`. Every published number names its baseline and
compares primitive-vs-primitive at an equalized load factor.

**Status:** Phases 0–3 are complete: the foundation and harness, the `(n, δ)`
sizing formulas (cross-checked against the `sternma/optopenhash` oracle), the
first fast structures — `SwissLongMap<V>` and the fully-primitive `LongLongMap`
(`Long → Long`) — that demonstrate the speed win (memory-first, and faster at
scale), `FunnelLongMap<V>` (the clean-room funnel-hashing structure, the first
faithful JVM/KMP port, a bounded-worst-case specialist for very high load), and
`ElasticLongMap<V>`, the namesake clean-room **elastic-hashing** structure — the
non-greedy, three-case insertion that breaks the amortized-`log` barrier
(`O(1)` amortized probes). Phase 4 adds `SingleWriterSwissLongMap<V>`, the
concurrent variant of the Phase-1 map: one writer, any number of lock-free
readers, linearizable key-addressed operations — verified with Lincheck on the
JVM and cross-thread stress tests on Native.

Read [`.agents/guidelines/jvm-project.md`](../.agents/guidelines/jvm-project.md)
for the shared build stack, coding style, tests, and versioning policy that
apply to the JVM target. Repo-specific notes that refine those defaults for this
Kotlin Multiplatform codebase:
- **Build:** Gradle with the Kotlin DSL; Kotlin is pinned to **2.3.21** with
  **KSP 2.3.9** (KSP had no 2.4.0 release). Dependencies are declared via
  the `io.spine.dependency.*` `buildSrc` convention, not a version catalog.
- **Tests:** the bulk live in `commonTest` on the `kotlin.test` runner
  with Kotest assertions (`kotest-assertions-core`) and `kotest-property`, so
  they run on JVM and Native. Suites are `internal` and `Spec`-suffixed; full
  JUnit 5 conventions (`@DisplayName`, `@Nested`) are used only in inherently
  JVM-only `jvmTest` suites.
- **Static analysis & coverage:** detekt and Kover are wired as
  KMP-compatible plugins; Kover coverage is reported to Codecov.
