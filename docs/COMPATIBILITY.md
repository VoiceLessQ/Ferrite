# Ferrite compatibility notes

How Ferrite's threading model holds up when paired with mods that
parallelize chunk generation across worker threads, or with mods that
rewrite vanilla hot paths in place (Lithium being the most common
example of the latter). This is an audit, not a stamp of approval.
The structurally safe parts are sound by construction. The unknown
parts are flagged as test gates so that when a user does run the
stack under load, we know what to look at.

Last refreshed 2026-05-05 (full code-level audit landed this revision;
prior revision was desk-level only). Update on each release that
changes the JNI surface, mixin set, or worldgen bootstrap path.

## Scope

The audit assumes three concrete pairing classes:

- **Ferrite + a concurrent-chunkgen mod**: any mod that runs
  Mojang's chunk-generation pipeline on a worker pool rather than
  the server main thread. Question: does any Ferrite state shared
  between server-main and chunk-gen workers race?
- **Ferrite + Lithium**: Lithium tightens vanilla Java hot paths in
  place via mixins (AI tasks, voxel shapes, allocations, biome noise
  cache, etc.). Question: do our mixins and Lithium's mixins target
  the same call sites in incompatible ways?
- **Ferrite + both**: combined.

Ferrite single-mod threading is also covered for completeness, since
the same scratch buffers and globals are exposed regardless of what
else is loaded.

## Tier 1: structurally safe

These hold by construction. No empirical run needed.

### Worldgen state (read path)

`rust/mod/src/worldgen_state.rs` holds the world state in
`OnceLock<WorldgenState>`. After `finalize_worldgen_init` returns,
the state is `&'static`, immutable, and contains only read-only
collections (`HashMap<String, NormalNoise>`, `RTree`,
`HashMap<String, DensityFunction>`).

Concurrent reads from any number of worker threads are sound by the
OnceLock contract. This covers the entire worldgen hot path: noise
sampling, biome lookup, density-function evaluation, aquifer queries.
If an external worker pool drives our chunkgen mixins from N
threads, each one reads the same `&'static` state and bounces to a
stateless Rust kernel.

### DF interpreter lazy nodes

`LazyEndIsland` and `LazyBlendedNoise` in `rust/mod/src/density.rs`
use `Arc<OnceLock<...>>` for first-call init. Thread-safe, and
deterministic since the world seed is the only input.

### Worldgen bootstrap

`WorldgenStateBootstrap` runs from a `ServerLevelEvents.LOAD` handler.
Single-threaded by Fabric's event dispatch. The `BUILDER` mutex is
held only during init, register, and finalize, all on that one
thread.

### Java-side worldgen statics

Audited via grep against `src/main/java/me/apika/apikaprobe/worldgen/`.
Every shared static is one of: `ConcurrentHashMap`, `AtomicLong`,
`AtomicBoolean`, `AtomicReference`, `volatile` field, or
`CopyOnWriteArrayList`. No plain `HashMap` or `ArrayList` on the
worldgen path.

`WorldgenStateBootstrap.identifiedRouterDfs` and `fingerprintToName`
are `volatile Map<...>` fields published as `Collections.unmodifiableMap(localMap)`
after the local map is fully built. Standard safe-publication idiom:
volatile reference + immutable wrapper + builder reference dropped
after assignment. Concurrent readers from chunkgen workers see a
fully-initialized read-only map.

`RustBiomeRouter.holdersById` is a `volatile Holder<Biome>[]` published
the same way: array filled by caller, then `install(...)` writes the
reference; the only post-install reference is the volatile field itself
and no code path mutates through it. Vanilla biome holders are
themselves immutable post-registry-freeze, which lands before any
chunk gen, so the array's contents are also frozen by the time a
reader sees the published reference.

### Rust-side concurrency posture

Code-level audit of the full Rust crate. Sync-primitive inventory:

