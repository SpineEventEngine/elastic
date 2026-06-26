# Optimal Open-Addressing Hash Tables on the JVM — Project Initiation Report

**Proposal:** a Kotlin library implementing the *elastic hashing* and *funnel hashing* data
structures from Farach-Colton, Krapivin & Kuszmaul, *Optimal Bounds for Open Addressing Without
Reordering* (arXiv:2501.02305, Jan 2025).

**Status:** draft for kickoff / scoping
**Audience:** implementing engineers, reviewers

---

## 1. Why this library

The paper resolves a question open since Yao (1985): for open-addressing hash tables **without
reordering** (a key, once placed, never moves), how fast can probing be? The long-assumed answer —
that greedy uniform probing is optimal — turns out to be wrong. Two new structures beat it:

| Structure                 | Strategy   | Amortized expected probes | Worst-case expected probes | Notes                                                    |
|---------------------------|------------|---------------------------|----------------------------|----------------------------------------------------------|
| Classical uniform probing | greedy     | Θ(log 1/δ)                | Θ(1/δ)                     | the prior "optimal", now disproven                       |
| **Elastic hashing**       | non-greedy | **O(1)**                  | **O(log 1/δ)**             | breaks the amortized log barrier                         |
| **Funnel hashing**        | greedy     | O(log 1/δ)                | **O(log² 1/δ)**            | w.h.p. O(log² 1/δ + log log n); disproves Yao for greedy |

Here **δ is the fraction of *empty* slots** (δ = 1 − load factor). At 90% load, δ = 0.1; at 99%, δ =
0.01. The gains are dramatic precisely where classical tables fall apart: **very high load factors
**.

**Honest positioning.** These bounds are about *probe counts*, not wall-clock time. In practice,
SwissTable-style maps (abseil, Rust `hashbrown`, JVM equivalents) win on raw throughput at moderate
load because of SIMD + cache locality. Independent benchmarks (the Zig port below) show elastic
hashing winning on *insert/delete at ~99% load* but paying a cache penalty from its probe-ordering
at lower load. **The defensible value proposition for this library is predictable, bounded behavior
at high load factor and memory pressure** — real-time systems, fixed-memory budgets,
adversarial-resistant tables — not "fastest general-purpose map." This should frame the README, the
benchmarks, and the success criteria.

There is currently **no faithful JVM implementation of these data structures.** The single Kotlin
repo that exists (`m-fire/platform-optimal-hash`) implements only the underlying *hash primitives* (
tabulation/universal hashing), not the table structures. This is a genuine gap.

---

## 2. Algorithm summary (implementation-oriented)

Both structures share: a fixed target capacity `n` and target `δ`; an array partitioned into *
*levels of geometrically decreasing size**; a per-level salt so probe paths decorrelate across
levels; and the no-reordering invariant.

### 2.1 Elastic hashing (non-greedy)

- Partition the slot array into levels `A₁, A₂, …, A_⌈log₂ n⌉` with **geometrically halving
  capacities** (`A₁` holds a constant fraction of `n`, each subsequent level ≈ half the previous).
- Probing uses a **two-dimensional sequence** over (level pair, attempt): an injection φ: ℕ² → ℕ
  orders attempts so that work is interleaved across two adjacent levels `Aᵢ, Aᵢ₊₁`.
- Insertion is **non-greedy**: it does *not* always take the first free slot. Based on how full `Aᵢ`
  and `Aᵢ₊₁` currently are (compared against per-level free-fraction thresholds), the insert either
  places in `Aᵢ` within a bounded probe budget, or defers to `Aᵢ₊₁`. This decoupling is what yields
  O(1) amortized and O(log 1/δ) worst-case expected.
- **Reference for exact constants/thresholds:** §4 of the paper + `sternma/optopenhash` (clearest
  direct translation).

### 2.2 Funnel hashing (greedy)

- α = Θ(log 1/δ) levels, each subdivided into fixed-size **buckets** of size β = Θ(log 1/δ).
- A key maps to **exactly one bucket per level**. Insert greedily: try the level-1 bucket; if full,
  fall to the level-2 bucket; and so on. Overflow does *not* spill sideways into other buckets —
  only downward to the next level.
