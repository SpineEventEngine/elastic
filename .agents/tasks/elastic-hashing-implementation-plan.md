# Elastic Hashing in Kotlin Multiplatform — Implementation Plan

**Status:** draft for discussion (phased, with explicit decision points)
**Inputs:** [`kotlin-optimal-open-addressing-report.md`](kotlin-optimal-open-addressing-report.md),
[`docs/related-papers.md`](../../docs/related-papers.md)
**Audience:** implementing engineers, reviewers, project owner

**Decisions log:**
- **DP-1 — DECIDED: Strategy A (speed-first).** Phase 1 ships the SwissTable-style,
  primitive-specialized fast map + the harness; funnel (Phase 2) and elastic (Phase 3)
  follow as high-load specialists / the namesake research contribution.
- **DP-2 — DECIDED: JVM + Native only (for now).** Commit to the two
  performance-credible targets; defer JS/Wasm to a later phase when there is demand.
- **DP-3 — DECIDED: incremental config adoption.** Wire detekt, Kover, copyright,
  version policy, Codecov as KMP-compatible plugins; do not force the JVM-shaped
  `buildSrc/module.gradle.kts` convention onto the KMP source sets.
- **DP-4 — DECIDED: two-tier harness.** `kotlinx-benchmark` drives JVM + Native for
  portable op benchmarks; a small raw-JMH JVM module is added when authoritative
  GC/alloc profiling (`-prof gc`, bytes/op) is first needed.
- **DP-5 — DECIDED: common-first tests.** Bulk of tests in `commonTest` on the
  `kotlin.test` runner + `kotest-assertions-core` (+ `kotest-property`), running on
  JVM and Native; `Spec`-suffix + `internal` naming throughout; full JUnit5
  conventions (`@DisplayName`, `@Nested`) only in inherently JVM-only `jvmTest`
  suites (differential-oracle replay, any Java bridge).
- **DP-6 — DECIDED: build our own, lead with `Long → V`.** Hand-write the
  primitive-`Long`-key / object-value map first (key boxing eliminated; broadly useful
  shape; exercises object-value storage). `androidx.collection` is a reference and
  benchmark bar, **not** a dependency.
- **DP-10 — DECIDED: lean primitive API now, boxed `MutableMap<Long,V>` view in
  Phase 4.** Hot path stays allocation-free; Map interop is opt-in later.
- **DP-8 — DECIDED: tombstones + rehash-on-growth** (Phase 1 control-byte map).
  DELETED control byte; reclaim on resize; rehash-in-place when mostly tombstones.
  The funnel/elastic deletion question is separate and revisited in Phase 2/3.
- **DP-9 — DECIDED: growable, pre-sizable to (n, δ).** Phase 1 map grows by standard
  resize+rehash. Funnel/elastic are a single growable type that can be pre-sized to a
  target (n, δ) for the predictable regime and auto-rebuilds (O(n), transient ~2×
  memory) if exceeded.
- **DP-7 — DECIDED: KSP code-gen from the start.** Stand up a KSP-based generator that
  produces the specialization matrix from one template. Practical sequencing:
  hand-write `Long→V` as the reference prototype, then templatize it and regenerate it
  from the generator so the pipeline is proven on the first type. *(Exact mechanism —
  KSP processor vs Gradle template-expansion task — finalized in Phase 0/1; both emit
  generated Kotlin into the source sets.)*
- **DP-11 — DECIDED: internal metrics now, public inspection API later.** Probe-count
  instrumentation feeds CI bound-verification from day one; a read-only public
  inspection API is promoted in a later phase once its shape is clear.
- **DP-12 — DECIDED: port paper constants, then tune empirically.** Faithful baseline
  first; validate/tune the thresholds and the approximate probe-budget constant against
  the oracles and probe-count metrics.
- **DP-13 — DECIDED: concurrency IS in scope** (no longer "future track"), as a
  **single-writer / multi-reader, lock-free-read** model (**DP-13a**). Single-threaded
  cores ship first and are designed to be concurrency-aware (atomic table publication);
  the SWMR variant is then *derived*, targeting the SwissTable map first. Concurrent
  funnel/elastic is an unprecedented research **stretch**, not a committed deliverable.
- **DP-7a — DECIDED (a): pin Kotlin to 2.3.21 + KSP 2.3.9.** KSP had no Kotlin 2.4.0
  release (latest 2.3.9), so the project is pinned to the latest 2.3.x where the codegen
  toolchain exists. KSP is applied to `elastic` (its `ksp*` tasks register and skip
  cleanly with no processors yet); the generator + processor module arrive in Phase 1.
  All Phase 0 checks re-verified green on 2.3.21 (tests JVM+Native, detekt, Kover,
  benchmark harness).

