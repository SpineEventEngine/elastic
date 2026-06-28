# Phase 1 — `SwissLongMap<V>` (the "faster than stdlib" proof)

**Status:** implementation + tests + review + authoritative JVM benchmark complete.
Branch `phase-1`. See [implementation plan](elastic-hashing-implementation-plan.md) §Phase 1.

## What shipped

- `io.spine.elastic.SwissLongMap<V>` — a SwissTable/`hashbrown`-style open-addressing map
  implementing `OpenAddressingLongMap<V>`. Primitive `Long` keys (no key boxing); object
  values in `Array<Any?>`.
- `io.spine.elastic.LongLongMap` — the **fully primitive** `Long → Long` specialization
  (values in a `LongArray`, neither key nor value boxed). fastutil-style `absentValue`
  default-return API (no `Long?`). The true primitive-vs-primitive peer of `HashMap<Long,Long>`.
- `io.spine.elastic.internal.Swar` — control-word SWAR primitives + control-byte/group layout.
- `io.spine.elastic.internal.Capacity` — shared power-of-two / `7/8`-load sizing, used by both
  maps (the value-type-independent part; the rest is the near-duplication DP-7 will generate).
- Tests: `SwarSpec`, `SwissLongMapSpec` + `…PropertiesSpec`, `LongLongMapSpec` +
  `…PropertiesSpec` (differential vs `LinkedHashMap`/`HashMap` oracles, clustered + wide
  domains), and a JVM-only `MemoryFootprintSpec` (JOL retained-size guard). Green on JVM +
  host Native. Coverage: Swar/Capacity 100%, both maps ~98% line / 93% branch.
- Benchmarks: `SwissLongMapBenchmark`, `LongLongMapBenchmark`, and `StdlibHashMapBenchmark`,
  each with a `lookupHitShuffled` (random-access) variant (see "Benchmark lesson").

## Memory result (the decisive win)

Exact retained heap (JOL `GraphLayout.totalSize`), each map pre-sized for `n` in its own
units, at the `7/8` max load of a `2^18`-slot table (`n = 229,376`):

| bytes/entry | `HashMap<Long,Long>` | `SwissLongMap<Long>` | `LongLongMap` |
|---|---|---|---|
| retained heap | **89** | **38** (2.3× smaller) | **19** (**4.7× smaller**) |

`HashMap` pays a `Node` object + two boxes per entry; `SwissLongMap` drops the key box and the
node; `LongLongMap` drops the value box too. This is the genuine, defensible Phase-1 advantage
that the time-only benchmarks miss — and it shows up as a real insert-speed win for `LongLongMap`
(far less allocation/GC) in the time numbers below.

## Key implementation decisions (made during the work)

- **Packed-`Long` control words, not a `ByteArray`.** Control bytes live eight-to-a-`Long`
  in a `LongArray`; a group load is a single array read. The first cut used a `ByteArray`
  with a per-probe 8-iteration byte-assembly loop and was ~3–4× slower — the packed layout
  is the portable way to get the single-load SWAR the plan intended.
- **Carry-free SWAR match.** `matchByte` uses `((x & 0x7F…7F) + 0x7F…7F) | x | 0x7F…7F`,
  not the borrow-prone `(x - 0x01…01) & ~x`. The borrow trick false-matches a zero lane's
  neighbour (caught by `SwarSpec`); carry-free is exact for every target, so one routine
  serves fingerprint, empty, and deleted matches.
- **Group-*aligned* triangular probing.** Eliminates the abseil cloned-tail trick and the
  alignment ambiguity the design critique flagged; the triangular group stride is a full
  permutation on a power-of-two group count, so the probe always reaches an empty lane.
- **Simple tombstones (DP-8).** `remove` always writes `DELETED`; reclaimed only on rebuild.
  `growthLeft` counts full+deleted against `7/8` load, so an EMPTY lane always exists and
  `find` always terminates. (The abseil convertible-to-empty optimization was rejected for
  Phase 1 — easy to mis-port into chain truncation.)
- **Single-threaded (DP-13).** Plain `var tables` holder; no thread-safety claimed. Write
  order (key, value, *then* control byte) and the one-field-swap resize are kept as seams so
  the Phase-4 SWMR variant is a derivation.
