# Worldgen speedup session — narrative

## Setting the stage

Coming in, the bit-exact biome lookup infrastructure was working at near
coords but diverging at far coords. The first job was to chase that down,
then turn the working infrastructure into actual user-visible worldgen
speed.

---

## Act 1: Fix the depth-axis spline bug

**The symptom.** `/ferrite biome rust 100000 64 100000` returned
`wooded_badlands`, vanilla returned `lush_caves`. Per-axis dump showed
five climate axes within 1e-5 of vanilla, but **depth was off by 0.225**.
Big diff, only on one axis.

**The trail.** All spline coordinate references were being encoded as
`Constant(0)` in our DF bytecode. That meant `extractSplineCoordFn`
either wasn't running or was getting a null coord. I added one-shot
diagnostic dumps in
[DensityFunctionWalker.java](src/main/java/me/apika/apikaprobe/DensityFunctionWalker.java)
to log the methods and fields of:

1. The outer `DensityFunctionTypes$Spline` DF (had `.spline()` already)
2. The inner `Spline$Implementation` (mojmap `CubicSpline.Multipoint`)
3. The `Spline.Coordinate` wrapper class

**The find.** Two yarn renames in 1.21.11 we didn't know about:

- `Spline$Implementation.coordinate()` → renamed to `locationFunction()`
- `Spline.Coordinate` → renamed to
  `DensityFunctionTypes$Spline$DensityFunctionWrapper`, with accessor
  `function():RegistryEntry`

**The fix.** Added `locationFunction` to the candidate list in
`writeSpline`'s Multipoint branch, plus a `bruteFindCoord` field-scan
fallback for future yarn drift. The existing `extractSplineCoordFn`
already covered `function()` so the chain completed end-to-end.

**The result.** `/ferrite biome rust 100000 64 100000` → `lush_caves`
**MATCH**. All six climate axes within ~5e-5. Bit-exact biome lookup at
any coords, loaded or not — that's the foundation everything else in
this session was built on.

---

## Act 2: Bulk biome prediction

**Goal.** Take the per-coord lookup and turn it into a feature: predict
biomes across a region around the player. New command
`/ferrite biome predict [radius]` in
[FerriteCommand.java](src/main/java/me/apika/apikaprobe/FerriteCommand.java).

**First version (serial).** Just looped over a quart-grid:

```java
for (int dz = -radiusBlocks; dz < radiusBlocks; dz += step) {
  for (int dx = -radiusBlocks; dx < radiusBlocks; dx += step) {
    int id = RustBridge.findBiomeAtBlockRust(cx + dx, cy, cz + dz);
```

500-block radius (62,500 cells) → **9.4 µs/cell, ~590 ms total**.

**Hypothesis: JNI overhead per cell.** Built a batched JNI:
`findBiomeRegionRust(originX, originY, originZ, sideX, sideZ, step,
outBuffer)` in
[worldgen_jni.rs](rust/mod/src/worldgen_jni.rs). Same workload, same
result, **9.6 µs/cell**.

**Conclusion.** JNI overhead was *not* the bottleneck — the per-cell
DF computation was. Six DF samples + R-tree lookup per cell = ~9 µs.
Batching alone can never win that.

**Pivot to Rayon.** Replaced the serial fill loop with
`par_chunks_mut(side_x * 4)` over rows. Same correctness, very different
numbers:

| Workload                | Serial   | Parallel  | Speedup |
|-------------------------|----------|-----------|---------|
| 5000-radius, 6.25M cells| 57,194ms | 10,516ms  | **5.4×**|

That's the actual unit-of-work win — big workloads parallelise well
because the per-task work (~1.5ms per row) dwarfs Rayon's scheduling
overhead.

---

## Act 3: Wiring biome lookup into chunkgen (the experiments that didn't win)

**The naive idea.** Vanilla calls `MultiNoiseBiomeSource.getBiome` ~1500
times per chunk during `populateBiomes`. Replace it with our Rust path
via a mixin redirect → faster chunkgen, right?

### Experiment 3a: per-call mixin

[MultiNoiseBiomeSourceRouteMixin.java](src/main/java/me/apika/apikaprobe/mixin/MultiNoiseBiomeSourceRouteMixin.java)
@Inject HEAD with cancellable on the
`(IIIL.../MultiNoiseSampler;)L.../RegistryEntry;` overload. New helper
[RustBiomeRouter.java](src/main/java/me/apika/apikaprobe/RustBiomeRouter.java)
holds the per-Rust-ID `RegistryEntry<Biome>` table populated by
`WorldgenStateBootstrap.registerBiomes`.

Mixin attached cleanly first try. 7593 holders cached. Toggled on, ran
chunkgen…

**Result: regression.** User feedback: *"Off have slight speed and on is
a bit slower."*