> This plan supersedes nothing in the kickoff report; it *grounds* it with a market
> survey and a per-platform feasibility analysis, and reorders the phases so the
> project's stated hard requirement — *"much quicker than the standard data
> structures"* — is provable in the first shipping phase. Every phase ends with
> decision points we should discuss before starting it.

---

## 0. The central finding (read this first)

The project has **two goals that partly pull against each other**:

1. **Implement the Elastic Hashing / Funnel Hashing structures** from
   Farach-Colton, Krapivin & Kuszmaul (arXiv:2501.02305, 2025).
2. **Be much quicker than the standard library maps** on Kotlin JVM and the other
   KMP targets.

Grounded research (competitive survey + the only independent wall-clock benchmarks
that exist, cross-checked adversarially) says these are **not the same deliverable**:

- **Elastic/funnel hashing does not win on wall-clock for general workloads.** The
  paper's results are about *probe counts*, not nanoseconds. The one serious
  independent wall-clock port (Zig, vs an in-language SwissTable) finds elastic wins
  **only** on insert/delete at **~99 % load**, is **~25 % *slower* on lookup at 1 M
  entries** (the φ probe-ordering hurts cache locality), and is **10–23× slower than
  a best-in-class SwissTable** (abseil / Rust `hashbrown`). The author's own
  conclusion: *"std.HashMap still wins for typical mixed workloads."* There is **no
  production deployment** of these structures in any language, and **no JVM/Kotlin
  implementation of the full tables exists** — a port would be the first credible one.

- **The "much faster than stdlib" win comes from a different design:** SwissTable-style
  open addressing (control bytes + SWAR group scan) plus **primitive specialization**
  (no boxing). This is a *separate* data structure from elastic/funnel.
  **`androidx.collection` already ships exactly this** as Kotlin Multiplatform —
  `ScatterMap`/`MutableScatterMap` plus a hand-written primitive family
  (`IntIntMap`, `LongObjectMap`, …) — across JVM/Native/JS/Wasm, in **pure common
  Kotlin with no SIMD**. That library is simultaneously our **closest precedent**,
  our **benchmark bar**, and a **build-vs-reuse decision** (see DP-1, DP-6).

**Consequence for phasing.** If Phase 1 ships a funnel/elastic map (as the kickoff
report proposes), the first benchmark we publish will show our structure *losing* to
`HashMap` on normal workloads — because that is what the algorithm does. The honest,
motivating sequence is to **lead with the structure that delivers the speed win**
(SwissTable-style, primitive-specialized) and to position elastic/funnel as
**specialists for the bounded-worst-case-at-very-high-load niche** plus a
**research/novelty contribution** (first credible JVM/KMP port). This reordering is
the single biggest decision in this document — see **DP-1**.

---

## 1. What the research established (grounded facts)

All figures below survived an adversarial verification pass; where a number is a
single-source/single-machine claim it is flagged.

### 1.1 Competitive landscape — the baselines we are measured against

The user's bar is *"standard data structures provided by Kotlin JVM and other
platforms"*, i.e. **stdlib**, not the specialist libraries. But reviewers will ask
about the specialists, so we must know where they sit.

| Library                                                       | Probing / layout                            | Primitive maps               | License           | Maintained (2026)            | Note                                            |
|---------------------------------------------------------------|---------------------------------------------|------------------------------|-------------------|------------------------------|-------------------------------------------------|
| **java.util.HashMap** (= Kotlin JVM `HashMap`, a `typealias`) | separate chaining + treeify, LF 0.75        | no (boxes)                   | —                 | n/a (JDK)                    | **the baseline to beat**                        |
| Kotlin **Native/Wasm** `HashMap`                              | open addressing, linear probing, tombstones | no (boxes)                   | —                 | n/a (stdlib)                 | **the baseline on those targets**               |
| **fastutil**                                                  | linear probing, parallel arrays, LF 0.75    | yes (build-time codegen)     | Apache-2.0        | **active** (8.5.18)          | broadest coverage; the default people reach for |
| **HPPC**                                                      | linear probing                              | yes (template codegen)       | Apache-2.0        | active (slow cadence)        | **≈ fastutil speed (within ~2 %)**              |
| **Koloboke**                                                  | interleaved key/value array                 | yes                          | Apache-2.0        | **abandoned since 2016**     | best published cache-bound throughput, but dead |
| **Eclipse Collections**                                       | open addressing                             | yes (StringTemplate codegen) | EDL-1.0 + EPL-1.0 | **very active** (13.0, 2025) | large API surface; heavyweight                  |
| **Agrona**                                                    | linear probing, LF 0.55                     | yes (hand-written, curated)  | Apache-2.0        | very active                  | zero-allocation hot paths; HFT-grade            |
| **Trove**                                                     | open addressing                             | yes                          | **LGPL-2.1**      | **abandoned (2012)**         | **the actual slowest** in the cited bench       |
| **JCTools `NonBlockingHashMapLong`**                          | lock-free open addressing                   | `long` keys                  | Apache-2.0        | active                       | *concurrent*, different problem                 |
| Apache Commons `Flat3Map`                                     | switch dispatch ≤3 then HashMap             | no                           | Apache-2.0        | active                       | tiny maps only                                  |

