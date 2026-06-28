# Phase 2 — `FunnelLongMap<V>` (clean-room funnel hashing)

**Status:** implementation + tests + static analysis complete; review in progress.
Branch `phase-2`. See [implementation plan](elastic-hashing-implementation-plan.md) §Phase 2
and the approved design (this file supersedes the scratch plan).

## What shipped

- `io.spine.elastic.FunnelLongMap<V>` — the first faithful, clean-room JVM/KMP
  implementation of **funnel hashing** (Farach-Colton, Krapivin & Kuszmaul,
  arXiv:2501.02305, §5). Primitive `Long` keys (no key boxing), object values in
  `Array<Any?>`, implementing `OpenAddressingLongMap<V>`. Positioned honestly as a
  **bounded-worst-case specialist for very high load**, not a general fast map.
- `io.spine.elastic.internal.FunnelCapacity` — funnel capacity arithmetic (a doubling
  sequence from `MIN = 64`, chosen by `FunnelSizing.maxInserts`). Funnel capacity is
  **not** power-of-two, so `Capacity` (the SwissTable `7/8` power-of-two sizing) cannot
  be reused for it.
- Reuses `FunnelSizing` **verbatim** for all geometry (α/β/specialSize/levelBucketCounts/
  specialProbeLimit/maxInserts — already cross-checked against `sternma/optopenhash` and
  pinned by `FunnelSizingSpec`), plus `LongHasher`/`fmix64` and the `OpenAddressingLongMap`
  interface.
- Tests (`commonTest`, run on JVM + Native): `FunnelLongMapSpec` (contract, boundary
  keys, growth, pre-size, tombstone churn, heavy-collision descent, the **cross-level
  duplicate regression**, the **rebuild re-double** recovery, **insert/delete churn**
  (reclaim-in-place + growth, differential), the **adversarial-throw** guard with its
  message, clear), `FunnelLongMapPropertiesSpec` (differential vs `LinkedHashMap` over
  clustered + wide domains), `FunnelLongMapProbeBoundSpec` (the `O(log² 1/δ)` ceiling as
  a CI assertion, exercised at high load ≈ 1−δ), and `FunnelCapacitySpec` (the sizing
  helper directly, including the `MAX` guards).
- `benchmarks/FunnelLongMapBenchmark` — same shape/sizes as `SwissLongMapBenchmark`/
  `StdlibHashMapBenchmark`, with a `delta` sweep (`0.1` and high-load `0.01`).
- `elastic/build.gradle.kts` — `force(Base.annotations)` so the `jvmTest` classpath
  aligns the stale transitive `spine-annotations` to `Base`'s pinned version (see
  *Verification*).

## The algorithm (faithful + corrected)

- **Layout.** One `Tables<V>` holder (swapped by a single field write on rebuild) backs
  both regions with one `keys`/`values`/`ctrl` triple: primary levels in `[0, specialBase)`,
  the special overflow array in the tail. Occupancy is a per-slot `ByteArray`
  (`EMPTY`/`FULL`/`TOMBSTONE`) — **no SWAR control words**: a funnel bucket is small
  (`beta = ceil(2*log2(1/delta))` slots, 7 at the default `delta`), not the eight-wide
  power-of-two group SWAR needs, and fits a cache line, so a plain bucket scan is simpler
  and equally fast. Per-level deterministic golden-ratio
  salts; `bucketIndex = fmix64(hash xor salt).mod(bucketCount)` — a real floor-modulo, not
  a mask (bucket counts are arbitrary integers).
- **Insert = lookup-then-place.** `put` does the full `find` first; if present it
  overwrites, else `placement` descends to the first level with a free slot (a `TOMBSTONE`
  reused before a never-used `EMPTY`), spilling to the special array. Searching before
  placing is what prevents cross-level duplicate keys once tombstones exist (the critical
  bug the pre-implementation design review caught).
- **Search = stop-on-empty (whole search).** `find` descends past a bucket only when it
  is entirely full; the first `EMPTY` it meets proves the key absent and stops the *entire*
  search. Sound because a key at level `j` was placed only when every earlier level's
  bucket was entirely full, and a full slot never reverts to `EMPTY` (deletes write
  `TOMBSTONE`) — so a present key is never preceded by an empty slot on its probe path.
  A `TOMBSTONE` never ends a search; only an `EMPTY` does.
- **Deletion = tombstones** (off-paper; the paper is insertion-only). Reclaimed only on
  rebuild. `growthLeft` (a `maxInserts` budget) is not credited back on remove — same as
  the Phase-1 maps.
