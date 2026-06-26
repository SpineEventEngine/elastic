# Elastic Hashing in Kotlin Multiplatform — Implementation Plan

**Status:** draft for discussion (phased, with explicit decision points)
**Inputs:** [`kotlin-optimal-open-addressing-report.md`](kotlin-optimal-open-addressing-report.md),
[`docs/related-papers.md`](../../docs/related-papers.md)
**Audience:** implementing engineers, reviewers, project owner

**Decisions log:**
- **DP-1 — DECIDED: Strategy A (speed-first).** Phase 1 ships the SwissTable-style,
  primitive-specialized fast map + the harness; funnel (Phase 2) and elastic (Phase 3)
  follow as high-load specialists / the namesake research contribution.

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

| Library | Probing / layout | Primitive maps | License | Maintained (2026) | Note |
|---|---|---|---|---|---|
| **java.util.HashMap** (= Kotlin JVM `HashMap`, a `typealias`) | separate chaining + treeify, LF 0.75 | no (boxes) | — | n/a (JDK) | **the baseline to beat** |
| Kotlin **Native/Wasm** `HashMap` | open addressing, linear probing, tombstones | no (boxes) | — | n/a (stdlib) | **the baseline on those targets** |
| **fastutil** | linear probing, parallel arrays, LF 0.75 | yes (build-time codegen) | Apache-2.0 | **active** (8.5.18) | broadest coverage; the default people reach for |
| **HPPC** | linear probing | yes (template codegen) | Apache-2.0 | active (slow cadence) | **≈ fastutil speed (within ~2 %)** |
| **Koloboke** | interleaved key/value array | yes | Apache-2.0 | **abandoned since 2016** | best published cache-bound throughput, but dead |
| **Eclipse Collections** | open addressing | yes (StringTemplate codegen) | EDL-1.0 + EPL-1.0 | **very active** (13.0, 2025) | large API surface; heavyweight |
| **Agrona** | linear probing, LF 0.55 | yes (hand-written, curated) | Apache-2.0 | very active | zero-allocation hot paths; HFT-grade |
| **Trove** | open addressing | yes | **LGPL-2.1** | **abandoned (2012)** | **the actual slowest** in the cited bench |
| **JCTools `NonBlockingHashMapLong`** | lock-free open addressing | `long` keys | Apache-2.0 | active | *concurrent*, different problem |
| Apache Commons `Flat3Map` | switch dispatch ≤3 then HashMap | no | Apache-2.0 | active | tiny maps only |

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

| Target | Primitive keys | Object keys | Lever | Confidence |
|---|---|---|---|---|
| **JVM** | **REALISTIC** (≈2–5× vs *boxed* `HashMap`) | **MARGINAL** (≈1.0–1.3×; break-even risk on read-heavy) | open addressing + primitive specialization + SWAR | high |
| **Kotlin/Native** | **REALISTIC** | marginal–realistic | same; specialization *mandatory* (value classes box) | med-high |
| **Kotlin/JS** | **UNLIKELY–MARGINAL** | **UNLIKELY** | hard to beat V8 native `Map`; only dense-Int via typed arrays | medium |
| **Kotlin/Wasm** | marginal–realistic | marginal | WasmGC stable; **no reachable SIMD**; SWAR only; maturity in flux | med-low |

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

| Repo | Lang | Trust | Use |
|---|---|---|---|
| **`sternma/optopenhash`** | Python | **oracle** (105★, MIT, paper-faithful, tested) | primary correctness oracle |
| **`aaron-ang/opthash-rs`** | Rust | **oracle** (Apache-2.0, both tables, SwissTable core, active) | secondary oracle + layout/growth reference |
| `jorgik1/elastic-hash` (`open-elastic-hash`) | Python | cross-check only (7★, solo) | tertiary |
| `joshuaisaact/elastic-hash` | Zig | benchmark intuition only (no funnel, no license) | the wall-clock evidence source |
| `KarpelesLab/elastichash` | Go | **avoid** (AI-generated, **wrong author attribution**) | — |
| `m-fire/platform-optimal-hash` | Kotlin | **avoid** (primitives only, not the tables, AI-derived) | — |
| `MWARDUNI/ElasticHashing` | — | **does not exist** (404 confirmed) | — |
| `devintegeritsm/KrapivinHashTable` | C# | API-ergonomics only (it's quadratic probing, *not* the paper) | — |

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
  nativeMain / jsMain / wasmJsMain  (actual) = common SWAR fallback