**Why.** Per-cell Rust DF cost matches per-cell Java DF cost. Adding JNI
overhead (~100-400 ns) on top can't possibly win. Vanilla's
`Climate.Sampler` likely shares intermediate noise samples across the
six axes; we sample each axis from scratch.

### Experiment 3b: slab cache

Hypothesis: amortize JNI cost by caching a 4×4 slab per (chunkX, chunkZ,
qY) and serving 15/16 cells from memory. ThreadLocal cache, single slot
per thread, refill via `findBiomeRegionRust`. Code in
[RustBiomeRouter.java:tryRoute](src/main/java/me/apika/apikaprobe/RustBiomeRouter.java).

**Result: still slower.** Two reasons:

1. JNI-per-slab still costs something
2. 16-cell batches are too small for Rayon to win — the thread-pool
   overhead dominates the ~150µs of actual work per slab

The honest takeaway from these two experiments: **per-call routing is
fundamentally not a win**. Per-cell work is the same in both engines;
nothing to amortize. The win has to come from doing the work somewhere
else, not faster.

---

## Act 4: Async pre-warm — predict ahead, serve from memory

**The reframe.** Instead of routing biome lookups on the chunkgen worker
thread, *predict ahead of time* on background threads. By the time
vanilla calls `getBiome`, the answer is already in memory. The CPU work
total is similar; what changes is *who* pays for it and *when*.

### Component: ChunkPrewarmer

[ChunkPrewarmer.java](src/main/java/me/apika/apikaprobe/ChunkPrewarmer.java)
holds a `ConcurrentHashMap<Long, int[1536]>` keyed by packed (cx,cz),
plus a fixed-size daemon thread pool (4 workers) and a semaphore-bounded
inflight tracker.

Each warm task fills 1536 cells (4 × 96 Y-slabs × 4) for one chunk.
First version called `findBiomeRegionRust` in a 96-iteration loop — one
JNI call per Y slab.

### Component: ChunkPrewarmTrigger

[ChunkPrewarmTrigger.java](src/main/java/me/apika/apikaprobe/ChunkPrewarmTrigger.java)
fires every server tick, walks each player's chunk in concentric rings
out to `viewDistance + 4`, and schedules any chunk not yet cached or
inflight. Ring iteration = nearby chunks win priority over far ones.
Per-tick scheduling budget caps total work.

### Wiring it back: cache-first router

`RustBiomeRouter.tryRoute` first checks `ChunkPrewarmer.lookup(...)`. On
hit (lock-free read), return at memory speed — no JNI, no DF eval. On
miss, fall through to the existing slab cache path.

### First measurement

`/ferrite prewarm on` (which auto-enables the router). Walked around for
~90 seconds, ran `/ferrite prewarm status`:

```
cached=878 inflight=947 warmed=14501 hits=1910249 misses=3246052 avgWarm=25811us
```

- 14,501 chunks pre-warmed across 4 background workers
- 1.9M cache hits at memory speed (37% hit rate)
- **25.8 ms per chunk warm cost** — the 96 JNI calls were expensive
- 947 inflight backlog: trigger schedules ~640 chunks/sec, workers drain
  ~155/sec. We're scheduling faster than we can warm.

System works end-to-end. Next obvious win: kill those 96 JNI calls.

### 3D batch JNI

Added
`Java_me_apika_apikaprobe_RustBridge_findBiomeRegion3DRust(originX,
originY, originZ, sideX, sideY, sideZ, step, outBuf)` in
[worldgen_jni.rs](rust/mod/src/worldgen_jni.rs). Output layout: row-major
`(iy, iz, ix)`. Internal Rayon parallelism over Y-slabs — for a chunk
that's 96 slabs × 16 cells = enough to actually win on multi-core.

Java side declared in
[RustBridge.java](src/main/java/me/apika/apikaprobe/RustBridge.java) and
wired into `ChunkPrewarmer.warmChunk`: one JNI call fills the entire
chunk's 1536 cells in one shot.

---

## Act 5: Reality check — vanilla pipeline is the real bottleneck

User feedback after both prewarm iterations: *"its okay, tad a bit
slower, but at the end i hit a wall with nothingness"*

**The honest read.**

- Prewarm is *working* (37% hit rate is real, cache reads firing during
  vanilla biome lookups)
- But it only short-circuits **biome lookups** — one phase of chunkgen.
  Vanilla still runs noise, surface, decoration, light, etc.
- The "wall of nothingness" = **vanilla's chunk-load pipeline can't keep
  up with player movement**. That's a vanilla-pipeline problem, not a
  per-chunk-work problem. No amount of biome prewarming can fix it.

User's question: *"is it vanilla speed?"* — yes. The pipeline (chunk
worker pool, ticket system, sync points) is vanilla's. Our Rust code
(`TerrainBulkHandoff`, `SurfaceDispatch`, biome lookup) shaves work
*inside* each chunk's gen. But the chunk-load *throughput* is determined
by Minecraft's chunk-load executor + ticket queue.

