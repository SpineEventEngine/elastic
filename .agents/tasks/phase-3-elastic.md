# Phase 3 — `ElasticLongMap<V>` (clean-room elastic hashing)

**Status:** design (this file) → implementation → tests → review. Branch `phase-3`.
See the [implementation plan](elastic-hashing-implementation-plan.md) §Phase 3, the
Phase-1 [`SwissLongMap`](phase-1-swiss-long-map.md) and Phase-2
[`FunnelLongMap`](phase-2-funnel.md) it builds on.

The namesake research contribution: the first credible JVM/KMP clean-room
implementation of **elastic hashing** (Farach-Colton, Krapivin & Kuszmaul, *Optimal
Bounds for Open Addressing Without Reordering*, arXiv:2501.02305, **§4**) — the
*non-greedy* structure that breaks the amortized-`log` barrier
(`O(1)` amortized / `O(log 1/δ)` worst-case expected probes). Positioned, like funnel,
as a **bounded-worst-case specialist for very high load**, not a general fast map.

---

## 1. What the algorithm is (faithful core)

Elastic hashing partitions the slot array into **geometric levels**
`A₁, A₂, …, A_⌈log n⌉` (`A₁` the largest ≈ `n/2`, each subsequent level ≈ half the
previous), each with an **independent per-level hash**. The defining idea — and the
whole reason the paper beats Yao's bound — is **non-greedy insertion**: an insert does
*not* always take the first free slot. Looking at how full the current level `Aᵢ` and
the next `Aᵢ₊₁` are, it spends a **bounded probe budget** `f(ε)` in `Aᵢ` and otherwise
**defers** to `Aᵢ₊₁`. Decoupling placement from "first free" is what yields `O(1)`
amortized.

### 1.1 The three-case non-greedy insertion (paper §4)

For a key reaching level `i` (`i < last`), let `ε₁` be the **free fraction** of `Aᵢ`
and `ε₂` the free fraction of `Aᵢ₊₁`. The per-level probe budget is
`f(ε) = c · min(log₂(1/ε), log₂(1/δ))` (the paper's `c·min(log² ε⁻¹, log δ⁻¹)`;
the reference and this project use `c = 4`, per **DP-12: port then tune**) — already
implemented and cross-checked as
[`ElasticSizing.probeLimit`](../../elastic/src/commonMain/kotlin/io/spine/elastic/internal/ElasticSizing.kt).

- **Case 1 — `ε₁ > δ/2` and `ε₂ > 0.25`:** probe up to `f(ε₁)` slots in `Aᵢ`; place in
  the first free one. If none is free within the budget, **defer** to `Aᵢ₊₁`.
- **Case 2 — `ε₁ ≤ δ/2`** (this level is nearly full): **skip** `Aᵢ`, descend to
  `Aᵢ₊₁` without probing here.
- **Case 3 — `ε₂ ≤ 0.25`** (the *next* level is nearly full): fall back to **greedy** —
  scan all of `Aᵢ` and take the first free slot.

The **last** level is always greedy (scan all of it). The `δ/2` and `0.25` thresholds
and the `c=4` budget are ported verbatim from the paper / the `sternma/optopenhash`
reference (the oracle this project's sizing is already cross-checked against).

### 1.2 Search

A key may sit in a level deeper than the first with free space (the non-greedy rule
defers and skips), so search **cannot** stop at the first empty slot across the whole
table the way funnel does. Search is **level-by-level**: for each level in order, probe
that level's sequence, stopping at the first **empty** slot *within that level* (a
tombstone never stops it), then continue to the next level. The first key match wins.

This is exactly what the reference oracle does. It is a deliberate, documented
**simplification of the paper's `φ` injection** (see §3).

---

## 2. Realization decisions (faithful, but it must actually reach high load)

A naïve copy of the reference would not work as a real map. The concrete realization:

### 2.1 Power-of-two levels + full-coverage triangular probing