benchmarks/                      (KMP, kotlinx-benchmark)  + jvm-only raw JMH module
```

Principles:

- **One algorithm in common; platform seams only where a target offers a real
  speedup.** The scalar SWAR path is the source of truth on every target; `expect`/
  `actual` exists for the JVM SIMD experiment and call-site-equals, nothing else.
- **On-heap primitive arrays** as the storage substrate (`ByteArray` control bytes +
  typed key/value arrays). No off-heap, no `Unsafe`, no FFM.
- **Primitive specialization is the product**, generics are the convenience. Lead with
  one hand-written specialization that demonstrates the boxing-elimination win.
- **Honest, baseline-qualified numbers** in every artifact (README, benchmarks, CI).

---

## 4. Phased plan

> **DP-1 is decided: Strategy A (speed-first).** The ordering below is therefore the
> committed plan, not a conditional one.

### Phase 0 — Foundation, harness, and spec (no data structure yet)

**Goal:** a clean KMP build, a credible two-tier benchmark harness, a correctness/
differential-oracle harness, the `(n, δ)` sizing formulas, and written success metrics.

**Deliverables**
- Scaffold cleanup: **drop `lib/`** and its leftover `commons-math3`/`guava` catalog
  entries; align Kotlin versions (root `2.4.0` vs catalog `2.3.20`); add copyright
  headers (`update-copyright`); decide target set (DP-2) and add targets to
  `elastic`/`benchmarks`.
- `OpenAddressingMap<K,V>` interface + `Hasher<K>` abstraction (API skeleton only).
- `(n, δ) → level sizing` functions ported from the paper, cross-checked vs
  `sternma/optopenhash`, unit-tested against hand-computed values.
- **Two-tier benchmark harness:** `kotlinx-benchmark` (allOpen wired) across targets +
  a JVM raw-JMH module for `-prof gc`. A trivial map (e.g. wrap `HashMap`) proves the
  harness end-to-end and establishes the **baseline numbers** for stdlib on each target.
- **Differential-oracle fixtures:** scripts that run `sternma/optopenhash` (and later
  `opthash-rs`) over seeded op-sequences and emit golden `(op, key, expected)` traces
  committed as test resources; a JVM replay harness that diffs our structure against them.
- **Property/oracle test scaffolding:** Kotest property tests (KMP) for `insert/get/
  remove/contains` vs a reference `LinkedHashMap` model.
- Written **success metrics** (e.g. "primitive map ≥ N× over stdlib at LF 0.9 on JVM;
  ≤ X bytes/entry; elastic insert win at LF 0.99; never worse than stdlib by >Y% on
  lookup").

**Decision points:** DP-2 (target set), DP-3 (build infra: adopt Spine `config`
conventions vs lightweight), DP-4 (benchmark framework two-tier), DP-5 (test stack in
common: `kotlin.test` runner + `kotest-assertions-core`).

### Phase 1 — First fast structure (the "much faster than stdlib" proof) — *Strategy A*

**Goal:** ship one structure that *demonstrably* beats stdlib, and prove the storage
foundation (control bytes + SWAR + primitive specialization).

**Deliverables**
- A **SwissTable/ScatterMap-style open-addressing map**: `ByteArray` control bytes
  (7-bit fingerprint + empty/deleted sentinels), single-`Long` 8-byte SWAR group scan,
  LF ~0.875, backward-shift or tombstone deletion (DP-8).
- **One hand-written primitive specialization** (DP-6 picks which — recommend
  `Long`→`V` or `Int`→`Int`, the highest-impact cases) **plus** the generic variant.
- Benchmarks vs stdlib on every target; vs fastutil/HPPC on JVM as a *reference ceiling*
  (not a success gate). Memory footprint reported.

**Decision points:** DP-1 (strategy/ordering — gates this whole phase), DP-6
(which specialization first; build-vs-reuse androidx.collection), DP-10 (API depth:
lean surface now vs full `MutableMap`).

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

**Decision points:** DP-8 (deletion: tombstones vs none + compaction policy), DP-9
(fixed-capacity vs dynamic-growth-by-rebuild), DP-11 (expose probe metrics publicly?).

### Phase 3 — `ElasticHashTable` (non-greedy, the namesake)

**Goal:** the project's namesake structure, with the 3-case non-greedy insertion and
the `φ` injection.

**Deliverables**
- Clean-room `ElasticHashTable` from §4 with the `δ/2` / `0.25` thresholds; per-level
  salt re-randomization; differential + probe-count tests.
- Benchmark confirming `O(1)` amortized / `O(log 1/δ)` worst-case probe behavior, and
  the documented lookup-at-scale regression so we're not surprised by it.

**Decision points:** DP-12 (threshold-constant tuning — the TS gist warns the
constants are approximate; pin via experiment).

### Phase 4 — Practical hardening

**Goal:** make the structures production-usable.

**Deliverables:** dynamic growth (rebuild) where chosen; `MutableMap` adapters; more
primitive specializations (KSP codegen — DP-7); pluggable hashers + good default
finalizer; optional JVM-actual SIMD scan behind a capability flag + feature test
(authoritative path stays SWAR); call-site-equals JVM optimization.

**Decision points:** DP-7 (hand-written vs KSP codegen for the specialization matrix),
DP-13 (concurrency stance — single-threaded v1, document explicitly).

### Phase 5 — Validation & release

**Goal:** publish with numbers that survive external scrutiny.

**Deliverables:** full benchmark matrix (the §1.4 grid) vs `HashMap` + fastutil/HPPC +
Eclipse + Agrona at LF 0.5/0.75/0.9/0.95/0.99; memory footprint; reproducibility
metadata (JDK/OS/CPU pinned, seeds committed, JSON outputs); README with the honest,
baseline-qualified positioning; Maven Central publication.

---

## 5. Consolidated decision points

| # | Decision | Recommendation | Why it matters |
|---|---|---|---|
| **DP-1** | **Strategy / phase ordering.** A) speed-first (SwissTable map Phase 1, elastic later); B) elastic-first (paper structures Phase 1); C) both in Phase 1. | **✅ DECIDED: A** | Determines whether the project's hard requirement is provable early, and how we position elastic. |
| DP-2 | Target set. | JVM + Native + Wasm + JS, with JVM/Native as the performance-credible tier; JS/Wasm = correctness + best-effort | "Multiplatform" confirmed; sets expectations honestly |
| DP-3 | Build infra: adopt full Spine `config` conventions (buildSrc/module.gradle.kts, detekt, Kover) vs keep the lightweight `gradle init` setup | adopt Spine conventions incrementally | consistency with the org; static analysis required by guidelines |
| DP-4 | Benchmark framework | two-tier: `kotlinx-benchmark` (KMP) + raw JMH (JVM profiling) | only credible KMP option; JMH for authoritative JVM/GC numbers |
| DP-5 | Common test stack | `kotlin.test` runner + `kotest-assertions-core` (KMP); JVM suites keep `Spec`/`@DisplayName` conventions | reconciles KMP commonTest with Spine's Kotest/JUnit5 policy |
| DP-6 | First specialization + build-vs-reuse | **build from scratch**, hand-writing `Long→V` or `Int→Int`; use `androidx.collection` ScatterMap as a *design reference and benchmark bar*, **not a dependency** | depending on androidx couples us to an Android-branded artifact whose non-Android KMP targets are *experimental* — against the "avoid immature dependencies" goal; we still validate that we match/beat it |
| DP-7 | Specialization matrix mechanism | hand-written first, **KSP codegen** when >2–3 types | avoids source duplication at scale |
| DP-8 | Deletion policy | tombstones + compaction threshold (or backward-shift for the SwissTable map) | off-paper; affects probe-path correctness |
| DP-9 | Growth policy | rebuild-into-larger (amortized), document cost | structures are fixed-(n,δ); no in-place reorder |
| DP-10 | API depth | lean `OpenAddressingMap` now; `MutableMap` adapter Phase 4 | ecosystem compatibility vs surface area |
| DP-11 | Expose probe metrics | yes, behind a debug/inspection API | turns paper bounds into user-visible guarantees |
| DP-12 | Elastic threshold constants | pin via experiment, cross-check oracles | the public ports flag these as approximate |
| DP-13 | Concurrency | single-threaded v1, documented | thread-safe variant is a separate track |

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
- **Concurrency:** out of scope v1, stated explicitly.

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

## 8. Primary sources

- Farach-Colton, Krapivin, Kuszmaul. *Optimal Bounds for Open Addressing Without
  Reordering.* arXiv:2501.02305 (2025) — normative for sizing, thresholds, `φ`.
- Oracles: `sternma/optopenhash` (Python, MIT), `aaron-ang/opthash-rs` (Rust, Apache-2.0).
- Precedent/bar: `androidx.collection` `ScatterMap`/`IntIntMap` (KMP, Apache-2.0).
- Wall-clock evidence: `joshuaisaact/elastic-hash` (Zig) benchmark write-up.
- Java SwissTable + SWAR-vs-SIMD: `bluuewhale/hash-smith` and the author's deep-dives.
