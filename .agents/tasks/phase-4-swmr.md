# Phase 4 — `SingleWriterSwissLongMap<V>` (single-writer / multi-reader, lock-free reads)

**Status:** implementation + tests + post-implementation reviews complete; green on
JVM, macosArm64, and iosSimulatorArm64 (tests, detekt, Kover, Dokka). Branch
`phase-4`. Results in §8.
See the [implementation plan](elastic-hashing-implementation-plan.md) §Phase 4 and the
Phase-1 [`SwissLongMap`](phase-1-swiss-long-map.md) it derives from.

The thread-safe variant decided by **DP-13 / DP-13a**: one writer (or externally
serialized writers), any number of readers, and **reads that never block and never
spin on a lock** — derived from the single-threaded `SwissLongMap`, whose storage was
deliberately structured for this derivation (immutable storage holder swapped by a
single reference store; key and value written before the control byte).

---

## 1. Concurrency model and claims

- **Single writer.** All mutating calls (`put`, `remove`, `clear`) must come from one
  thread at a time. Either a dedicated writer thread, or external serialization
  (a lock, an actor) — whatever provides a happens-before edge between successive
  writers. Concurrent writers are undefined behavior, exactly like `HashMap`.
- **Many readers, lock-free.** `get`, `containsKey`, `size`, `isEmpty` may be called
  from any thread at any time, concurrently with the writer and each other. They
  allocate nothing, take no locks, and never write shared state.
- **Linearizability — scoped to the key-addressed operations** (review-mandated):
  `get`, `containsKey`, `put`, `remove`, and `clear` are linearizable. Every read
  racing a write returns a value consistent with some linearization: never a torn
  entry, never a value paired with the wrong key, never an entry missed that was
  present for the whole operation.
- **`size`/`isEmpty` are weakly consistent** moment-in-time estimates, exactly like
  `ConcurrentHashMap.size()`. They cannot be linearizable here in *either* ordering
  of the count update relative to the content-visible store (two locations cannot
  share one linearization point): count-after-publish admits `get(k)=v; size()=0`,
  count-before admits `size()=1; get(k)=null`. The design deliberately does not try.
  Chosen (documented) ordering: the count updates **after** the content-visible
  store — increment after the control publish, decrement after the `DELETED` store,
  and `clear()` swaps the table before zeroing the count. `isEmpty()` (the interface
  default `size == 0`) inherits the same semantics.
- **Progress: reads are bounded, not merely lock-free.** A probe performs at most
  `(maxLoad(capacity) + 1) * numGroups` control-word loads on its table snapshot.
  Proof sketch: non-empty lanes per table never exceed `maxLoad`, so ≥ `capacity/8`
  lanes stay `EMPTY` at every instant; the triangular probe visits every group once
  per `numGroups` steps; a full cycle overlapping zero inserts reads a constant
  table and must hit an empty-containing group, so every failed cycle overlaps ≥ 1
  insert; and a table absorbs ≤ `maxLoad` inserts before `growthLeft` hits 0 and the
  writer must swap (`clear()`/rehash/grow all publish a *new* `Tables` and never
  write the retired one again). The proof pins three implementation obligations:
  1. readers load the table reference **once** per operation, never mid-probe;
  2. the `growthLeft` check precedes slot publication;
  3. retired tables are never mutated.

## 2. Derivation from `SwissLongMap` — the four changes

The algorithm (SWAR control-word scan, group-aligned triangular probe, `7/8` load,
resize+rehash growth) is unchanged. What changes is *publication*:

1. **The table reference is atomic.** `Tables` (control words + keys + values +
   geometry) stays an immutable-shape holder; the map's `tables` field becomes an
   atomic reference. The writer builds a rebuilt table off-line with plain writes and
   publishes it with a single atomic store; every operation — read or write — loads
   it once and works on that snapshot for the whole operation. All build-time writes
   therefore happen-before any use of the new table.
2. **Control words are atomic `Long` elements.** Readers load a whole 8-lane group
   with one atomic element read — an immutable snapshot of the group, scanned by the
   existing `Swar` routines. The writer publishes a slot by storing the updated
   control word *after* writing the slot's key and value. A reader that observes a
   lane `FULL` therefore observes the key and value stores that preceded the
   publication.
3. **Value slots are atomic references.** A `put` over an existing key overwrites the
   value *without* touching the control word, so that store needs its own
   publication edge: values live in an atomic reference array. This also safely
   publishes the *contents* of a freshly constructed value object.
