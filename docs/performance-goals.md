# Performance goals & success metrics

This document fixes what "much faster than the standard data structures" means
for this project, so success is measurable rather than asserted. It is the
yardstick for the project's benchmark matrix.

## Honest positioning

The baseline we commit to beating is the **platform standard library**
(`java.util.HashMap` on the JVM; the Kotlin/Native `HashMap`), **not** the
best-in-class native maps (abseil / Rust `hashbrown`) — those are out of reach
from common Kotlin (no SIMD, no off-heap). The win comes from
**open addressing + primitive specialization** (no boxing), not from any
algorithmic novelty.

The speed claim is **gated on primitive keys**. For boxed/object keys an
open-addressing map at moderate load may only break even with `HashMap`, whose
high-bit spread and treeify are a real safety net.

**Reframed by Phase 1 measurement (2026-06): the win is layout-driven.** Storing keys
and values in compact primitive arrays — no per-entry node, no boxes — both shrinks
the footprint and, *at scale*, cuts cache misses versus `HashMap`'s scattered
node-and-box graph, so the same compactness pays off twice. The fully-primitive
`Long → Long` map retains **~4.7×** less heap than `HashMap<Long, Long>` **and** is
**~2× faster on random-access lookup at 1M** (fairly measured). In-cache (small tables
that fit L1/L2) the margin is small — the SWAR + `fmix64` arithmetic roughly offsets
the saved indirection. The Phase 1 criteria below are therefore stated as **memory +
at-scale time**, the regimes where the layout advantage is decisive, rather than a
single uniform lookup-speed multiple.

## Phase 0 baseline snapshot (indicative)

Captured with the **`smoke`** configuration (1 warmup, 1 iteration) — rough, not
publication-grade; authoritative baselines come from the `main` configuration
(5×5) on pinned hardware. Boxed `HashMap<Long, Long>`, Apple Silicon, Corretto 17.

| Op | size | JVM (ns/op) | Native macosArm64 (ns/op) |
|---|---|---|---|
| `lookupHit` | 10 000 | ~30 100 | ~98 900 |
| `lookupHit` | 1 000 000 | ~6 631 000 | ~17 750 000 |
| `insertAllGrowing` | 10 000 | ~87 700 | ~338 300 |
| `insertAllGrowing` | 1 000 000 | ~19 588 000 | ~42 040 000 |

The insert rows above are the **default-capacity** (`insertAllGrowing`) variant,
which folds in resize/rehash cost. The fairness baseline a primitive map is
compared against on steady-state insert speed is **`insertAllPresized`** (map
pre-sized in its own units) — measured separately so a future map cannot meet the
insert target merely by avoiding resize. The Native stdlib map is ~3× slower than
the JVM's here, confirming it is the more beatable baseline.

## Success criteria

**Phase 1 — primitive `Long → Long` / `Long → V` maps (memory + at-scale time):**
- **Memory — the primary gate.** The fully-primitive `Long → Long` map retains
  **≥ 2× less heap per entry** than `HashMap<Long, Long>`; the object-value
  `Long → V` map is also clearly smaller. Report exact bytes/entry (JOL, each map
  pre-sized in its own units, at equal occupancy). *Measured: 19 vs 89 bytes/entry
  (**4.7×**) for `Long → Long`; 38 vs 89 (**2.3×**) for `Long → V`.*
- **Time at scale.** At ≥ 1M entries (out of cache), on **random-access** lookup
  and on **presized insert**, the primitive map is **≥ as fast as `HashMap`**
  (target ≥ 1.2×). *Measured (fair methodology): ~2.0× random-access lookup at 1M;
  ~1.25–1.5× presized insert; in-cache (10k) the lookup margin is ~1.1×.*
- **No material regression.** Never slower than stdlib on any core op at any size;
  in-cache margins are small (~1.1×, near parity) but not a regression.