**Corrected facts** (the kickoff report and early drafts got these wrong):

- **fastutil and HPPC are co-fastest** among the classic open-addressing libs (within
  ~2 %). **Trove** is the slowest; **Koloboke** leads the cache-bound cases. *Do not*
  build positioning on "beat fastutil's slow layout" — that claim is false.
- **No mature, production-grade SwissTable exists in pure Java/JVM.** The only direct
  port (`bluuewhale/hash-smith`) is an experimental single-author project. Its
  headline win is **memory** (~53 % retained-heap reduction for maps vs `HashMap`,
  single-machine), not raw speed.
- **There is no first-class fast Kotlin/KMP primitive-map library** at the
  fastutil/HPPC tier. This is the **genuine product gap** (bigger than elastic hashing).

**Realistic positioning vs the specialists:** we are unlikely to beat fastutil/HPPC on
raw single-thread JVM lookup. Our credible differentiators are **(a) true Kotlin
Multiplatform** (they are JVM-only), **(b) memory footprint** (SwissTable layout),
**(c) maintained-and-modern**, and **(d) Kotlin-idiomatic ergonomics**. Against
**stdlib** (the stated bar) a primitive-specialized SwissTable-style map is a clear,
provable win.

### 1.2 Per-platform feasibility — where "much faster than stdlib" is real

| Target            | Primitive keys                             | Object keys                                             | Lever                                                             | Confidence |
|-------------------|--------------------------------------------|---------------------------------------------------------|-------------------------------------------------------------------|------------|
| **JVM**           | **REALISTIC** (≈2–5× vs *boxed* `HashMap`) | **MARGINAL** (≈1.0–1.3×; break-even risk on read-heavy) | open addressing + primitive specialization + SWAR                 | high       |
| **Kotlin/Native** | **REALISTIC**                              | marginal–realistic                                      | same; specialization *mandatory* (value classes box)              | med-high   |
| **Kotlin/JS**     | **UNLIKELY–MARGINAL**                      | **UNLIKELY**                                            | hard to beat V8 native `Map`; only dense-Int via typed arrays     | medium     |
| **Kotlin/Wasm**   | marginal–realistic                         | marginal                                                | WasmGC stable; **no reachable SIMD**; SWAR only; maturity in flux | med-low    |

Load-bearing constraints behind the table:

- **The speed win is "vs boxed stdlib via boxing elimination," not an algorithmic
  win.** State the baseline precisely in every published number, or it won't survive
  external review. Object-key maps may only *break even* with `java.util.HashMap`
  (whose treeify + high-bit spread is a real safety net); gate the value proposition
  on **primitive keys**.
- **SIMD is not a baseline.** The JDK Vector API is on its *10th–11th* incubation,
  Valhalla-blocked with no finalization date, **unreachable from KMP common code**,
  and **measured ~24 % slower than SWAR** for 8-byte control-group scans in the one
  serious Java experiment. Use the **single-`Long` 8-byte SWAR group** everywhere;
  keep any JVM SIMD path as an *optional, flagged, JVM-actual* extra — never the
  correctness path.
- **Off-heap is a trap for a KMP library.** FFM (`MemorySegment`) is JVM-only and
  `sun.misc.Unsafe` memory access is deprecated→throwing by ~JDK 26. Stay on
  on-heap primitive arrays (`IntArray`/`LongArray`), which are first-class in common.
- **Primitive specialization must be hand-written or code-generated.** Kotlin has no
  primitive-array generics; a `value class` **boxes** the moment it crosses a generic
  `T`, nullable, interface, or array boundary — i.e. exactly the hot path. Follow the
  androidx model: hand-write the first 1–2 specializations, add KSP codegen later if
  the matrix grows (DP-7).
- **A ~50 % JVM micro-win** in the one serious Java SwissTable experiment came from
  calling `key.equals(other)` directly at the call site instead of
  `Objects.equals(...)` (clean type-profile, no megamorphic pollution). Bake this into
  the JVM path.

### 1.3 Reference implementations — which to trust as oracles