4. **No tombstone reuse — the load-bearing simplification.** In the concurrent
   variant, `put` never reuses a `DELETED` lane; deleted slots are reclaimed only by
   the (always fresh-table) rebuild.

   This establishes the named, load-bearing invariant (review-mandated):

   > **Table-state monotonicity.** Within a published table, a lane only moves
   > `EMPTY → FULL → DELETED` (never back to `EMPTY`); each `keys[slot]` is written
   > **exactly once**, before the control store that publishes it; a value slot only
   > moves between values and then to `null` (`remove`), never `null → value` again.

   Consequences: the plain `LongArray` key read is ordered by the control load and
   can never observe a *different* key than the fingerprint matched (so `remove`
   must **not** zero `keys[slot]`, unlike the single-threaded map — a racy key
   rewrite would be a genuine data race, and on Native UB-class); an observed
   `EMPTY` lane is a valid instant-of-absence proof; a same-key reinsert always
   lands at a strictly *later* probe position than its tombstone (empties only
   shrink, and the original insert took the first empty-containing group), so a
   probing reader can never miss both slots without a genuine absence window
   existing; occupancy only grows, which is what bounds reader probes (§1).

   The cost: churn workloads rebuild more often than the single-threaded map (every
   fresh-slot insert consumes `growthLeft`; a remove never credits it back). The
   capacity nevertheless stays bounded: churn at `L` live entries stabilizes at the
   smallest power-of-two `C` with `L ≤ rehashThreshold(C) = ⌊7C/16⌋` — at most one
   doubling beyond what `L` alone needs — after which every rebuild reclaims
   tombstones at the same capacity. (E.g. 600 live keys pre-sized to capacity 1024
   stabilize at 2048; that one-time doubling is intended behavior, pinned by test.)

`entryCount` becomes an atomic int so `size`/`isEmpty` are safely readable (§1
semantics). `growthLeft` stays a plain field — writer-only state, covered by the
single-writer contract.

### 2.1 Why remove may null the value slot (no present-vs-absent ambiguity)

`remove` stores `DELETED` into the control lane and *then* nulls the value slot (so
dead values do not leak until the next rebuild). A racing reader that already matched
the lane while it was `FULL` may then load `null` from the value slot. This is safe
*for this API surface*:

- `get` returns `null` both for "absent" and for a stored `null` value — so returning
  `null` when racing a remove is indistinguishable from linearizing after it. (The
  write order guarantees the remove's `DELETED` store lies inside the reader's
  interval whenever it sees `FULL` + `null`, so that linearization point exists.)
- `containsKey` never reads the value slot at all; it linearizes on the control-word
  load (before the remove if it saw `FULL`, after if it saw `DELETED`).
- `put`/`remove` return values are writer-local reads — exact under the contract.

There is no operation that distinguishes "present" from "absent" *through the value
slot*, so the `FULL`-control/`null`-value transient is unobservable. (This is the
reason the concurrent variant can keep `SwissLongMap`'s null-value support instead of
banning nulls the way `ConcurrentHashMap` does.)

**Future-view obligation (recorded for Phase 5):** an entry-materializing view
(entries/values iteration, `containsValue`, `forEach`) *would* observe the transient
as a phantom `(key → null)` pair. The mitigation falls out of the chosen write order
(`DELETED` before `null`): a view that loads `null` from a slot it saw `FULL` must
re-load the control lane — `DELETED` means skip the slot, `FULL` means a genuine
stored null. That recheck is sound only because of table-state monotonicity (§2 change 4);
carry this note into the Phase-5 boxed-view design.

### 2.2 Interleavings argued (reader vs the one writer)

| Reader step sees | Writer concurrently | Outcome |
|---|---|---|
| lane `EMPTY` on its probe | inserts that key right there | read linearizes before the insert (miss is correct — the ops overlap) |
| lane `FULL`, fingerprint match, key match | overwrites the value | reader loads old or new value — both linearizable |
| lane `FULL`, key match | removes the key | old value → before; `null` value → after (§2.1) |
| lane `DELETED` | anything | fingerprint (`0x00..0x7F`) never matches `0xFE`; probe continues, stops only on `EMPTY` |
| old table snapshot | resize publishes new table | reader finishes on the frozen old table; any write that completed before the reader *started* is in that snapshot (the atomic table-load orders it) |
| control word from before an insert, key slot after | — | impossible to misread: the reader only dereferences `keys[slot]` for lanes it saw `FULL`, and a `FULL` observation orders the write-once key store (§2 change 4) |
| tombstoned slot of key `k`, misses a racing reinsert of `k` | remove(k) earlier, put(k) racing | the reinsert lands strictly later on the probe than the tombstone (monotonicity); a "miss" linearizes in the genuine absence window between the `DELETED` store and the reinsert's control publish |