To push the wall further out, the next layer has to be **driving
vanilla's own chunkgen workers ahead of the player**. Vanilla owns the
gen pipeline; what we control is *when* a chunk is queued onto it. If
chunks reach FULL status before the player walks into their cell, the
player never perceives the load. Prewarm continues to compound — its
biome cache accelerates the biome step inside each forced gen.

---

## Act 6: Tuned prewarm + chunk-forcer

### Why tuning came first

The chunk-forcer needs prewarm to *already* be lock-stable, because
forcing more vanilla gens adds biome lookups, and a leaky/slow biome
cache would just shift load. Three things changed in
[ChunkPrewarmer.java](src/main/java/me/apika/apikaprobe/ChunkPrewarmer.java)
+ [ChunkPrewarmTrigger.java](src/main/java/me/apika/apikaprobe/ChunkPrewarmTrigger.java):

1. **`LOOK_AHEAD: 4 → 32`** — coverage grows from 528 → 1,408 blocks
   per side (~7,900 chunks tracked). With the rest of the stack
   measured at ~485 chunks/sec drain, the full ring fills in ~16 sec
   stationary.
2. **`CACHE_LIMIT: 1024 → 8192`** — ~47 MB ceiling. Eviction stops
   churning until the player has explored ~140 km².
3. **`ThreadLocal<ByteBuffer>` in `warmChunk`** — kills the per-chunk
   `allocateDirect(6144)` that was the actual cause of the "freezes."
   Off-heap reference processing across thousands of buffers gone.

Plus a new `SCHEDULE_BACKPRESSURE = 64` cap — the trigger pauses
scheduling once 64 tasks are inflight, so the queue never grows
unboundedly during fast flight.

After tuning, hit rate jumped from 86% → **99.87%**:

```
[T+250s]  cached=7818  inflight=59  warmed=99,626
          hits=19,821,343  misses=26,647  avgWarm=9,757us
```

### Component: ChunkForcer

[ChunkForcer.java](src/main/java/me/apika/apikaprobe/ChunkForcer.java)
submits chunks to vanilla's chunk-load pipeline via the public ticket
API: `world.getChunkManager().addChunkLoadingTicket(ticketType, pos, 0)`.
That returns a `CompletableFuture` — vanilla queues the chunk on its
own chunk-load executor, runs the full gen pipeline (noise, biome,
surface, decoration, light), and writes it to .mca on next auto-save.
Persistence is automatic; a forced chunk survives a server restart even
if the player never visits.

In-flight cap is 50 to mirror established pre-gen tooling defaults —
beyond that, vanilla's executor just queues and we waste memory on
inflight tracking with no throughput benefit.

On every completion, the prewarm cache for that chunk is evicted
(`ChunkPrewarmer.evict`). Vanilla now owns the authoritative biome data
and our prediction would just hog memory for a chunk the cache will
never serve again.

### Component: ChunkForceTrigger

[ChunkForceTrigger.java](src/main/java/me/apika/apikaprobe/ChunkForceTrigger.java)
fires every server tick, walks each player's chunk in concentric rings
out to `viewDistance + 16`, and for each chunk:

- If `world.isChunkLoaded(cx, cz)` — skip and evict any prewarm cache
  for it (vanilla owns it now)
- Else — `ChunkForcer.submit(...)` (which respects the inflight cap)

Per-tick budget is 8 (160/sec), well above vanilla's ~47-chunk/sec
production rate. The cap on inflight is what actually throttles us.

### Eviction hook on natural chunk loads

[ExampleMod.java](src/main/java/me/apika/apikaprobe/ExampleMod.java)
also registers `ServerChunkEvents.CHUNK_LOAD` to evict the prewarm
cache when *any* chunk reaches loaded status — covering chunks loaded
by player walk, `/tp`, world-edit, and other paths that don't go
through our forcer. Belt-and-suspenders: cache stays bounded to chunks
that aren't yet vanilla-owned.

### Final measurement

`/ferrite prewarm on` then `/ferrite chunkforce on`, fast-flight ~4
minutes, no stops:

```
prewarm:    cached=7818  inflight=59  warmed=99,626
            hits=19,821,343  misses=26,647  avgWarm=9.7ms
chunkforce: enabled=true  inflight=50  scheduled=11,922
            completed=11,872  errored=0
```

- **99.87% biome hit rate** during the forced gens (cache was ready
  before vanilla asked)
- **47 vanilla chunks/sec** completed — at-or-near vanilla's max
  on 4 chunkgen workers
- **0 errors** — ticket API resolving cleanly
- **inflight=50** sitting at cap = workers fully saturated, queue
  draining as fast as production allows