| Repo                                         | Lang   | Trust                                                         | Use                                        |
|----------------------------------------------|--------|---------------------------------------------------------------|--------------------------------------------|
| **`sternma/optopenhash`**                    | Python | **oracle** (105★, MIT, paper-faithful, tested)               | primary correctness oracle                 |
| **`aaron-ang/opthash-rs`**                   | Rust   | **oracle** (Apache-2.0, both tables, SwissTable core, active) | secondary oracle + layout/growth reference |
| `jorgik1/elastic-hash` (`open-elastic-hash`) | Python | cross-check only (7★, solo)                                  | tertiary                                   |
| `joshuaisaact/elastic-hash`                  | Zig    | benchmark intuition only (no funnel, no license)              | the wall-clock evidence source             |
| `KarpelesLab/elastichash`                    | Go     | **avoid** (AI-generated, **wrong author attribution**)        | —                                          |
| `m-fire/platform-optimal-hash`               | Kotlin | **avoid** (primitives only, not the tables, AI-derived)       | —                                          |
| `MWARDUNI/ElasticHashing`                    | —      | **does not exist** (404 confirmed)                            | —                                          |
| `devintegeritsm/KrapivinHashTable`           | C#     | API-ergonomics only (it's quadratic probing, *not* the paper) | —                                          |

The report's **algorithm summary is faithful to the paper** (geometric levels; the
`φ` injection; the non-greedy 3-case insertion with the real `δ/2` and `0.25`
thresholds; funnel `α = ⌈4 log(1/δ)+10⌉` levels of `β = ⌈2 log(1/δ)⌉`-sized buckets +
overflow array). Correct author trio: **Farach-Colton, Krapivin, Kuszmaul** — do not
propagate the wrong attribution some repos carry.

**Implication:** correctness verification cannot diff against an in-language oracle.
We must **pre-generate operation traces + expected results from the Python/Rust
oracles offline and commit them as golden fixtures** (Phase 0).

### 1.4 Benchmark framework

- **`kotlinx-benchmark`** (JetBrains, **0.4.17**, Alpha) is the only credible
  KMP-native option. On **JVM it generates real JMH**; Native runs host-only; JS via
  Node; Wasm experimental. Requires the **allOpen** plugin for `@State` classes or
  benchmarks silently fail to generate. *Verify the latest version on Maven Central
  before pinning — cadence is slow.*
- For authoritative JVM numbers with GC/alloc profiling (`-prof gc`, `bytes/op`), add
  a **plain JMH module** alongside — `kotlinx-benchmark` doesn't surface all JMH
  profilers. **Two-tier harness** (DP-4).