`clear()` swaps in a fresh empty table (atomic store) — readers finish on the old
snapshot, exactly like a resize.

## 3. Atomics substrate — DP-13b: stdlib `kotlin.concurrent.atomics`, not atomicfu

Requirements, in common KMP code across `jvm`, `macosArm64`, `linuxX64`,
`linuxArm64`, `mingwX64`, `iosArm64`, `iosSimulatorArm64`: an atomic reference field
(table publication), an atomic `Long` array (control words), an atomic reference
array (values), an atomic `Int` (entry count).

**Decision (recon-verified against the actual artifacts, amending DP-13's
"via kotlinx-atomicfu"):** use the Kotlin 2.3 stdlib **`kotlin.concurrent.atomics`**
package (`@ExperimentalAtomicApi`), not atomicfu. Grounds:

- **atomicfu 0.29.0 without its compiler plugin boxes every array element** —
  `AtomicLongArray` is literally `Array(size) { atomic(0L) }` in its common source
  (one heap box per element; two per element on Native). Disqualifying for a map
  whose hot path is scanning a flat `Long` array and whose headline win is memory.
- **atomicfu with the plugin** reaches flat layout on JVM only through a Beta
  Gradle+compiler plugin ("no strict compatibility guarantees between Kotlin
  versions"), with usage constraints, and Native IR transformation is off by
  default. All cost, no benefit over stdlib.
- **stdlib atomics are flat and free:** on JVM they are typealiases of the
  `java.util.concurrent.atomic` classes (verified: no Kotlin class files exist;
  `loadAt`/`storeAt` compile to direct `invokevirtual` on `j.u.c.a.AtomicLongArray`
  over a flat `long[]`). On Native they are flat arrays: loads and primitive stores
  compile to direct LLVM seq-cst atomic instructions; *reference* stores
  (`values.storeAt`, the table swap) go through the runtime's
  `UpdateVolatileHeapRef` — still a genuine seq-cst store, wrapped in GC write
  barriers (a function call on the writer's value-store path; relevant when reading
  Native writer-side benchmark numbers, not for correctness). Available since
  Kotlin 2.1, unchanged through 2.4.
- **Ordering (review-corrected):** stdlib exposes only volatile/seq-cst accesses in
  common code. Seq-cst is *required*, not merely sufficient, for the stores that
  carry the §1 claims: the table-reference, control-word, and value stores need
  real-time visibility (a completed `put` must be visible to a `get` that starts
  afterwards). Only the *per-slot* key/value-before-control chains would survive a
  release-only store; the freshness guarantees would not. Therefore **no
  `lazySet`-style weakening is sanctioned**, on any path. The residual cost is the
  StoreLoad fence on x86 volatile stores, confined to the single-writer path; the
  writer's own seq-cst *loads* are near-free on both x86 and ARM64, so no
  writer-private caching is warranted in this phase.
- **Experimental-status containment:** all atomics stay `private` inside the map, so
  `ExperimentalAtomicApi` never appears in the public API; the `@OptIn` is file-local.
  The residual risk (stdlib binary-compat drift at stabilization) is contained
  because the stdlib version ships with the consumer's own Kotlin.
- Rebuilds construct plain `LongArray`/`Array<Any?>` off-line at full speed and wrap
  them via the copying `AtomicLongArray(LongArray)` / `AtomicArray(Array<T>)`
  constructors. Review confirmed no unfenced path to the pre-copy arrays: they stay
  writer-local, the copies are reachable only through the seq-cst table publication,
  and single-writer program order covers the constructors' plain copy loops.

`buildSrc`'s `AtomicFu` object stays (it is still needed as a forced transitive
version for the native test klib compile), but the library takes no atomicfu
dependency.

## 3a. Lincheck (recon-verified)