- A **special overflow array `A_{α+1}`**, split into two parts: a primary array probed by a
  uniform/group sequence, and a small secondary fallback (two-choice / bucketed) for the residual.
  Sizing of α, β and the two overflow parts is derived from δ.
- Simpler to implement correctly than elastic; very consistent. **Recommended as the first structure
  to build.**
- **Reference:** §5 of the paper + `sternma/optopenhash` + `aaron-ang/opthash` (`FunnelHashMap`).

### 2.3 Deletion and resizing (not in the paper)

The paper defines insert and search only. Every practical port adds:

- **Deletion via tombstones** (logical deletion preserving probe sequences). This is an *extension*,
  not from the paper; it complicates probe-path reasoning and requires periodic compaction/rebuild.
  Decide tombstone policy early.
- **Dynamic growth.** The structures are defined for a fixed (n, δ). Because no reordering is
  allowed in place, growth means **rebuilding into a larger structure** (`opthash` grows dynamically
  this way; `sternma/optopenhash` stays fixed-capacity). Pick a stance and document the amortized
  rebuild cost.

---

## 3. Proposed library design (Kotlin / JVM)

### 3.1 Public API

Mirror a subset of `MutableMap` so the types are drop-in-ish, while exposing the knobs that matter:

```kotlin
interface OpenAddressingMap<K, V> {
    val size: Int
    fun put(key: K, value: V): V?      // no reordering of existing entries
    fun get(key: K): V?
    fun containsKey(key: K): Boolean
    fun remove(key: K): V?             // tombstone-based
    fun clear()
}

class FunnelHashMap<K, V>(
    expectedCapacity: Int,
    delta: Double = 0.10,              // target empty fraction (=> 90% load)
    hasher: Hasher<K> = Hasher.default(),
) : OpenAddressingMap<K, V>

class ElasticHashMap<K, V>(
    expectedCapacity: Int,
    delta: Double = 0.10,
    hasher: Hasher<K> = Hasher.default(),
) : OpenAddressingMap<K, V>

fun interface Hasher<K> {
    fun hash(key: K): Long
}   // pluggable; per-level salting applied internally
```

Decision to make at kickoff: implement `MutableMap<K, V>` fully (entrySet/iterator/etc.) for
ecosystem compatibility, or keep a lean surface initially and add the `MutableMap` adapter in a
later phase.

### 3.2 Storage layout

Follow the SwissTable-derived layout the Rust reference uses, adapted to the JVM:

- **Control bytes** (`ByteArray`): one per slot, holding a 7-bit fingerprint (top bits of the hash)
  plus sentinel values for empty (`0x00`) and tombstone (`0x80`). Scanned to skip non-matching slots
  cheaply.
- **Entries:** start with `arrayOfNulls<Any?>` for keys and values (generic, boxed). Plan
  primitive-key specializations (`LongKeyedFunnelMap`, etc.) as a later phase — generic erasure +
  boxing is the JVM tax versus the Rust/Zig ports.
- **Level descriptors:** small struct array mapping each level/bucket to its offset and capacity
  into one backing arena, to keep allocations down (the `opthash` "single Arena per map" pattern).
- **Per-level salt:** re-randomize the hash per level to decorrelate probe paths.

### 3.3 Scanning / SIMD

- **Phase 1:** scalar control-byte scan, optionally SWAR (8 control bytes packed in a `Long`,
  branch-free match via bit tricks). Portable, no incubator dependency.
- **Later:** the JDK **Vector API (`jdk.incubator.vector`)** for SIMD control-byte comparison,
  behind a feature flag and capability check. It is still an incubator module — treat as an
  optimization, not a baseline, and keep the scalar path authoritative.

### 3.4 Hashing

Default to a good, fast non-cryptographic finalizer over `Object.hashCode()` (e.g., a
murmur/wyhash-style mix), with the `Hasher` interface allowing override. Note that the .NET
reference runs MurmurHash3 over everything; the JVM's `hashCode()` contracts plus a mix step are
usually sufficient and avoid per-key allocation.

---

## 4. Scope and phasing

**Phase 0 — Spec & harness.** Lock the API, the (n, δ) → sizing formulas (ported from the paper and
cross-checked against `sternma/optopenhash`), and a JMH benchmark + correctness-oracle harness.
Define success metrics.