- **Methodology that makes numbers credible** (copy martinus / hashbrown / abseil):
  separate `insert / lookup-hit / lookup-miss / iterate / remove / churn`; sweep key
  distributions incl. **clustered/adversarial** (open addressing's worst case); sizes
  from cache-resident (~1 k) to out-of-cache (≥10 M); load factors 0.5/0.75/0.9/0.95/0.99;
  **presized vs default-capacity** (isolates resize cost); report **ns/op AND bytes/op
  AND peak footprint**; `@Fork ≥ 3`; `Blackhole`; `@Param` over impl type to keep call
  sites monomorphic. Fairness vs `HashMap`/fastutil: equalize load factor, presize each
  in *its own* units, compare primitive-vs-primitive.

---

## 2. Assessment of the kickoff report (is it grounded and helpful?)

**Grounded and helpful:**

- ✅ Honest §1 positioning ("predictable bounded behavior at high load, not fastest
  general map"). The research *strengthens* this — it's the only defensible elastic
  pitch.
- ✅ The algorithm summary (§2) is faithful to the paper.
- ✅ SWAR-first, Vector API as optional/incubator (§3.3) — correct, and now backed by
  a measured SWAR-beats-SIMD result.
- ✅ Control-byte/fingerprint layout, per-level salt, single-arena descriptors (§3.2).
- ✅ Differential/oracle + property testing + probe-count regression metrics (§5).
- ✅ Identifying the genuine gap (no faithful JVM implementation).
- ✅ Clean-room from the paper for IP (§6.5).

**Needs correction or reframing:**

- ⚠️ **Scope says "Kotlin / JVM"; the goal is KMP.** Most of §3.2–3.3 (Vector API,
  arenas, `arrayOfNulls`) is JVM-shaped. The architecture must be **common-core +
  `expect`/`actual`**, with on-heap primitive arrays as the portable substrate.
- ⚠️ **Phase ordering buries the speed win.** The report leads Phase 1 with
  `FunnelHashMap`/`ElasticHashMap`. Per §0, that makes the first published benchmark a
  loss. Recommend leading with the SwissTable-style fast map (DP-1).
- ⚠️ **The "much faster" goal is not achievable *with elastic/funnel*** — only with the
  SwissTable-style design + primitive specialization. The report doesn't separate
  these two deliverables; this plan does.
- ⚠️ **Competitive framing** (§4 Phase 4 mentions fastutil/Koloboke/etc.) must use the
  corrected facts: fastutil ≈ HPPC, Trove slowest, and we likely won't beat
  fastutil/HPPC on raw JVM speed — differentiate on KMP + memory + maintenance.
- ⚠️ **Vector API** should be demoted from "Phase 3 performance path" to "optional,
  may never be worth it"; SWAR is the permanent answer.
- ⚠️ **Deletion & growth are off-paper** (report says so) — make these first-class
  design tasks, not afterthoughts (DP-8, DP-9).

**Bottom line:** the report is a good, honest kickoff. Its *direction* is right; its
*scope* (JVM→KMP) and *phase ordering* (speed win first) need the changes above.

---

## 3. Recommended architecture

```
elastic/                         (KMP library module)
  commonMain/
    OpenAddressingMap interface  + ScatterMap-style generic map
    control-byte + SWAR group scan      (pure Kotlin, authoritative everywhere)
    primitive specialization #1  (hand-written; e.g. Long→V or Int→Int)
    Hasher abstraction + per-level salt
    FunnelHashTable / ElasticHashTable  (clean-room from paper)   [later phases]
  jvmMain/   (actual) optional SIMD scan behind capability flag; call-site equals
  nativeMain (actual) = common SWAR fallback        [JS/Wasm source sets added later]
benchmarks/                      (KMP, kotlinx-benchmark)  + jvm-only raw JMH module
                                 (atomicfu for the Phase 4 SWMR concurrent variant)
```

Principles:

- **One algorithm in common; platform seams only where a target offers a real
  speedup.** The scalar SWAR path is the source of truth on every target; `expect`/
  `actual` exists for the JVM SIMD experiment and call-site-equals, nothing else.
- **On-heap primitive arrays** as the storage substrate (`ByteArray` control bytes +
  typed key/value arrays). No off-heap, no `Unsafe`, no FFM.
- **Primitive specialization is the product**, generics are the convenience. Lead with
  one hand-written specialization that demonstrates the boxing-elimination win.
- **Concurrency-aware single-threaded cores.** Even the single-threaded structures are
  designed so a **single-writer / multi-reader, lock-free-read** variant (DP-13) can be
  *derived*, not retrofitted: a resize publishes the new table by a single atomic store,
  control-byte groups are read as immutable snapshots, and portable atomics come from
  `kotlinx-atomicfu` (works across JVM + Native). The SWMR variant is a separate phase.
- **Honest, baseline-qualified numbers** in every artifact (README, benchmarks, CI).

---

## 4. Phased plan

> **DP-1 is decided: Strategy A (speed-first).** The ordering below is therefore the
> committed plan, not a conditional one.

### Phase 0 — Foundation, harness, and spec (no data structure yet)

**Goal:** a clean KMP build, a credible two-tier benchmark harness, a correctness/
differential-oracle harness, the `(n, δ)` sizing formulas, and written success metrics.

**Status — IMPLEMENTED** (branch `phase-0-foundation`), green on JVM + host Native:
- Scaffold cleaned (`lib/` dropped, catalog is the single Kotlin-version source);
  JVM + Native targets wired (`macosX64` dropped — deprecated by Kotlin).
- `OpenAddressingLongMap<V>`, `LongHasher`/`fmix64`, and `ElasticSizing`/`FunnelSizing`
  ported from the paper and **cross-checked byte-for-byte against `sternma/optopenhash`**
  (example fixtures + `kotest-property` invariants).
- Static analysis (detekt, gating) + coverage (Kover) wired and passing.
- Two-tier benchmark harness (`kotlinx-benchmark`, JVM JMH + Native) running, with the
  `StdlibHashMapBenchmark` baseline captured ([performance-goals](../../docs/performance-goals.md)).
- **Differential-oracle scope clarified:** the *sizing* cross-check vs `sternma` is done
  (unit + property tests). *Map-semantics* differential testing is best done against an
  in-process `LinkedHashMap` model once a structure exists → **Phase 1**; the behavioral
  `sternma`/`opthash` cross-check (probe paths) → **Phase 2/3**. No premature golden
  fixtures are committed.
- **Resolved DP-7a:** pinned Kotlin **2.3.21** + KSP **2.3.9** (KSP had no 2.4.0
  release); KSP applied to `elastic` (tasks register), generator deferred to Phase 1.

**Deliverables**
- Scaffold cleanup: **drop `lib/`** and its leftover `commons-math3`/`guava` catalog
  entries; align Kotlin versions (root `2.4.0` vs catalog `2.3.20`); add copyright
  headers (`update-copyright`); add the **JVM + Native targets** (DP-2) to
  `elastic`/`benchmarks`; wire detekt/Kover/Codecov as KMP-compatible plugins (DP-3).
- Stand up the **KSP code-gen scaffolding** (DP-7) and add **`kotlinx-atomicfu`**
  (DP-13) so the specialization pipeline and concurrency-aware primitives exist before
  the first structure.
- `OpenAddressingMap` primitive interface + `Hasher` abstraction (API skeleton only).
- `(n, δ) → level sizing` functions ported from the paper, cross-checked vs
  `sternma/optopenhash`, unit-tested against hand-computed values.
- **Two-tier benchmark harness:** `kotlinx-benchmark` (allOpen wired) across JVM +
  Native + a JVM raw-JMH module for `-prof gc`. A trivial map (e.g. wrap `HashMap`)
  proves the harness end-to-end and establishes the **baseline numbers** for stdlib on
  JVM and Native.
- **Differential-oracle fixtures:** scripts that run `sternma/optopenhash` (and later
  `opthash-rs`) over seeded op-sequences and emit golden `(op, key, expected)` traces
  committed as test resources; a JVM replay harness that diffs our structure against them.
- **Property/oracle test scaffolding:** Kotest property tests (KMP) for `insert/get/
  remove/contains` vs a reference `LinkedHashMap` model.
- Written **success metrics** (e.g. "primitive map ≥ N× over stdlib at LF 0.9 on JVM;
  ≤ X bytes/entry; elastic insert win at LF 0.99; never worse than stdlib by >Y% on
  lookup").

**Decision points:** all resolved — DP-2 (JVM + Native), DP-3 (incremental config),
DP-4 (two-tier harness), DP-5 (common-first tests), plus DP-7/DP-13 scaffolding seeded
here.

### Phase 1 — First fast structure (the "much faster than stdlib" proof) — *Strategy A*

**Goal:** ship one structure that *demonstrably* beats stdlib, and prove the storage
foundation (control bytes + SWAR + primitive specialization).

**Deliverables**
- A **SwissTable/ScatterMap-style open-addressing map**: `ByteArray` control bytes
  (7-bit fingerprint + empty/deleted sentinels), single-`Long` 8-byte SWAR group scan,
  LF ~0.875, **tombstone deletion + rehash-on-growth** (DP-8), and **resize+rehash
  growth** (DP-9) designed for **atomic table publication** so the Phase-4 SWMR variant
  is a derivation, not a rewrite.
- The **`Long → V` primitive specialization** (DP-6) as the lead structure, **produced
  by the KSP code-gen pipeline** (DP-7) — hand-write it as the prototype, then templatize
  and regenerate so the generator is proven on the first type. **Lean primitive API**
  (DP-10).
- Benchmarks vs stdlib on every target; vs fastutil/HPPC on JVM as a *reference ceiling*
  (not a success gate). Memory footprint reported.

**Decision points:** all resolved (DP-1, DP-6, DP-7, DP-8, DP-9, DP-10). This phase is
ready to scope into tasks.

### Phase 2 — First paper structure: `FunnelHashTable` (greedy, simpler)

**Goal:** the first faithful, clean-room implementation of the paper's funnel hashing,
differential-tested against the oracles, with probe-count instrumentation that turns
the paper's bounds into CI assertions.

**Deliverables**
- Clean-room `FunnelHashTable` from §5 of the paper (α levels of β-sized buckets +
  overflow array), reusing Phase-1 storage primitives where sensible.
- Differential tests vs `sternma`/`opthash` golden fixtures; probe-count regression
  metrics confirming `O(log² 1/δ)` worst-case at increasing load.
- Honest benchmark: show the high-load insert/delete behavior **and** the lookup cost,
  framed per §0.

**Decision points:** DP-9 (growth) and DP-11 (probe metrics) resolved. **Open, revisit
in-phase:** funnel/elastic **deletion** semantics — off-paper; tombstones degrade
funnel/elastic probe paths differently than the SwissTable map, so this needs its own
design (the DP-8 decision covers only the Phase 1 map).

### Phase 3 — `ElasticHashTable` (non-greedy, the namesake)

**Goal:** the project's namesake structure, with the 3-case non-greedy insertion and
the `φ` injection.

**Deliverables**
- Clean-room `ElasticHashTable` from §4 with the `δ/2` / `0.25` thresholds; per-level
  salt re-randomization; differential + probe-count tests.
- Benchmark confirming `O(1)` amortized / `O(log 1/δ)` worst-case probe behavior, and
  the documented lookup-at-scale regression so we're not surprised by it.

**Decision points:** DP-12 (threshold constants) resolved — port then tune. Shares the
funnel/elastic deletion open item from Phase 2.

### Phase 4 — Concurrency: single-writer / multi-reader (lock-free reads)

**Goal:** a thread-safe variant with **lock-free reads** (DP-13 / DP-13a), derived from
the now-solid single-threaded SwissTable map.

**Deliverables**
- A **SWMR concurrent variant** of the `Long → V` map: readers never block (they read
  immutable control-byte group snapshots); writes are serialized to one writer (or
  externally synchronized); resize publishes the new table by a single atomic store via
  `kotlinx-atomicfu`. Memory-model-correct on both JVM and Native.
- **Linearizability / concurrency testing** (Lincheck on JVM) for the read/write/resize
  interleavings; stress tests under concurrent readers + a writer.
- Concurrent read-scaling benchmarks (throughput vs reader count).

**Decision points:** DP-13a (model) resolved. **Stretch (explicitly not committed):**
concurrent funnel/elastic — unprecedented; gate on a separate research spike.

### Phase 5 — Practical hardening & breadth

**Goal:** make the structures broadly usable.

**Deliverables:** boxed `MutableMap<Long,V>` view (DP-10); more primitive specializations
via the KSP pipeline (DP-7); pluggable hashers + good default finalizer + per-level salt;
optional JVM-actual SIMD scan behind a capability flag + feature test (authoritative path
stays SWAR); call-site-`equals` JVM optimization.

### Phase 6 — Validation & release

**Goal:** publish with numbers that survive external scrutiny.

**Deliverables:** full benchmark matrix (the §1.4 grid) vs `HashMap` + fastutil/HPPC +
Eclipse + Agrona at LF 0.5/0.75/0.9/0.95/0.99; memory footprint; reproducibility
metadata (JDK/OS/CPU pinned, seeds committed, JSON outputs); README with the honest,
baseline-qualified positioning; Maven Central publication.

---

## 5. Consolidated decision points

| #        | Decision                                                                                                                                                | Recommendation                                                                                                                                                                                                  | Why it matters                                                                                                                                                                                             |
|----------|---------------------------------------------------------------------------------------------------------------------------------------------------------|-----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| **DP-1** | **Strategy / phase ordering.** A) speed-first (SwissTable map Phase 1, elastic later); B) elastic-first (paper structures Phase 1); C) both in Phase 1. | **✅ DECIDED: A**                                                                                                                                                                                               | Determines whether the project's hard requirement is provable early, and how we position elastic.                                                                                                          |
| DP-2     | Target set.                                                                                                                                             | **✅ DECIDED: JVM + Native only (for now);** add JS/Wasm later on demand                                                                                                                                        | focuses the performance-credible targets; smaller surface, faster Phase 0                                                                                                                                  |
| DP-3     | Build infra: adopt full Spine `config` conventions (buildSrc/module.gradle.kts, detekt, Kover) vs keep the lightweight `gradle init` setup              | **✅ DECIDED: incremental** — detekt/Kover/copyright/version/Codecov as KMP-compatible plugins; no JVM-shaped `buildSrc/module.gradle.kts` forced onto KMP source sets                                          | consistency with the org on static analysis/coverage/versioning, without KMP build yak-shaving                                                                                                             |
| DP-4     | Benchmark framework                                                                                                                                     | **✅ DECIDED: two-tier** — `kotlinx-benchmark` (JVM + Native) + raw JMH (JVM profiling)                                                                                                                         | only credible KMP option; JMH for authoritative JVM/GC numbers                                                                                                                                             |
| DP-5     | Common test stack                                                                                                                                       | **✅ DECIDED: common-first** — `kotlin.test` + `kotest-assertions-core`/`kotest-property` in `commonTest` (JVM + Native); `Spec`/`internal` naming everywhere; `@DisplayName`/`@Nested` only in JVM-only suites | reconciles KMP commonTest with Spine's Kotest/JUnit5 policy; maximizes shared coverage                                                                                                                     |
| DP-6     | First specialization + build-vs-reuse                                                                                                                   | **✅ DECIDED: build our own, lead with `Long→V`**; `androidx.collection` is reference + bar, **not a dependency**                                                                                               | depending on androidx couples us to an Android-branded artifact whose non-Android KMP targets are *experimental* — against the "avoid immature dependencies" goal; we still validate that we match/beat it |
| DP-7     | Specialization matrix mechanism                                                                                                                         | **✅ DECIDED: KSP code-gen from the start** — one template generates the matrix; hand-write `Long→V` as the prototype, then regenerate it from the generator                                                    | consistency + no source duplication at scale (fastutil/HPPC/Eclipse all code-gen)                                                                                                                          |
| DP-8     | Deletion policy (Phase 1 map)                                                                                                                           | **✅ DECIDED: tombstones + rehash-on-growth** (DELETED control byte; reclaim on resize; rehash-in-place when mostly tombstones)                                                                                 | proven SwissTable/hashbrown approach; composes with control-byte SWAR scan. Funnel/elastic deletion is a separate Phase 2/3 decision                                                                       |
| DP-9     | Growth policy                                                                                                                                           | **✅ DECIDED: growable, pre-sizable to (n, δ)** — Phase 1 map resize+rehash; funnel/elastic one growable type, pre-size for the predictable regime, auto-rebuild if exceeded                                    | one type serves both predictable and ergonomic use; document the O(n) rebuild / transient 2× memory                                                                                                        |
| DP-10    | API depth                                                                                                                                               | **✅ DECIDED: lean primitive API now; boxed `MutableMap<Long,V>` view in Phase 4**                                                                                                                              | hot path allocation-free; Map interop opt-in later                                                                                                                                                         |
| DP-11    | Expose probe metrics                                                                                                                                    | **✅ DECIDED: internal now (CI bound-verification); public read-only inspection API later**                                                                                                                     | keeps the bound-verification capability without committing public surface prematurely                                                                                                                      |
| DP-12    | Elastic threshold constants                                                                                                                             | **✅ DECIDED: port paper constants, then tune empirically** against oracles + probe metrics                                                                                                                     | faithful baseline + practically validated; the public ports flag these as approximate                                                                                                                      |
| DP-13    | Concurrency                                                                                                                                             | **✅ DECIDED: in scope** — single-threaded cores first (concurrency-aware), then a thread-safe variant (Phase 4)                                                                                                | broadens applicability; the biggest roadmap addition. Concurrent funnel/elastic is a research stretch, not committed                                                                                       |
| DP-13a   | Concurrency model                                                                                                                                       | **✅ DECIDED: single-writer / multi-reader, lock-free reads**                                                                                                                                                   | best value-to-complexity; fits the low-latency niche; far more tractable than full lock-free, portable via `kotlinx-atomicfu`                                                                              |

