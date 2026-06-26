# Performance goals & success metrics

This document fixes what "much faster than the standard data structures" means
for this project, so success is measurable rather than asserted. It is the
yardstick for the benchmark matrix (plan §1.4, decision DP-4).

## Honest positioning

The baseline we commit to beating is the **platform standard library**
(`java.util.HashMap` on the JVM; the Kotlin/Native `HashMap`), **not** the
best-in-class native maps (abseil / Rust `hashbrown`) — those are out of reach
from common Kotlin (no SIMD, no off-heap; see plan §1.2). The win comes from
**open addressing + primitive specialization** (no boxing), not from any
algorithmic novelty.

The speed claim is **gated on primitive keys**. For boxed/object keys an
open-addressing map at moderate load may only break even with `HashMap`, whose
high-bit spread and treeify are a real safety net.

## Phase 0 baseline snapshot (indicative)

Captured with the **`smoke`** configuration (1 warmup, 1 iteration) — rough, not
publication-grade; authoritative baselines come from the `main` configuration
(5×5) on pinned hardware. Boxed `HashMap<Long, Long>`, Apple Silicon, Corretto 17.

| Op | size | JVM (ns/op) | Native macosArm64 (ns/op) |
|---|---|---|---|
| `lookupHit` | 10 000 | ~30 100 | ~98 900 |
| `lookupHit` | 1 000 000 | ~6 631 000 | ~17 750 000 |
| `insertAll` | 10 000 | ~87 700 | ~338 300 |
| `insertAll` | 1 000 000 | ~19 588 000 | ~42 040 000 |

The Native stdlib map is ~3× slower than the JVM's here, confirming it is the
more beatable baseline.

## Success criteria

**Phase 1 — primitive `Long → V` map (the speed proof):**
- **JVM:** ≥ 2× faster than boxed `HashMap<Long, V>` on `lookupHit` and
  `insertAll` at load factor 0.9; **never** slower than stdlib by more than 10 %
  on any core op (insert / lookup-hit / lookup-miss / iterate / remove).
- **Native:** ≥ 2× faster than the Kotlin/Native `HashMap` on the same ops.
- **Memory:** report bytes/entry; target a clear reduction versus `HashMap`'s
  node-per-entry layout. Footprint is a first-class deliverable, not an
  afterthought.

**Phase 2–3 — funnel / elastic (the bounded-worst-case specialists):**
- Empirically confirm the paper's probe-count bounds at rising load
  (`O(log² 1/δ)` funnel; `O(1)` amortized / `O(log 1/δ)` worst-case elastic) and
  assert them as CI regression metrics.
- Demonstrate the **insert/delete win at very high load (≈ 0.99)**; report the
  **lookup-at-scale cost** honestly (the φ ordering can regress lookups at large
  sizes — this is expected, not a bug).

**Fairness gates (all phases):** equalize load factor across maps; pre-size each
in its own units; always include an **adversarial/clustered key set**; name the
baseline in every published number; compare primitive-vs-primitive.

## Methodology

JMH on the JVM (authoritative; add the raw-JMH tier with `-prof gc` for
bytes/op when first needed), `kotlinx-benchmark` for the portable JVM + Native
tiers. `@Fork ≥ 3`, warmup 5×1s, measurement 5×1s, `AverageTime`/ns. Seed key
sets deterministically and share identical arrays across implementations. Numbers
are reported **per platform** and never cross-compared as a single figure.