**Phase 1 — Correct & generic (MVP).** `FunnelHashMap` first (greedy, simpler), then
`ElasticHashMap`. Fixed capacity, tombstone deletion, full test suite. Goal: provably correct,
readable, matches `java.util.HashMap` semantics.

**Phase 2 — Practical.** Control-byte/fingerprint layout + SWAR scan; pluggable hashers with
per-level salting; dynamic growth via rebuild; `MutableMap` adapter.

**Phase 3 — Performance.** Vector API SIMD path (flagged); primitive-key specializations;
cache-line-aligned buckets; allocation tuning.

**Phase 4 — Validation & release.** Full benchmark matrix vs `HashMap`, fastutil, Koloboke, Eclipse
Collections, Agrona — at load factors 0.50 / 0.75 / 0.90 / 0.95 / 0.99. Publish with the honest
positioning from §1.

---

## 5. Testing & verification

- **Differential / oracle testing** against `java.util.HashMap` (same op sequence → same observable
  results).
- **Property-based testing** (jqwik or Kotest Property) over randomized insert/get/remove/contains
  sequences and capacities.
- **Bound verification as regression metrics:** instrument probe counts and empirically confirm O(1)
  amortized / O(log 1/δ) worst-case (elastic) and O(log² 1/δ) worst-case (funnel) at increasing
  load. This both validates correctness of the sizing constants and turns the paper's claims into CI
  assertions.
- **High-load stress:** correctness and no-infinite-probe behavior at 95–99.x% load.
- **Concurrency:** out of scope for v1 (single-threaded). State this explicitly; thread-safe /
  concurrent variants are a separate future track.

---

## 6. Key risks & open questions

1. **JVM performance ceiling.** Boxing, generic erasure, and the GC mean wall-clock will trail
   Rust/Zig/C++. Set expectations: the win is *bounded worst-case at high load*, demonstrated by
   probe-count metrics, not raw ns/op leadership.
2. **Probe-ordering vs cache locality.** The Zig port found elastic's probe ordering costs ~25% on
   lookup at larger sizes due to cache effects. Expect the same; measure it; consider the funnel
   structure as the better default for many workloads.
3. **Deletion & resizing are off-paper.** Tombstone accumulation degrades probe paths;
   rebuild-on-grow has amortized cost. These need their own design + tests.
4. **Vector API instability.** Incubator module; keep it optional.
5. **Licensing / clean-room.** References are MIT (`optopenhash`) and Apache-2.0 (`opthash`). Decide
   between a clean-room reimplementation from the paper (cleanest IP story) versus a
   port-with-attribution. The arXiv paper itself is the safest primary source for the algorithm.

---

## 7. Reference implementations across languages

The user-supplied repos are marked ★. Use them as cross-language oracles and architectural
references; the paper is the normative source.

### Rust — best engineering reference

- ★ **`aaron-ang/opthash`** and ★ **`aaron-ang/opthash-rs`** — these are the **same project** (
  `opthash` is the published crate / PyPI package name; `opthash-rs` is the source repo — identical
  READMEs). The most complete implementation found: `ElasticHashMap` and `FunnelHashMap` mirroring
  `std::collections::HashMap`, single-arena allocation, 7-bit fingerprint control bytes, **SIMD**
  control-byte scans, tombstone accounting, per-level salt re-randomization, `foldhash` default,
  zero-allocation `new()` with dynamic growth, a `reserve_fraction` headroom knob, Python bindings,
  CI + CodSpeed benchmarks. Apache-2.0. **Primary reference for the storage layout and growth
  strategy (§3).**

### Python — best algorithmic reference

- ★ **`sternma/optopenhash`** (PyPI `optopenhash`) — `ElasticHashTable` + `FunnelHashTable`,
  `(capacity, delta)` constructor, `insert`/`search`. The clearest, most faithful direct translation
  of the paper; **primary reference for the algorithm and sizing constants (§2).** MIT.
- ★ **`MWARDUNI/ElasticHashing`** — supplied reference. ⚠️ **Was not publicly reachable at the time
  of writing (GitHub returned 404 — possibly private, renamed, or removed).** Confirm access before
  relying on it; it could not be reviewed for this report.