`org.jetbrains.lincheck:lincheck:3.6` (the 2.x line under `org.jetbrains.kotlinx` is
frozen at 2.39; 3.x moved group and packages — DSL in
`org.jetbrains.lincheck.datastructures`). JVM-only, `jvmTest`-scoped, new
`io.spine.dependency.test.Lincheck` object. No `--add-opens`/`--add-exports` needed
on JDK 17 (3.x self-attaches its agent). Framework-agnostic (`check()` throws an
`AssertionError` subclass), so plain JUnit 5 `@Test` works. The SWMR restriction is
expressed with `@Operation(nonParallelGroup = "writer")` on every mutating operation
— Lincheck assigns all actors of a non-parallel group to a single thread of the
parallel part (verified in its generator source); the sequential init/post parts run
on the main thread with happens-before edges from thread start/join, which satisfies
the "externally synchronized" writer contract. Built against Kotlin 2.2/coroutines
1.7.3/atomicfu 0.27.0 — all below this repo's forced versions, resolving upward,
no expected clash. **`size`/`isEmpty` must not be free parallel operations** (§1:
they are not linearizable, and the checker would correctly reject them); they are
validated in sequential positions only.

## 4. API

```kotlin
public class SingleWriterSwissLongMap<V> public constructor(
    expectedSize: Int = 0,
    private val hasher: LongHasher = LongHasher.Default,
) : OpenAddressingLongMap<V>
```

Same interface, same constructor shape as `SwissLongMap`. The name spells out the
single constraint the caller must uphold (one writer); many lock-free readers is the
benefit, not the obligation (review: prefer the spelled-out Agrona style —
`OneToOneConcurrentArrayQueue` — over a `Swmr` initialism). The KDoc carries:

- the model — one writer (or externally serialized writers), lock-free concurrent
  readers — with the single-writer requirement in **bold**;
- the scoped linearizability claim (§1) and the weakly consistent `size`/`isEmpty`;
- the requirement that a custom [`LongHasher`] be **pure and thread-safe** (readers
  hash concurrently; `LongHasher.Default` and `Fibonacci` qualify);
- the requirement to **safely publish the map instance itself** to reader threads
  (thread start, an atomic/volatile reference, or another happens-before edge) —
  ordinary practice, but on Kotlin/Native there is no final-field safety net;
- the churn trade-off (§2 change 4): removals reclaim space only at rebuilds.

Implementation notes: all shared holders (`tables` reference, `entryCount`) are
`val` fields and `this` never escapes the constructor. No DP-numbers, phase labels,
or repo-internal paths in the public KDoc. `OpenAddressingLongMap`'s "implementations
in the initial phase are single-threaded" wording is updated to describe both forms.