- **KSP deferred (DP-7).** Hand-written `Long → V` only this phase, per the user's call.

## Benchmark lesson (important, durable)

The first comparison used **sequential keys `0..n` inserted and looked up in order**. That is
an accidental *best case for `java.util.HashMap`*: its nodes are heap-allocated in key order
and traversed in key order, so the lookup loop streams sequential memory. Open addressing
scatters by hash, so the same access pattern is a *worst case* for it. Result: Swiss looked
~3× slower — a benchmarking artifact, not a real loss.

Adding a **shuffled (random-order) lookup** removes the bias: `HashMap`'s lookup collapses
(it now pointer-chases random nodes) while Swiss is unchanged. Random-order access is the
realistic case, so `lookupHitShuffled` is the fair gate — always include it here.

**Authoritative JVM time** (Apple Silicon, Corretto 17, 5×5; *fair* methodology — every lookup
sums into one `Blackhole` sink, every map pre-sized in its own units; `Long → Long`). Absolute
ns/op drift run-to-run with machine load; the within-run *ratios* are the signal.

| op (avg ns/op)        | size | HashMap    | SwissLongMap | LongLongMap | LongLong vs HashMap |
|-----------------------|------|------------|--------------|-------------|---------------------|
| **lookupHitShuffled** | 10K  | 37,204     | 38,623       | 34,007      | ~1.1× faster        |
| **lookupHitShuffled** | 1M   | 16,555,992 | 16,254,257   | 8,138,515   | **~2.0× faster**    |
| insertAllPresized     | 10K  | 82,847     | 79,438       | 53,800      | ~1.5× faster        |
| insertAllPresized     | 1M   | 9,404,673  | 25,394,448   | 7,546,444   | ~1.25× faster       |

(Sequential `lookupHit` is omitted — it is the `HashMap`-favouring node-locality artifact noted
above. `SwissLongMap`'s 1M insert is ~2.7× slower than `LongLongMap` because boxing its object
values churns the allocator.)

**Honest verdict.** Two clean wins. **Memory:** `LongLongMap` retains **4.7× less heap** than
`HashMap` (`SwissLongMap` 2.3× less) — deterministic (JOL). **At-scale time:** fairly measured,
`LongLongMap` is **~2.0× faster than `HashMap` on random-access lookup at 1M** and ~1.25–1.5× on
presized insert; in-cache (10K) the lookup margin is smaller (~1.1×), since the SWAR + `fmix64`
arithmetic offsets the locality gain there. The opt-in `Fibonacci` hasher pushes lookup further
(~2.8× at 1M). `SwissLongMap`'s boxed values cost it on insert, so the primitive-value form leads
the speed story. **Net: "beats stdlib" holds decisively on memory and on at-scale time; the
original "≥2× everywhere" target stays re-scoped to "memory + at-scale time" because the in-cache
lookup margin is modest, not 2×.**

## Follow-ups (not done this phase)

- **Version gate — DONE.** `version.gradle.kts` bumped `1.0.0-SNAPSHOT-002 → -003` (non-breaking).
- **In-cache hot-path tuning — DONE (2026-06).** Quantified that `fmix64` (two multiplies) is
  ~26-28% of lookup time. Added the opt-in `LongHasher.Fibonacci` (single-multiply Knuth hash);
  the default stays `fmix64` (adversarial-safe). With `Fibonacci`, `LongLongMap` random-access
  lookup is **24,536 ns @10K / 5,992,441 ns @1M** — ~26-28% faster than `fmix64`, i.e. ~1.5×
  (10K) to ~2.8× (1M) faster than `HashMap`.
- **Dedup / codegen (DP-7) — DEFERRED (2026-06).** The two maps share everything but value
  storage + the absent-key protocol, but at two specializations the duplication is small and
  `Swar`/`Capacity` already factor the core. A shared abstract base is viable but moderate-risk
  (type-erased value storage, a write-order callback, detekt function-count). Decision: keep
  both hand-written; revisit base-vs-codegen when a *3rd* specialization appears. See plan DP-7.
- **Memory metric note:** footprint is measured exactly with JOL (`MemoryFootprintSpec`); a
  `-prof gc` allocation-rate tier (DP-4) was not needed to establish the win and remains optional.