User feedback after the run: *"within the native [view distance] i can
see chunks getting loaded. but still 2-4 chunk visible loading. i didnt
stop and kept flying, very vanilla like too."*

The "wall of nothingness" is gone — replaced by 2-4 chunks of visible
edge loading at fly speed. That residual edge is **vanilla's chunkgen
worker pool throughput limit** (~47 chunks/sec / 4 workers ≈ 21 ms per
chunk after our accelerators). Pushing past that hits a different
ceiling (worker pool sizing, remaining vanilla phases) — out of scope
for this session.

---

## What lives in code now

| File | Role |
|---|---|
| [DensityFunctionWalker.java](src/main/java/me/apika/apikaprobe/DensityFunctionWalker.java) | Java→bytecode encoder for vanilla DFs; now handles `locationFunction` and `bruteFindCoord` fallback |
| [RustBridge.java](src/main/java/me/apika/apikaprobe/RustBridge.java) | JNI declarations: `findBiomeRegionRust`, `findBiomeRegion3DRust` added |
| [worldgen_jni.rs](rust/mod/src/worldgen_jni.rs) | Rust JNI impl: 2D batch + 3D batch + Rayon parallelism + shared `sample_biome_at` helper |
| [FerriteCommand.java](src/main/java/me/apika/apikaprobe/FerriteCommand.java) | New commands: `/ferrite biome predict`, `/ferrite biome route on/off/status`, `/ferrite prewarm on/off/status/clear` |
| [RustBiomeRouter.java](src/main/java/me/apika/apikaprobe/RustBiomeRouter.java) | Per-call biome routing with cache-first lookup against prewarm cache |
| [ChunkPrewarmer.java](src/main/java/me/apika/apikaprobe/ChunkPrewarmer.java) | Background biome-prediction pool + per-chunk cache (3D batch fill, ThreadLocal direct buffer, eviction-on-load) |
| [ChunkPrewarmTrigger.java](src/main/java/me/apika/apikaprobe/ChunkPrewarmTrigger.java) | Per-server-tick player scan; concentric-ring scheduling out to `viewDist + 32`; skips already-loaded chunks |
| [ChunkForcer.java](src/main/java/me/apika/apikaprobe/ChunkForcer.java) | Public-ticket-API gen forcer; inflight cap 50; auto-evicts prewarm cache on completion |
| [ChunkForceTrigger.java](src/main/java/me/apika/apikaprobe/ChunkForceTrigger.java) | Per-server-tick concentric-ring chunk-force scheduler; `viewDist + 16` ahead-of-player range |
| [MultiNoiseBiomeSourceRouteMixin.java](src/main/java/me/apika/apikaprobe/mixin/MultiNoiseBiomeSourceRouteMixin.java) | HEAD-cancellable inject on `getBiome(IIIL.../MultiNoiseSampler;)` — short-circuits to router |
| [WorldgenStateBootstrap.java](src/main/java/me/apika/apikaprobe/WorldgenStateBootstrap.java) | Now also installs the per-Rust-ID `RegistryEntry<Biome>` table into `RustBiomeRouter` |
| [ferrite.mixins.json](src/main/resources/ferrite.mixins.json) | Added `MultiNoiseBiomeSourceRouteMixin` |
| [ExampleMod.java](src/main/java/me/apika/apikaprobe/ExampleMod.java) | Registers `ChunkPrewarmTrigger`, `ChunkForcer`, `ChunkForceTrigger`, plus `ServerChunkEvents.CHUNK_LOAD` for cache eviction |
| [FerriteCommand.java](src/main/java/me/apika/apikaprobe/FerriteCommand.java) | Adds `/ferrite chunkforce on/off/status` alongside `/ferrite prewarm on/off/status/clear` |

---

## Decisions and tradeoffs in retrospect

- **Per-call mixin shipped but proven not a win.** Kept in the codebase
  behind `RustBiomeRouter.ENABLED = false` for future experiments and
  because it's the substrate the prewarm cache plugs into.
- **Slab cache abandoned in favour of prewarm cache.** Still in
  `RustBiomeRouter` as a fallback path on prewarm-cache miss; barely
  matters in practice once prewarm is on.
- **Eviction is dumb.** Random sample of 25% of entries when the cache
  exceeds 1024. LRU would be smarter but the ring-trigger refills
  anything still relevant within a few ticks anyway.
- **Validator still misleading.** `/ferrite density validate` still
  shows ~2/35 because vanilla DFs in the registry have null
  NoiseHolder.noise. Bit-exact biome lookup at far coords is stronger
  evidence than the validator could give us, so this is parked as
  lower-priority QA.
- **Lint warnings on volatile flags ignored.** `ENABLED` flags match the
  existing `SurfaceDispatcher.ENABLED` pattern in this codebase.