*(Why we diverge from the reference's quadratic probing.)*

The `sternma` reference probes each level with **quadratic probing** `(h + j²) mod m`
over **non-power-of-two** level sizes (e.g. `[1,1,3,11]` for capacity 16). On a level of
size `m`, `{j² mod m}` reaches only ≈`m/2` *distinct* slots (the quadratic residues), so
the reference **cannot fill a level past ≈50–60 %** before a free slot becomes
unreachable — it raises *"Insertion failed in all levels"*. That defeats the entire
point of Phase 3, which is **bounded behaviour at load ≈ 1 − δ (up to ~99 %)**.

The paper assumes **uniform random probes that cover the level**. The faithful,
*terminating* realization of "a random slot in `Aᵢ`" is the one the Phase-1 map already
uses: **power-of-two level sizes + triangular probing** `(h + j(j+1)/2) mod m`, which
visits **every** slot of a power-of-two level exactly once. So:

- The map allocates each level at a **power-of-two size**, largest-first, summing to the
  (power-of-two) capacity: `[cap/2, cap/4, …, 2, 1, 1]` (the last two are size 1 so the
  geometric series sums exactly to `cap`). This is `ElasticSizing.binaryLevelSizes` (new;
  added beside the existing paper/reference-faithful `levelSizes`, which stays as the
  cross-checked arithmetic reference and is still tested).
- Within a level, probe by the triangular sequence — full coverage, good distribution
  (close to the paper's random-probe assumption), and it reaches arbitrarily high load.
- Per-slot scan (not packed-`Long` SWAR): like funnel's buckets, most probing happens in
  a small budget window, and the small/short levels make SWAR's eight-wide group
  machinery unhelpful here.

`ElasticSizing.maxInserts` and `ElasticSizing.probeLimit` are reused **as-is**.

### 2.2 Per-level hashing

`levelHash_i = fmix64(baseHash xor levelSalt[i])` with deterministic golden-ratio salts
`GOLDEN * (i+1)` — identical to funnel, so probe paths decorrelate across levels.

### 2.3 Insertion is lookup-then-place

`put` runs the full `find` first; if present, it overwrites. Only a proven-absent key
runs the non-greedy `placement`. This is the **Phase-2 lesson**: deciding placement from
a single level while a live copy sits in another level would create cross-level
duplicates once tombstones exist. (Verified by a dedicated regression test.)

### 2.4 Deletion = tombstones; growth = rebuild-on-grow (shared with funnel)

- **Deletion** writes a `TOMBSTONE` (off-paper, as in funnel): no reordering, so the
  probe chain a deleted occupant participated in is preserved. A tombstone never ends a
  search; placement reuses a tombstone before a never-used empty (eager reclaim).
- **Per-level `used[i]`** counts ever-written slots (FULL + TOMBSTONE); it drives `ε`
  for the case analysis. Reusing a tombstone leaves `used[i]` unchanged; consuming a
  never-used empty bumps it.
- **Stranding guard (review-mandated).** Because `ε` is `used`-based (tombstone-pessimistic),
  Case 2 (`ε₁ ≤ δ/2`) can *skip* a level that still has a free or reclaimable slot, so the
  non-greedy descent could return "no slot" *below* load `1 − δ`. To keep placement from
  ever reporting a false overflow while a slot is free, `placement` ends with a
  **table-wide greedy sweep** (largest→smallest, first non-FULL via full triangular
  coverage) as a fallback. This restores the funnel-style termination proof: placement
  never reports a structural overflow while any slot is free.
- **Growth** uses a global `growthLeft = maxInserts − Σ used[i]` budget; when it hits 0,
  rebuild into a fresh table draining all live entries plus the pending key. The drain
  re-inserts by **pure greedy descent** (`greedyPlacement`, *not* the 3-case rule) — over
  full-coverage power-of-two levels this provably places any key while a slot is free, so
  a table sized for its entries drains in a **single pass**. Unlike a funnel rebuild
  (whose fixed `β`-buckets can overflow during re-insertion, needing a re-double loop), an
  elastic rebuild can never itself overflow, so there is no re-double loop;
  `reinsert` fails loudly rather than dropping an entry if that invariant is ever
  violated. Low live occupancy (`≤ maxInserts/2`) rebuilds **in place** to reclaim
  tombstones; otherwise the capacity **doubles** (`ElasticCapacity`, new — power-of-two
  doubling from a `MIN ≥ 16`, `forEntries` sized **purely** against `maxInserts` since
  full coverage makes every slot addressable; no `holdableEntries` cap as in funnel). A
  `structural` flag forces a grow (not in-place reclaim) should a structural overflow ever
  occur — a defensive guard that, given `growthLeft` always fires first, is unreachable in
  practice. **Pre-sizable to `(n, δ)`** (DP-9).
- **Level count.** `binaryLevelSizes(cap).size == ElasticSizing.levelCount(cap) + 1` (the
  two trailing size-1 levels). The map sizes **every** per-level array (salts, offsets,
  `used`) from `binaryLevelSizes.size` — never from `levelCount`, which would be off by
  one. A test pins the `+1`.

### 2.5 Adversarial hashing — degrades, does not throw (a *trade-off*, not a free win)

Funnel must throw on a degenerate hasher (its fixed `β`-slot buckets cannot absorb keys
that collide identically at every level). Elastic's **full-coverage** levels never need
to: a constant hasher fills each level completely (the triangular sequence still
enumerates a level's slots) and the table simply grows — every key stays findable, no
throw. But this is a **trade-off, not an advantage to celebrate**: a constant hasher
makes placement and search degrade to **~Θ(capacity) per operation** (measured by the
review: ~169 probes/insert, search mean 462 / worst 929 of 1024 slots at cap 1024), and
memory grows without bound as distinct keys arrive. Funnel's loud-but-cheap
`IllegalStateException` is arguably *safer*. The constant-hasher test therefore **asserts
the probe blow-up**, so the degradation is visible and never mistaken for "bounded
behaviour". The loud `check()` in `Tables.reinsert` is the documented dead-man guard:
with full coverage **plus the §2.4 greedy fallback** an elastic rebuild can never
overflow, so a single drain pass always succeeds and that `check` never fires.

### 2.6 Concurrency-aware (DP-13)

Single-threaded like the siblings, but structured for the Phase-4 SWMR derivation:
one `Tables` holder swapped by a single field write on rebuild; key+value written before
the occupancy byte.

---

## 3. The `φ` injection — what we do and don't implement (honest scope)

The paper's `φ: ℕ² → ℕ` injection (`φ(i,j) ≤ O(i·j²)`) linearizes the 2-D
(level, attempt) probe space into one global order; probing in `φ`-order is the
*analytical device* behind the tight **`O(log 1/δ)` worst-case** *search* bound. We
implement the **operational** elastic structure faithfully — geometric levels, per-level
independent hashing, and the **non-greedy three-case insertion with the real `δ/2` /
`0.25` thresholds and the `f(ε)` budget** (this is where the `O(1)`-amortized
**insertion** win actually comes from, and it touches only the two adjacent levels `φ`
orders) — but search probes **level-by-level**, exactly as the reference oracle does,
rather than in `φ`-interleaved order.

Consequence, stated honestly (per the plan's "documented lookup-at-scale regression"):
amortized **insertion** probe behaviour reflects the elastic contribution and is the
focus of the probe-bound CI metric; **search** at very high load can scan more than the
`φ`-interleaved ideal. Full `φ`-interleaved search is a documented potential refinement,
not part of this phase — the same kind of transparent simplification Phase 2 made with
its stop-on-empty search.

---

## 4. Deliverables

- `io.spine.elastic.ElasticLongMap<V> : OpenAddressingLongMap<V>` — primitive `Long`
  keys (no key boxing), values in `Array<Any?>`; public `delta` knob (default `0.1`),
  `expectedSize`, pluggable `hasher`. Mirrors `FunnelLongMap`'s shape/KDoc style.
- `io.spine.elastic.internal.ElasticCapacity` — power-of-two doubling capacity,
  delta-aware `forEntries`, `grown`, `MIN`/`MAX`. Mirrors `FunnelCapacity`.
- `ElasticSizing.binaryLevelSizes(capacity)` — power-of-two largest-first level split
  used by the map (added beside the existing, still-tested `levelSizes`).
- Probe instrumentation (DP-11): `internal lastProbes` (last search) and a placement
  probe counter, feeding `ElasticLongMapProbeBoundSpec`.
- Tests (commonTest, JVM + Native):
  - `ElasticLongMapSpec` — contract, null values, boundary keys, growth-from-default,
    pre-size, tombstone churn, heavy-collision (constant hasher) **gracefully grows**,
    cross-level-duplicate regression, the no-premature-growth stranding guard,
    reclaim-in-place, insert/delete churn (differential), clear, input validation.
  - `ElasticLongMapPropertiesSpec` — differential vs `LinkedHashMap` over clustered +
    wide + constant-hash domains.
  - `ElasticLongMapProbeBoundSpec` — amortized insertion + mean search probes bounded at
    high load (delta sweep).
  - `internal/ElasticCapacitySpec`; extend `ElasticSizingSpec` for `binaryLevelSizes`.
- `benchmarks/ElasticLongMapBenchmark` — same shape/sizes as the funnel benchmark, with
  the `delta` sweep (`0.1`, `0.01`).
- Docs: update `docs/project.md` status and the plan's Phase 3 status; version bump.

## 5. Correctness arguments to verify (review targets)

1. **Search reaches every live key.** A key is placed at the first non-FULL slot in its
   level's triangular sequence; earlier slots were non-empty at insert time and a FULL
   slot only ever becomes TOMBSTONE (never EMPTY), so search — which stops only at EMPTY
   — always reaches it. Scanning *all* levels covers non-greedy deferral/skip.
2. **No cross-level duplicates** under tombstones — guaranteed by lookup-then-place.
3. **Termination & high load** — triangular probing fully covers each power-of-two
   level, so placement finds any free slot and the table reaches load `1 − δ` before
   `growthLeft` forces a rebuild.
4. **Growth accounting** — `used[i]`/`growthLeft` updated only on never-used-empty
   consumption; rebuild recomputes from drained live entries; the single-pass greedy
   drain cannot overflow a table sized for its entries (the `reinsert` `check` proves it).
5. **Differential equivalence** to `LinkedHashMap` across randomized op sequences,
   including the constant-hash (graceful-growth) path.

## 6. Pre-implementation review outcome — GO-WITH-CHANGES

A four-lens adversarial design review (paper-fidelity, correctness-adversarial,
coverage/termination/high-load, reference/reuse) plus synthesis stress-tested this design
over ~1.4M+ simulated operations. Verdict: **fundamentally sound** — no reviewer could
make search miss a live key, create a cross-level duplicate, return a stale value, or
confuse a null value with an absent key; triangular probing fully covers each
power-of-two level; the table reaches load `1 − δ` before growth; amortized insertion
probes are bounded and **n-independent** (~3.4/insert at load 0.9 across cap 256…65536).
The ordering question is resolved: **largest-first** is the *paper-faithful* choice (the
`sternma` oracle's smallest-first iteration is the unfaithful one); the differential test
compares **end-state membership**, not per-level placement.

Changes baked into this design (all reflected above):

- **Stranding guard** — table-wide greedy fallback in `placement` (§2.4).
- **Pure-greedy rebuild drain** + **`binaryLevelSizes.size` as the sole level count** (§2.4).
- **D7 reframed** from "advantage" to "trade-off", with an asserted probe blow-up (§2.5).
- **`ElasticSizing.probeLimit` parameter `load` → `freeFraction`** + KDoc ("the FREE
  fraction `ε = 1 − fill`, NOT the load factor"), to prevent a future `(1 − ε)` inversion.
- **`ε` is the `used`-based (ever-written, FULL+TOMBSTONE) free fraction** — deliberately
  pessimistic under tombstones; tombstone reclaim is best-effort (a tombstone passed
  inside an entered probe window is reused before a never-used EMPTY; tombstone-heavy
  near-full levels are reclaimed at the next rebuild). One documented `ε` definition drives
  both the Case 1/2/3 thresholds and the `f(ε)` budget.
- **The probe-bound CI gate asserts amortized *insertion* probes** (the elastic
  contribution), not search; search-at-high-load cost is measured and documented, not
  gated.

Must-add tests folded into §4: constant-hasher graceful-growth **with asserted probe
blow-up** and budget-driven growth; the stranding regression (free slot placed without
growth); `binaryLevelSizes` structure incl. the `levelCount + 1` relationship;
cascade-to-tail; search-reaches-every-live-key under churn; cross-level-duplicate
regression; null-value vs absent-key; tombstone-churn capacity bound; `probeLimit`
free-fraction guard; reclaim-in-place-vs-double at `maxInserts/2`; `growthLeft` boundary;
amortized-insertion probe metric; pre-size → zero rebuilds; differential at
`δ ∈ {0.1, 0.05, 0.25}`.