- **Growth = rebuild-on-grow with a re-double loop.** A rebuild drains all live entries
  **plus the pending key** into a fresh table and only publishes when all fit; because the
  greedy re-insertion can itself overflow, the target capacity doubles and retries up to
  `MAX_GROWTH_RETRIES`. *Structural* overflow (no slot found even below the load budget)
  **always grows** — the capacity-invariant salts would reproduce the collision at the
  same size; same-capacity reclaim is reserved for the load trigger at low occupancy.
- **Probe metric (DP-11).** `Tables.probes` counts slots examined by the last `find`;
  the map exposes `internal lastProbes` and `internal maxProbesPerOp`
  (`levelCount*beta + specialProbeLimit + 2`) for the CI bound check.

## Key decisions (made during the work)

- **Two deliberate departures from Phase 1, approved in the plan:** (1) no SWAR control
  words (see above); (2) `delta` is a public, user-tunable constructor param (default
  `0.1`), fixed for the map's lifetime and preserved across rebuilds.
- **stop-on-empty whole-search** — a correctness-preserving simplification/speedup over
  the reconciled spec's "descend past empty" (which was over-conservative). Proven and
  re-verified adversarially.
- **Funnel is hash-sensitive by design.** A hasher that defeats the per-level salts (e.g.
  a constant hash) cannot be accommodated at any capacity; the map throws
  `IllegalStateException` rather than looping or OOM-ing. Documented; use `SwissLongMap`
  for adversarial keys.

## Verification

- **JVM + Kotlin/Native (`macosArm64`): all tests green.** `FunnelLongMapSpec` (17),
  `FunnelLongMapPropertiesSpec` (2), `FunnelLongMapProbeBoundSpec` (1),
  `FunnelCapacitySpec` (7), plus the existing suites, on both targets.
- **detekt: green. Kover: green** — `FunnelCapacity` 100 % line / 100 % branch,
  `FunnelLongMap` 97 % / 90 %, `FunnelLongMap.Tables` 99 % / 86 %.
- **Build fix (the JVM-test blocker):** `base-testlib` (added to `jvmTest` by the
  `kmp-module` convention) transitively pins an **older** `spine-annotations`
  (`2.0.0-SNAPSHOT.360`) than the `Base` module this project targets (`.421`), and the
  convention's `forceVersions()` does not force `spine-annotations`. That stale, uncached
  version was the only thing requiring a GitHub Packages download. Aligned it to
  `Base.annotations` via `force(Base.annotations)` in `elastic/build.gradle.kts` (next to
  the existing `force(AtomicFu.lib)`); resolution now uses the cached `.421` and JVM tests
  + Kover run with no credentials.
- **Adversarial implementation review (4-dimension multi-agent pass): sound.** The
  **stop-on-empty** search was independently verified correct against a `HashMap` oracle
  over ~445M randomized cross-checks with clustering hashers — no counterexample exists.
  Its findings were test-coverage gaps (now closed: the rebuild re-double branch, the
  reclaim-in-place branch, and a high-load probe-bound test) and doc-accuracy nits (fixed:
  the β range and the "capacity-invariant salts" phrasing) — **no code bugs**.

## Open follow-ups

- **Funnel/elastic deletion policy (plan, Phase 2 open item).** Shipped as tombstones +
  reclaim-on-rebuild (the natural fit for a no-reordering structure). A later pass may add
  an explicit same-capacity tombstone-reclaim trigger from `remove` under heavy churn
  (currently reclaim only piggybacks on the `growthLeft` rebuild trigger). Shared with
  Phase 3 elastic.
- **JVM authoritative benchmark numbers** (honest high-load insert/delete win + the
  lookup-at-scale cost) — to be captured on pinned hardware, like Phase 1.
- **Probe-bound mean check** is currently a loose "< ceiling" soft assertion; tighten to a
  calibrated amortized `O(log 1/δ)` bound once JVM numbers exist.
- **Consciously accepted (review should-considers, left as-is):** (1) the special array's
  two-slot fallback is inert at every realizable size (`specialProbeLimit ≥ 2` because
  `FunnelCapacity.MIN = 64`) — kept as a documented, oracle-faithful safety net; it adds a
  constant `+2` to the probe ceiling, which `maxProbesPerOp` accounts for. (2) The two
  "cannot grow further" failure modes throw different types: `IllegalStateException` on
  re-double exhaustion (the realistic degenerate-hash case, asserted with its message) and
  `IllegalArgumentException` at the `FunnelCapacity.MAX` ceiling (matches Phase-1
  `Capacity.grown`, essentially unreachable in practice).
- Version: branch already at `1.0.0-SNAPSHOT-004` (> master `-003`); gate satisfied.
  `docs/project.md` status updated.