---

## 6. Testing & verification

- **Differential testing** against committed golden traces from `sternma/optopenhash`
  + `aaron-ang/opthash-rs` (no in-language oracle exists).
- **Property-based testing** (Kotest property, KMP) over randomized op-sequences vs a
  `LinkedHashMap` model.
- **Probe-count regression metrics** asserting the paper's bounds at rising load —
  validates both correctness of the sizing constants and the algorithm.
- **High-load stress** (95–99.x %): no infinite probing, correct behavior.
- **Benchmark fairness gates** per §1.4 (adversarial keys mandatory; baseline always
  named; primitive-vs-primitive).
- **Concurrency (in scope, Phase 4):** linearizability testing of the SWMR variant with
  **Lincheck** (JVM) over read/write/resize interleavings; stress tests with concurrent
  readers + a writer; verify reads never observe a torn/partial entry across a resize.

## 7. Key risks

1. **"KMP + fastest-possible" is partly self-contradictory.** Every top-tier speed
   lever (SIMD, off-heap arenas, V8 struct layout) is per-platform and unreachable from
   common code. We are "fast vs Kotlin stdlib," **never** "fast vs the best native map."
   Say so everywhere.
2. **Object-key, read-heavy workloads may not beat `java.util.HashMap`.** Gate the
   speed claim on primitive keys; require a JMH win vs `HashMap` (not `LinkedHashMap`)
   before any object-key claim.