**Class shape vs detekt (decided now):** `findSlot`, `matchingSlot`, `reinsert`, and
the lane-publish helper move onto the private `Tables` holder (which receives the
precomputed hash/fingerprint, staying hasher-free): main class ≈ 7 functions,
`Tables` ≈ 5 — both clear of the 11-function ceiling. The `put` probe keeps the
repo-precedent one-line-justified `LoopWithTooManyJumpStatements` suppression. An
`internal val currentCapacity` seam (reads the current table's capacity) is the test
observable for rebuild/capacity assertions; `internal` is invisible to explicit-API
consumers outside the module.

## 5. Tests

- **`SingleWriterSwissLongMapSpec`** (commonTest): mirrors `SwissLongMapSpec` (a
  correct concurrent map is first a correct map), **plus the divergence pins no
  Phase-1 test shape reaches** (review-mandated):
  1. *Re-inserting a removed key never resurrects the stale entry* —
     `put(k, v1); remove(k); put(k, v2)` returns `null`, `get(k) == v2`, `size == 1`;
     run with the default hasher **and** with `hasher = { 0L }` so the stale,
     never-zeroed key at the `DELETED` lane sits on the same probe chain.
  2. *Constant-hash churn crosses a forced rebuild without resurrecting stale keys* —
     remove + re-insert cycles sized to exhaust `growthLeft` and force ≥ 1 rebuild;
     all live keys map to their latest values, removed keys stay absent after the
     swap (pins "remove never zeroes keys" and "rebuild copies FULL lanes only").
  3. *Churn capacity bounds, quantitatively* (via `currentCapacity`): single-key
     churn from default capacity over ≥ 3×`maxLoad(8)` rounds keeps capacity 8
     (same-capacity tombstone reclaim works); churn holding 600 live entries in a
     map pre-sized for 600 (capacity 1024) doubles exactly once to 2048 and stays
     (the intended one-time doubling of §2 change 4).
- **`SingleWriterSwissLongMapPropertiesSpec`** (commonTest): differential vs
  `LinkedHashMap`, ported from the Phase-1 spec — the randomized backstop for
  resurrection bugs (its clustered 0..63 domain re-inserts removed keys densely) and
  the model-replay end-state oracle.
- **`SingleWriterSwissLongMapStressSpec`** (commonTest, runs on JVM **and Native** —
  the only suite exercising the Kotlin/Native memory model; on this host that means
  macosArm64 + iosSimulatorArm64): writer + N reader coroutines launched explicitly
  on `Dispatchers.Default` (virtual time deliberately not used), explicit generous
  `runTest` timeout, modest reader count (Native's pool = core count), iteration
  constants in one place so CI can size them down.
  - *Value integrity:* values are multi-field objects (`Payload(a, b)` with
    `a == b == key`) so the value-*contents* safe-publication claim is exercised on
    Native; every non-null read must decode to its key (catches wrong-slot/torn
    reads).
  - *Monotonic presence:* writer inserts ascending keys (no removals in the phase);
    a reader that observed key `k` present must find every sampled key `< k` present
    and `k` present on re-read (catches entries lost across a resize).
  - *Churn phase:* mixed inserts/removes; value integrity holds throughout; after
    joining, the final content equals a model replay of the writer's op log.
  - *Clear phase:* `clear()` under concurrent readers (the swap-without-copy
    publication, otherwise Lincheck/JVM-only).
  - Small initial capacity so the run crosses many rebuilds; explicit test timeout
    doubles as the livelock guard.
- **`SingleWriterSwissLongMapLincheckSpec`** (jvmTest, JUnit 5 conventions per
  DP-5): stress + model-checking modes; `put`/`remove`/`clear` in one
  `nonParallelGroup` ("writer"), `get`/`containsKey` free; **two configurations**:
  1. *resize-crossing*: the instance pre-filled with 7 distinct keys (capacity 8,
     `growthLeft == 0`), so the first fresh-key put in the parallel part rebuilds
     (7 > `rehashThreshold(8) = 3` → doubles to 16) and publishes the copied table
     mid-race — readers concurrently probe the very entries being copied;
  2. *empty-start*: the cold paths.
  Keys narrowed to a small window (e.g. `1..16`) so readers collide with the
  pre-filled entries; iterations/invocations bounded for CI. `size` is validated
  only in sequential positions (§3a). One-off (removed after verification): confirm
  the resize-crossing configuration actually rebuilds inside the parallel part.

## 6. Benchmarks

- **`SingleWriterSwissLongMapBenchmark`** (existing kotlinx-benchmark module, JVM +
  Native): single-threaded `lookupHit`/`lookupHitShuffled`/`lookupMiss`/`insert…` vs
  `SwissLongMap` — the price of seq-cst control/value reads on the uncontended read
  path (the honest "what does the concurrent variant cost when you don't need it"
  number). On Native, writer-side numbers include the `UpdateVolatileHeapRef` cost
  (§3).
- **`benchmarks-jvm`** (new raw-JMH JVM module — the DP-4 second tier, first needed
  now; kotlinx-benchmark exposes no `@Threads`/`@Group`): read-scaling lookup
  throughput at 1/2/4/8 threads on a pre-populated map, plus asymmetric `@Group`
  benchmarks (1 writer / N readers). Review-mandated controls: `@Fork ≥ 3`;
  per-thread key cursors in `@State(Scope.Thread)`; results returned/blackholed per
  invocation; the writer workload defined explicitly and split into *overwrite-only*
  (steady state, no rebuilds) and *churn* (rebuild-heavy — the honest stress case
  for no-tombstone-reuse); baselines at the same thread counts:
  `ConcurrentHashMap<Long, V>` and a `synchronized`-wrapped `SwissLongMap`;
  throughput mode only (no latency claims). Module bookkeeping: like `benchmarks`,
  applies KMP-free plain JVM plugins directly (no `kmp-module`, no detekt task),
  needs a `settings.gradle.kts` entry, and the build regenerates
  `docs/dependencies/*` for it (commit those; the license-report gate will want
  Lincheck 3.6 and JMH rendered).

## 7. Review outcome — GO-WITH-CHANGES (all folded in above)

Four adversarial lenses (JVM memory model, Kotlin/Native memory model,
linearizability, plan/API quality); confirmed-major fixes all reflected:
scoped linearizability + weakly consistent `size` (§1, §3a, §5), the named
table-state-monotonicity invariant (§2 change 4), the divergence-pin tests and
`currentCapacity` seam (§5, §4), the pre-filled Lincheck resize-crossing
configuration (§5), and the `lazySet` escape hatch removed (§3). Verified answers
to the original open points: the Native ordering argument is sound (message-passing
pattern, data-race-free by monotonicity); the rebuild-wrap trick has no unfenced
path; reader termination is bounded, not just finite; the null-on-remove transient
is unobservable through the current surface but constrains the Phase-5 view (§2.1).
One stress-spec finding ("quiescent end-state equality missing") was refuted in
verification — the ported properties spec is that oracle — but its cheap hardenings
(op-log replay, clear-under-readers phase, `Payload` values, explicit timeout) were
adopted anyway (§5).

**PR checklist reminders:** bump `version.gradle.kts` (once per branch, at PR time —
DONE: `1.0.0-SNAPSHOT-009`); regenerate `docs/dependencies/*` (DONE, committed);
run `dokkaGenerate` (DONE, green); update `docs/project.md`'s status line and
`docs/performance-goals.md` with a Phase-4 section (DONE).

## 8. What shipped — verification results

- **`SingleWriterSwissLongMap<V>`** (commonMain; the review's naming fix over the
  draft's `SwmrSwissLongMap`), with probe helpers on the private `Tables` holder
  (main class 8 functions, `Tables` 5 — both clear of the detekt ceiling), the
  `internal currentCapacity` test seam, and the atomics confined behind a
  file-level `@OptIn(ExperimentalAtomicApi::class)`.
- **Tests, all green on JVM + macosArm64 + iosSimulatorArm64:**
  - `SingleWriterSwissLongMapSpec` — 17 contract tests including the divergence
    pins (no stale-entry resurrection under default and constant hashers;
    constant-hash churn across forced rebuilds; single-key churn holds capacity 8;
    600-live churn doubles exactly once to 2048; the 8th insert rebuilds at
    capacity 8 — the geometry the Lincheck resize configuration relies on).
  - `SingleWriterSwissLongMapPropertiesSpec` — differential vs `LinkedHashMap`
    over clustered, wide, and constant-hash domains.
  - `SingleWriterSwissLongMapStressSpec` — 1 writer + 3 readers on
    `Dispatchers.Default`: monotonic presence across ~14 rebuilds × 4 runs,
    churn with a model-replay end-state check, repeated `clear()` under readers;
    multi-field `Payload` values prove contents publication on Native.
  - `SingleWriterSwissLongMapLincheckSpec` (jvmTest) — 6 tests: stress + model
    checking over the three configurations (empty, resize-crossing pre-fill,
    constant-hash), `size`/`isEmpty` excluded per §1, plus the custom scenario
    `put(3) → parallel(remove(3) | get(0))` under the constant hasher.
- **Mutation experiment (the harness has teeth):** re-introducing the guarded bug
  (`keys[slot] = 0` on remove) is NOT caught by the randomized scenarios — the
  race needs two context switches plus a fingerprint collision — but the custom
  scenario catches it immediately and deterministically, producing exactly the
  predicted violation (`get(0): 42` returning key 3's value through the zeroed
  slot). Lesson recorded: Lincheck's random scenario generation does not reach
  narrow multi-switch races; pin them with `addCustomScenario`.
- **Smoke benchmarks** (1 fork, noisy machine; authoritative matrix at Phase 6):
  read scaling 35.6 → 139.6 → 308.9 ops/µs at 1/4/8 reader threads (~linear;
  `ConcurrentHashMap` comparable at 282.6; lock-wrapped `SwissLongMap` collapses
  to 5.5); single-threaded lookup tax vs `SwissLongMap` ~1.3–1.5× at 1M, insert
  ≈ parity. Recorded in `docs/performance-goals.md` §Phase 4.
- **Reviews:** post-implementation panel — `kotlin-engineer`,
  `spine-code-review`, `review-docs`, `dependency-audit` (all APPROVE / APPROVE
  WITH CHANGES; every requested change applied: named arguments at the
  `LongArray`-adjacent call sites, JMH-module dedup into `MapAdapters.kt`,
  KDoc count/enumeration fixes, Kotest null-assertion idioms, phase-label scrub
  in `SwissLongMap` KDoc, usage example added) — plus an independent max-effort
  concurrency verification that re-derived all seven §1–§3 claims from the code:
  **all VERIFIED, no defects**.
- **Deferred / follow-ups:** the `benchmarks-jvm` allopen plugin version is
  pinned like the sibling `benchmarks` module (drifts silently on a Kotlin bump;
  fix both when the pin next changes); authoritative multi-fork benchmark matrix
  and `-prof gc` runs land with Phase 6; the §2.1 phantom-`(key → null)` note
  constrains the Phase-5 boxed `MutableMap` view.
