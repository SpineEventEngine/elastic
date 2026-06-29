[![arXiv][arxiv-badge]][arxiv-abs]
[![Ubuntu build][ubuntu-build-badge]][gh-actions]
[![codecov][codecov-badge]][codecov] &nbsp;
[![license][license-badge]][license]

# elastic

A Kotlin Multiplatform library of high-performance hash-table data structures.

The library pursues two deliberately separate goals:

1. **A provable speed win** — primitive-specialized, SwissTable-style
   open-addressing maps (leading with `Long → V`) that are faster and far more
   compact than the platform standard-library maps, by eliminating key and value
   boxing.
2. **A research contribution** — the first credible JVM/Kotlin clean-room
   implementation of **Elastic Hashing** and **Funnel Hashing** (Farach-Colton,
   Krapivin & Kuszmaul, [arXiv:2501.02305][paper], 2025), positioned not as a
   general-purpose fast map but as bounded-worst-case specialists for very high
   load factors (≈0.99).

The baseline this project commits to beating is the *platform standard library*
(boxed `java.util.HashMap`; the Kotlin/Native `HashMap`) — not best-in-class
native maps like abseil or Rust `hashbrown`, which are out of reach from common
Kotlin. The win comes from boxing elimination and is gated on primitive keys.

**Targets:** JVM and Kotlin/Native (`macosArm64`, `linuxX64`, `linuxArm64`,
`mingwX64`, `iosArm64`, `iosSimulatorArm64`). JS and Wasm are deferred.

## Status

The roadmap ships the speed win first and frames the namesake structures
honestly. See [`docs/project.md`](docs/project.md) and the phased
[implementation plan](.agents/tasks/elastic-hashing-implementation-plan.md) for
detail.

- **Phase 0 — complete.** Foundation, two-tier benchmark harness, and the
  `(n, δ)` sizing formulas from the paper, cross-checked against an external
  oracle.
- **Phase 1 — complete.** Two primitive-keyed maps shipped:
  - `SwissLongMap<V>` — a SwissTable/`hashbrown`-style `Long → V` map (no key
    boxing; object values).
  - `LongLongMap` — the fully primitive `Long → Long` specialization (neither
    key nor value boxed).

  Measured against `HashMap<Long, Long>`, `LongLongMap` retains **~4.7× less
  heap** and is **~2× faster on random-access lookup at 1M entries**
  (`SwissLongMap`: ~2.3× less heap).
- **Phase 2 — complete.** `FunnelLongMap<V>` — a clean-room funnel-hashing map
  (the first faithful JVM/KMP port), positioned as a bounded-worst-case
  specialist for very high load factors.
- **Phase 3 — planned.** `ElasticHashTable` — the non-greedy namesake.
- **Phase 4 — planned.** A single-writer / multi-reader variant with lock-free
  reads.
- **Phases 5–6 — planned.** Practical hardening, breadth, validation, and
  release.

## Modules

- `elastic` — the Kotlin Multiplatform library; public API in `io.spine.elastic`.
- `benchmarks` — a JMH-based benchmark harness (`kotlinx-benchmark`).

## Submodules

- `config` — shared repository configuration.

[arxiv-badge]: https://img.shields.io/badge/arxiv%20paper-2501.02305-b31b1b.svg
[arxiv-abs]: https://arxiv.org/abs/2501.02305
[paper]: https://arxiv.org/pdf/2501.02305
[gh-actions]: https://github.com/SpineEventEngine/elastic/actions
[ubuntu-build-badge]: https://github.com/SpineEventEngine/elastic/actions/workflows/build-on-ubuntu.yml/badge.svg
[codecov]: https://codecov.io/gh/SpineEventEngine/elastic
[codecov-badge]: https://codecov.io/gh/SpineEventEngine/elastic/branch/master/graph/badge.svg
[license-badge]: https://img.shields.io/badge/license-Apache%20License%202.0-blue.svg?style=flat
[license]: https://www.apache.org/licenses/LICENSE-2.0