- *(additional)* **`jorgik1/elastic-hash`** (PyPI `open-elastic-hash`) — fuller `insert`/`get`/
  `remove`/`contains` API with a test suite; useful as a second Python oracle.

### .NET

- ★ **`devintegeritsm/KrapivinHashTable`** (C#, NuGet) — ⚠️ **important caveat:** despite the
  Krapivin name, this is **not** a faithful elastic/funnel implementation. It is a **segmented
  quadratic-probing** table — `(n²+n)/2` probing, MurmurHash3, fixed-size segments for cache
  locality, logical (tombstone) deletion, insertions blocked above 90% load. Good as an *
  *API-ergonomics reference** (`Insert`/`TryGet`/`Get`/`Delete`, `initialCapacity`/`segmentSize`/
  `comparer` constructor) and as evidence of practical design choices, **but do not use it as a
  structural reference** for the paper's algorithms.

### TypeScript

- ★ **`lifeart/edfb82e3dd3249a503f37b8059b0c164`** (Gist, single-file `index.ts`, last updated March
  2025) — a self-contained `ElasticHashMap<K, V>` implementing the **full JavaScript `Map` interface
  ** (`set`/`get`/`has`/`delete`/`clear`/`keys`/`values`/`entries`/`[Symbol.iterator]`). Implements
  **elastic hashing only** (no funnel). Notable design choices directly relevant to our API work:
  geometric level partitioning (`partitionLevels`, each ≈ half the prior); per-level random salts
  driving a **double-hashing** probe sequence; the three-case non-greedy insertion with explicit
  thresholds (probe `Aᵢ` when it has > δ/2 free and `Aᵢ₊₁` has > 25% free; skip `Aᵢ` when ≤ δ/2
  free; fall back to greedy on the last level / when `Aᵢ₊₁` is nearly full); **tombstone deletion**;
  an **insertion-order doubly-linked list** so iteration matches `Map` semantics; dynamic *
  *resize-on-load-factor** with full rehash; and per-JS-type key hashing (
  number/bigint/string/boolean/symbol/object via a `WeakMap` id table). Caveats: it's a
  proof-of-concept gist (0 stars, AI-assisted write-up), and its author flags that the probe-budget
  constant is approximated (`c = 2` where the theory needs a larger constant), so treat the exact
  thresholds as illustrative rather than tuned. **Best reference for the `MutableMap`-compatible API
  surface, insertion-order iteration, and a readable single-file elastic insertion path** — and a
  useful cross-check for our threshold logic in §2.1. It also cites `MWARDUNI/ElasticHashing` as a
  source, the same (currently unreachable) repo noted above.

### Go (additional cross-references)

- **`KarpelesLab/elastichash`** — both structures, with benchmarks against Go's built-in map.
- **`bdragon300/elastic-funnel-hash`** — PoC of both; its README has a clear description of the
  funnel bank/bucket/overflow layout (the A′, B, C parts).

### Zig (additional — best for the optimization phase)

- **`joshuaisaact/elastic-hash`** — the most performance-serious port: SIMD bucket scanning, 8-bit
  fingerprints, batch insertion, the φ priority function, comptime specialization, benchmarked
  against abseil `flat_hash_map`. Source of the realistic high-load vs low-load performance findings
  cited in §1 and §6.

### C++ (additional)

- **`ascv0228/elastic-funnel-hashing`**, *
  *`andrewyang03/OpenAddressingWithoutReorderingImplementation`** — straightforward C++17 ports of
  both structures.

### Kotlin (existing — not a structural reference)

- **`m-fire/platform-optimal-hash`** — Kotlin Multiplatform, but implements only the **hash
  primitives** (`elasticTabulationHashNative`, `funnelUniversalHashNative`), **not** the table data
  structures. Flagged experimental/AI-generated. Confirms the gap this project fills.

---

## 8. Primary source

Martín Farach-Colton, Andrew Krapivin, William Kuszmaul. *Optimal Bounds for Open Addressing Without
Reordering.* arXiv:2501.02305 (2025). https://arxiv.org/abs/2501.02305 — **normative reference for
§2 sizing, thresholds, and the φ injection.**