- **Optional faster hasher.** `LongHasher.Fibonacci` (single multiply) trades
  adversarial-key robustness for ~26–28 % lower hash cost (a stable, same-run figure);
  with it the primitive map's random-access lookup is ~1.5× (10k) to ~2.8× (1M) faster
  than `HashMap`. The default `fmix64` stays the safe choice for untrusted keys.
- **Native.** The same common code runs on Kotlin/Native; report per-platform
  numbers (the Native `HashMap` is the more beatable baseline).
- **Retired:** the former "≥ 2× faster on `lookupHit`" target — undercut by JVM
  escape analysis (see *Honest positioning*) and superseded by the gates above.

**Phase 2–3 — funnel / elastic (the bounded-worst-case specialists):**
- Empirically confirm the paper's probe-count bounds at rising load
  (`O(log² 1/δ)` funnel; `O(1)` amortized / `O(log 1/δ)` worst-case elastic) and
  assert them as CI regression metrics.
- Demonstrate the **insert/delete win at very high load (≈ 0.99)**; report the
  **lookup-at-scale cost** honestly (the φ ordering can regress lookups at large
  sizes — this is expected, not a bug).

**Phase 4 — `SingleWriterSwissLongMap` (one writer, lock-free readers):**
- **Correctness is the gate, not speed.** Linearizability of the key-addressed
  operations (`get`/`containsKey`/`put`/`remove`/`clear`) verified by Lincheck in
  stress and model-checking modes, over three configurations: empty start, a
  pre-filled map whose first parallel insert publishes a rebuilt table mid-race,
  and a constant-hash map where every probe walks one shared chain. The suite
  demonstrably catches the guarded bug class: a deliberate mutation (zeroing the
  key bytes on removal) fails the constant-hash scenario immediately. Cross-thread
  stress tests (one writer + racing readers) run on JVM **and Native**.
- **Read scaling — the point of lock-free reads.** Reader throughput scales
  ~linearly with reader count. *Smoke-measured (1 fork, noisy machine —
  authoritative matrix deferred to Phase 6): 35.6 → 139.6 → 308.9 ops/µs at
  1/4/8 reader threads over a 1M-entry map (~8.7× at 8 threads). A lock-guarded
  `SwissLongMap` collapses under the same load (28.1 → 9.0 → 5.5 ops/µs).
  Boxed `ConcurrentHashMap<Long, Long>` scales comparably (282.6 ops/µs at 8
  threads) — against it the differentiators remain primitive keys, memory
  footprint, and KMP portability, not raw read throughput.*
- **Single-threaded overhead — the honest tax.** Versus `SwissLongMap` at 1M
  entries (smoke): random-access lookup ~1.3–1.5× slower (seq-cst control-word
  and value loads); presized insert ≈ parity. Prefer `SwissLongMap` when the map
  is confined to one thread.
- `size`/`isEmpty` are weakly consistent moment-in-time estimates
  (`ConcurrentHashMap`-style), documented as such and excluded from
  linearizability checking.

**Fairness gates (all phases):** equalize load factor across maps; pre-size each
in its own units; always include an **adversarial/clustered key set**; lookup
benchmarks must include a **random-access (shuffled) order**, not just sequential
keys — sequential in-order access flatters chaining's node locality and is an
accidental best case for `HashMap` (Phase 1 lesson); name the baseline in every
published number; compare primitive-vs-primitive.

## Methodology

JMH on the JVM (authoritative; the raw-JMH tier lives in the `benchmarks-jvm`
module — added in Phase 4 for the multi-threaded read-scaling benchmarks that
`kotlinx-benchmark`'s common facade cannot express, and the home for `-prof gc`
runs), `kotlinx-benchmark` for the portable JVM + Native tiers. `@Fork ≥ 3`,
warmup 5×1s, measurement 5×1s, `AverageTime`/ns. Seed key sets deterministically
and share identical arrays across implementations. Numbers are reported
**per platform** and never cross-compared as a single figure.