- **`OnceLock` for write-once-then-read-forever publication.**
  `WORLDGEN_STATE` (the world's noise/biome/DF state) plus the
  `Arc<OnceLock<>>` cache cells inside `LazyEndIsland` and
  `LazyBlendedNoise`. Closures depend only on world seed and root
  factory, both stable per world; same closure inputs across threads
  → same outcome regardless of which thread wins the init race.
- **`Mutex` for the init-only build state.** `BUILDER:
  Mutex<Option<WorldgenBuilder>>` is held only during the
  init→register→finalize sequence driven from the Java LOAD handler,
  then drops back to `None`. Single-threaded by call pattern; Mutex
  serializes correctly even if it weren't.
- **`thread_local!` + `RefCell` for per-thread scratch.** Cramming
  (`CELL_HASH`), redstone (`POWER_BUF`), and AC redstone (four
  buffers) use this idiom for per-tick scratch space. Per-thread
  isolation by construction; no cross-thread sharing possible.

The DF interpreter's `compute(&self, ctx, state) -> f64` takes
immutable references throughout. Recursion through `Add`, `Mul`,
`Min`, `Max`, `Marker` etc. is pure functional with no per-call
accumulator or memoization side-table. The `MarkerKind` tags
(`FlatCache`, `Cache2D`, `CacheOnce`, `CacheAllInCell`) are recorded
in the bytecode but the interpreter doesn't actually cache based on
them — `Marker` always passes through to `wrapped.compute(...)`.
For threading purposes the lack of memoization is positive: no
shared state to race.

Crate-wide grep confirms zero `static mut`, zero plain `Cell<>`,
zero `OnceCell` (the non-thread-safe variant), zero `UnsafeCell`,
zero `AtomicX`, and zero `Mutex<T>` or `RwLock<T>` outside the
init-only `BUILDER`. Every interior-mutability primitive in the
crate is one of the three patterns above.

## Tier 2: safe today, latent risk

These are safe under the current vanilla execution model but rely
on assumptions that some future mod could break.

### Entity tick scratch buffers and counters

Code-level audit pass enumerated every non-`volatile`, non-`Atomic*`,
non-`Concurrent*` static field touched on the entity-tick path. Two
sub-classes by severity:

**Sub-class A — undercount-on-race, no game-state corruption.**
Per-tick accumulator counters that get incremented from per-call
mixins and drained on `END_SERVER_TICK`. Worst case under hypothetical
parallel entity ticking: lost increments produce slightly wrong log
numbers, no game state involved.

- `CrammingDispatcher.lastProcessedTick` and `diag*` counters
- `PhysicsDispatcher.diag*` counters (build / dispatch / fallback /
  rebuild / bucket counters)
- `PhysicsHandoff.cellScratch` (grow-on-demand `int[]`, written
  during snapshot build)
- Monitor accumulators: `thisTick*` `long`/`int` fields in
  `GoalSelectorMonitor`, `HopperMonitor`, `LookControlMonitor`,
  `MoveControlMonitor`, `TargetScanMonitor`, `TpsMonitor`,
  `MovementInternalsMonitor`, `RedstonePhaseMonitor` (8 classes).
  Same shape as the dispatchers: incremented from per-call mixin
  hooks, drained from `END_SERVER_TICK`.

**Sub-class B — JNI snapshot state, would corrupt on race.**
Higher severity, called out separately because the failure mode is
structural rather than statistical:

- `PhysicsDispatcher.currentlyLoadedBucketKey`
- `PhysicsDispatcher.currentlyLoadedTickId`
- `PhysicsDispatcher.currentWorld`
- `PhysicsDispatcher.BUCKETS` (HashMap)
- `PhysicsHandoff.STATE_TO_PALETTE`, `PALETTE_AABBS` (HashMap, ArrayList)
- `CrammingDispatcher.MOB_SCRATCH` (ArrayList)

The bucket cluster is the most fragile: `key != currentlyLoadedBucketKey`
triggers a Rust-side snapshot rebuild via JNI, then `currentlyLoadedTickId`
is incremented and used as a tag in the next request. If two workers
ran `adjust` concurrently on different buckets, they would thrash the
snapshot every call and the `key`/`tickId` write ordering would feed
inconsistent state to the JNI side. This class would actually
misbehave under parallelism, not just lose counts.

**Vanilla runs entity ticks on the server main thread, and no
mainstream concurrent-chunkgen mod we are aware of parallelizes
entity ticking.** Every sub-class A and sub-class B field is written
from per-entity-tick mixins or per-world-tick mixins, all dispatched
serially from `ServerLevel.tick`. Today this holds.

Note that the Rust side of these subsystems is already protected by
`thread_local!` (cramming, redstone). The latent risk here is
exclusively Java-side. If a mod ever parallelizes entity ticking,
the Rust buffers would just spin up per-thread copies; the Java
buffers would race.

Mitigation when that day comes:

- Sub-class A: replace plain `long`/`int` counters with `LongAdder`
  or move to `ThreadLocal<long[]>` and aggregate on tick end. Cost:
  small; benefit: undercount goes away.
- Sub-class B: replace `MOB_SCRATCH`-style scratch lists with
  `ThreadLocal<>` (mirrors the Rust side). For the
  `PhysicsDispatcher` bucket cluster, the structural answer is to
  redesign so the bucket key is per-snapshot rather than a static —
  passed as an argument through the JNI request. That work would
  land as part of any port discipline pass on entity ticking
  parallelism, since the redesign is non-trivial.

### Pre-chunk dispatcher

`PreChunkDispatcher.LAST_SUBMIT` and `LAST_POS` are plain
`Long2LongOpenHashMap` and `HashMap`. Hooked from
`ServerTickEvents.END_SERVER_TICK`. Server main thread only. Safe
under any current threading model.

## Tier 3: unknown, needs user testing

These cannot be resolved without running the stack under load. Each
is described with the specific signal that would confirm or refute
the concern.

### Mixin priority ordering vs concurrent-chunkgen rewrites

Concurrent-chunkgen mods typically rewrite the same vanilla classes
we hook into for worldgen acceleration: `MultiNoiseBiomeSource`,
`ChunkNoiseSampler`, and `Aquifer` are the common overlap surfaces.

**Risk:** if both mods inject without explicit mixin priority,
ordering is unstable and may differ between JVM runs, including
between client and dedicated server.

**How to test:** run a fresh world with both mods loaded, generate
a few hundred chunks, check the log for `[Mixin]` warnings about
overlapping injection sites. If warnings appear, set explicit
priorities in `ferrite.mixins.json` for the contested classes
(default mixin priority is 1000; bumping to 1100 puts us after
mods using defaults, dropping to 900 puts us before).

**Soft-degrade behavior:** none. A priority collision is a real bug
that would silently produce wrong worldgen. Test must run before
shipping the pairing.

### Volatile-shadow risk

Some thread-safety-focused mods rewrite vanilla fields to be
`volatile` via raw ASM transformation, to fix vanilla worldgen
races for their own concurrent execution. If we `@Shadow` such a
field and read it without carrying the volatile tag, x86 TSO
typically masks the bug; ARM may not, since ARM is weakly-ordered
and missing fences can produce stale reads.

**How to test:** scan `src/main/java/me/apika/apikaprobe/mixin/`
for `@Shadow` declarations on fields belonging to vanilla worldgen
or chunk-system classes. Cross-reference each against the field
list of the loaded thread-safety mod (most publish a list in their
own source). If a shadow target appears in their volatile-rewrite
list, add `volatile` to our shadow declaration.

**Soft-degrade behavior:** none. This is a memory-model bug that
would surface as occasional stale reads on weakly-ordered hardware,
visible only as worldgen artifacts at chunk boundaries.

### Lithium `mixin.gen.biome_noise_cache` vs `MultiNoiseBiomeSourceRouteMixin`

Both intercept the same biome-source call. Lithium wraps with a
cache, we redirect to Rust.

**Risk:** route-ordering decides which fires first. If Lithium runs
before our redirect, the cache holds and Rust is bypassed. If ours
runs first, the cache is populated by our Rust answer (which is
fine as long as our answer is bit-exact, which the climate parity
validator already confirms).

**How to test:** with both mods loaded, run the validator at boot:
`./gradlew runClient -Pferrite.autovalidate=2000`. If parity stays
1000+/1000+ on biome lookup, ordering is benign either way. If it
drops, the order matters and we need explicit priority.

**Soft-degrade behavior:** worst case, our redirect is bypassed and
biome queries run in vanilla + Lithium. No correctness loss, just
no Rust acceleration on that path.

### Non-overworld bootstrap timing gap

`WorldgenStateBootstrap.register()` runs only on `Level.OVERWORLD`.
If a concurrent-chunkgen mod parallelizes nether or end generation
ahead of the overworld `LOAD` event, our `WorldgenState` isn't
initialized yet.

**Soft-degrade behavior:** `worldgen_state()` returns `None`, our
mixins skip their Rust path, vanilla generates the chunk normally.
Correctness is preserved; performance for early non-overworld chunks
falls back to vanilla.

**How to test:** start a fresh server, immediately run
`/execute in minecraft:the_nether run tp ~ ~ ~`. Watch the log for
the `[worldgen-init] Rust worldgen state ready` line vs the first
nether chunk gen. If our soft-degrade path is healthy, no exceptions
are thrown.

**Mitigation if needed:** move bootstrap to `ServerStartedEvent`
(ahead of any `LOAD`), or run on the first `LOAD` regardless of
dimension and re-init only when the seed mismatches.

## Single-mod assumptions baked in

These are not pairing-specific but are worth listing because some
future mod could violate them:

- **One world per JVM lifetime.** `WORLDGEN_STATE` is a process-wide
  `OnceLock`. Re-loading a world (e.g., disconnect from one
  singleplayer world and load another in the same JVM) will hit
  "worldgen state already finalized" and silently soft-degrade to
  vanilla on the second world. Not a bug today (works fine for
  dedicated servers and for clients who restart between worlds), but
  worth knowing.
- **Server-main-thread-only entity ticks.** See Tier 2.
- **Bootstrap runs to completion before any chunk gen on the
  bootstrapped dimension.** Holds under vanilla (level load finishes
  before chunk requests are serviced). See Tier 3 for the
  non-overworld edge case under concurrent chunkgen.

## Recommended pairing posture

Until the Tier 3 items are resolved with logs from a real run:

- **Ferrite + Lithium**: low risk. Both are in-process Java mixins
  on read-mostly paths. The biome-noise-cache overlap is the only
  flagged item, soft-degrades cleanly. Run the autovalidator with
  both loaded to confirm parity.
- **Ferrite + a concurrent-chunkgen mod**: defensible on the
  worldgen read path, unknown on mixin priority and the
  volatile-shadow item, untested on non-overworld bootstrap.
  Recommend a test world with both loaded, fresh seed, autovalidate
  enabled, before claiming compatibility publicly.
- **Ferrite + Lithium + a concurrent-chunkgen mod**: the same
  caveats as the previous item. Lithium does not introduce new
  threading concerns on top.

## Audit cleanup notes (non-threading)

The code-level audit also surfaced one item that is not a threading
concern but is worth recording so it doesn't get re-discovered:

- `SurfaceValidator.lastReportTick` and its containing
  `onServerTick(long currentTick)` method are dead code. Zero
  callers found via `grep -rn "SurfaceValidator.onServerTick"`. The
  method's own doc comment confirms: "Currently unused — wire from
  a tick mixin if desired." Either delete the field and method, or
  wire the registration; do not leave it as-is. Listed here rather
  than in a tier because there is no thread that touches it, so
  there is no concurrency claim to make either way.

## Update protocol

When a user runs one of the pairings and reports back:

- Clean run with autovalidator passing: move the relevant Tier 3
  item to Tier 1 with a note linking the report.
- Failure or warning: capture the log line, identify which
  injection site or shared field was involved, add a test case
  under `tests/` if reproducible, file the fix on its own branch.
- Either way, update the "Last refreshed" line at the top.
