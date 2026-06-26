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

**Authoritative JVM time** (Apple Silicon, Corretto 17, 5×5, single fork; `Long → Long`):

| op (avg ns/op)         | size | HashMap    | SwissLongMap | LongLongMap | note                    |
|------------------------|------|------------|--------------|-------------|-------------------------|
| **lookupHitShuffled**  | 10K  | 31,349     | 37,198       | 35,628      | HashMap fastest in-cache |
| **lookupHitShuffled**  | 1M   | 20,858,131 | 19,877,566   | 16,761,098  | **LongLong ~1.24×**     |
| insertAllPresized      | 10K  | 67,065     | 84,701       | 57,574      | **LongLong ~1.16×**     |
| insertAllPresized      | 1M   | 9,693,577  | 46,035,371   | 9,895,434   | LongLong ≈ HashMap; Swiss 4.7× slower |
| lookupHit (sequential) | 1M   | 3,320,950  | 15,845,754   | 18,581,928  | HashMap (locality artifact) |

**Honest verdict.** The win is **memory-first**: `LongLongMap` retains **4.7× less heap** than
`HashMap` (and `SwissLongMap` 2.3× less). On **time** the fully-primitive `LongLongMap` is
**competitive-to-modestly-faster** — ~1.24× on random-access lookup at 1M, ~1.16× on in-cache
presized insert, a tie at 1M insert — but **loses in-cache lookup** (~1.14×) and never reaches
the **≥2× the success criterion targets**. Why no 2× on time: the JIT escape-analyses away
`HashMap`'s *temporary* lookup-time key box (so boxing elimination is a memory win, not a
lookup-speed win), and in-cache the SWAR + `fmix64` arithmetic offsets the gains. `SwissLongMap`'s
**value boxing** makes its inserts ~4.7× slower than `LongLongMap` at 1M (allocation/GC churn) —
a concrete argument that object-value specializations pay a real price and the primitive-value
form is the one to lead the speed story. **Net: Phase-1 "beats stdlib" is met decisively on
memory and modestly on time-at-scale for the primitive map; the ≥2× lookup-time target is not
met and should be retired or re-scoped to memory + at-scale time.**

## Follow-ups (not done this phase)

- **Version gate:** bump `version.gradle.kts` above base before opening the PR (currently
  `1.0.0-SNAPSHOT-002`; non-breaking) via the `bump-version` skill.
- **In-cache hot-path tuning (optional):** in-cache, the maps do more arithmetic per op
  (`fmix64`'s two multiplies + the SWAR broadcast) than `HashMap`. A cheaper finalizer or an
  inlined default-hasher path may close the small in-cache lookup gap; unlikely to reach 2×.
- **KSP generator (DP-7):** `SwissLongMap` and `LongLongMap` share everything but value storage
  and the absent-key protocol — that near-duplication is exactly what the generator should emit
  from one template, with these two as the prototype inputs.
- **Memory metric note:** footprint is measured exactly with JOL (`MemoryFootprintSpec`); a
  `-prof gc` allocation-rate tier (DP-4) was not needed to establish the win and remains optional.