3. **Elastic/funnel are the wrong tool for a general fast map.** Keep "implement the
   paper" (novelty, high-load niche) and "ship a fast KMP map" (SwissTable model)
   explicitly separate, as this plan does.
4. **No JVM oracle** → correctness rests on cross-language golden fixtures; budget for it.
5. **Vector API instability / Wasm immaturity** → both optional/provisional; re-measure
   per Kotlin/JDK release.
6. **IP:** clean-room from the paper (arXiv is the safe primary source); references are
   MIT/Apache but we do not copy them.
7. **Concurrency adds real risk (DP-13).** Lock-free reads over a resizable
   control-byte table are subtle: a reader must never see a torn entry or a stale table
   across a resize. Mitigations: design Phase 1 for atomic table publication; keep the
   SWMR model (one writer) to sidestep writer-writer races; gate with Lincheck. **A
   concurrent funnel/elastic map has no precedent in any language** — treat it as a
   research spike with a kill switch, never a committed deliverable.

## 8. Primary sources

- Farach-Colton, Krapivin, Kuszmaul. *Optimal Bounds for Open Addressing Without
  Reordering.* arXiv:2501.02305 (2025) — normative for sizing, thresholds, `φ`.
- Oracles: `sternma/optopenhash` (Python, MIT), `aaron-ang/opthash-rs` (Rust, Apache-2.0).
- Precedent/bar: `androidx.collection` `ScatterMap`/`IntIntMap` (KMP, Apache-2.0).
- Wall-clock evidence: `joshuaisaact/elastic-hash` (Zig) benchmark write-up.
- Java SwissTable + SWAR-vs-SIMD: `bluuewhale/hash-smith` and the author's deep-dives.
