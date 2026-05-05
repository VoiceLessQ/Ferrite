# Future Plans

What's measured, what's not, and where improvement needs deeper research.
This doc is honest about uncertainty — "needs measurement" means exactly that,
not "we know this will win."

---

## The rule before anything here gets ported

Four checks, no exceptions:
1. Is vanilla actually the bottleneck? (profiler data, not speculation)
2. Is there a pure-logic/math slice? (no world reads mid-compute)
3. After JNI overhead, does Rust beat Java by >2×?
4. Is correctness testable without drowning in edge cases?

Piano model applies too — where does vanilla pause, what's in its hands,
can Rust take it in one handoff?

---

## Dead ends — investigated, hypothesis killed by data

### Cramming fingerprint cache
**Hypothesis:** mob positions stable tick-to-tick on a cramming pile →
fingerprint matches → skip hash build + pair iteration.

**Result:** `fpHits=0` across 7400+ ticks on a 254-mob pile. A cramming
pile is by definition a moving pile — cramming physics applies velocity
deltas to ~54% of mobs every tick, positions change, fingerprint never
matches. The only ticks where the fingerprint would match are ticks
where cramming did nothing — nothing to cache.

**Secondary finding:** cramming is already 0.01ms/tick after the spatial
hash port. Cache would save fractions of 0.01ms on a path that's already
near-zero. Wrong target regardless of whether the fingerprint would match.

**Real hot paths** at 254 mobs (from `[movement-internals]`):
- `move`: 1.67ms
- `travel`: 1.81ms
- `adjustColl`: 1.17ms
- `other`: 1.89ms

Total ~6.5ms vs cramming 0.01ms. The cramming work is done.

### Entity tick — investigation complete (2026-04-29)

Full breakdown at 254 mobs confirmed:

| bucket | avg/tick | verdict |
|---|---|---|
| cramming | 0.01ms | solved 310x, closed |
| GoalSelector | 0.40ms | JIT-optimized, not portable |
| MoveControl | 0.018ms | JIT ate world read cost |
| LookControl | 0.017ms | JIT ate atan2 cost |
| residual ~0.88ms | distributed call dispatch overhead | structural to vanilla's per-mob design |

The wall is vanilla's per-mob per-tick dispatch architecture. Rust wins on
batch operations. Entity AI is per-mob by design. No clean batch boundary
exists beyond cramming.

Every candidate followed the same arc: measure it, discover JIT already
optimized it to near-zero. The aggregate cost is real but distributed
across 160+ method calls per tick — each individually cheap, expensive
only in sum. JIT handles each call; it cannot eliminate the aggregate.

Entity tick investigation closed. Cramming is the only Rust-portable win
in this subsystem.

**Steady-state numbers at 254 hostile mobs (Ferrite active):**
- Tick time (ms/tick): avg ~9-10ms in-game, max spikes ~11-19ms (GC/JIT noise)
- Entity tick: avg ~4.7ms (all 254 mobs combined, from `[server-tick-phase]`)
- TPS: 20/20 -- 9-10ms is well under the 50ms budget

---

## Instrumented but never ported

### Fluid ticks (measured 2026-04-29, closed)

Schedulers already split in vanilla — two separate `WorldTickScheduler`
instances (`blockTickScheduler`, `fluidTickScheduler`). Band split now
visible in `[fluid-tick]` and `[block-tick]` monitor lines.

Measured baseline:

| scenario | fluid ticks/5s | µs/tick | share of server tick |
|---|---|---|---|
| ocean idle | ~7900 | 10.3µs | 0.135ms |
| active fill spike | ~2364 | 12.0µs | 0.281ms |
| ambient tail post-fill | ~300-600 | 14-28µs | 0.06-0.15ms |

Source audit verdict, port dead-on-arrival:

- Per-tick work is almost entirely world I/O (`getBlockState`,
  `setBlockState`).
- Snapshot trap: `tryFlow` writes block states mid-tick and immediately
  reads them back in the same `getSpread` loop.
- Two structural caches already absorb the expensive parts:
  identity-keyed LRU (200 entries) on `receivesFlow`, plus per-call
  `SpreadCache`.
- JNI per neighbor read would be 50-100x slower than the current cached
  identity compare.

Even at fill spike: <0.3ms server-tick share. JIT-locked at 10-15µs/tick.
Linear scaling, more fluid ticks means proportionally more cost, no
algorithmic win available without replacing world state access.

Port verdict: closed. Instrumentation retained for visibility.
Next: mob spawning candidate sampling or chunk saving/serialization.

### Villager AI / Brain ticks
Zero instrumentation. Known expensive on populated servers — villagers
run full `Brain` sensor + activity evaluation every tick. No measurement
exists in Ferrite context.

**Next step:** add mixin on `VillagerEntity.mobTick` or `Brain.tick`.
Measure on a world with 20+ villagers. If `creature` bucket in
`[entity-tick]` is hot, isolate villager specifically.

**Uncertainty:** Brain tick involves world reads (POI lookup, path queries).
May not have a clean pure-math slice. Measure first.

### Mob spawning candidate sampling
Per-tick: vanilla samples random positions in loaded chunks, checks biome
spawn conditions, caps against mob count. Pure math on the sampling side.

**Next step:** instrument `ServerChunkManager` spawn tick. Measure cost
per tick at various simulation distances. If >2ms/tick, apply four checks.

**Uncertainty:** spawn cap check reads chunk entity counts — world state
access. Sampling itself may be separable. Needs source audit.

### Block entity ticks — hoppers (active, two-phase plan 2026-04-29)

Measured so far (sorter chains, inventory-to-inventory transfer):

| setup | scans/tick | avg/scan | itemsFound |
|---|---|---|---|
| 10 hoppers | 10.0 | 0.13µs | 0 |
| 204 hoppers | 26.3 | 0.08µs | 0 |
| 300 hoppers | — | 0.11µs | 0 |

All runs hit the empty-scan fast path (`itemsFound=0`). Sorter chains
transfer inventory-to-inventory, so the entity scan rarely fires with
items present. **The scenario that actually causes player-reported lag
is unmeasured:** mob-farm collection layers (200+ uncovered hoppers
with items constantly present in the 1×1×1 pickup bbox).

**Prior art:**
- **2No2Name/hopperOptimizations** archived (1.16-only). Used
  event-driven entity tracker integration, not spatial hash.
- **Lithium** had the same approach in older versions; in current
  Lithium (0.24.x for 26.1.x) the dedicated `hopper/` mixin folder
  appears to have been removed or moved. The remaining `common/hopper/`
  files are inventory-side caching only. Unconfirmed whether the
  pickup optimization was retired or relocated; worth verifying before
  duplicating the work.

#### Two-phase plan

Two stackable wins, ordered by cost and ROI:

**Phase 1 — Smart Java (event-driven dirty flag).** Pure Java, no JNI.
Hopper sleeps until an item entity enters its pickup region:

```java
// Current vanilla:
List<ItemEntity> items = world.getEntitiesOfClass(ItemEntity, bbox);

// Phase 1:
if (!regionDirty) return;
regionDirty = false;
List<ItemEntity> items = world.getEntitiesOfClass(ItemEntity, bbox);
```

Eliminates scan for idle collection hoppers entirely. Cost is dirty-bit
maintenance on item-entity moves into watched regions.

**Phase 2 — Rust spatial hash (replace the scan).** When items ARE
present, swap `getEntitiesOfClass(ItemEntity, bbox)` for a Rust hash
query. Same cramming pattern: one hash per chunk per tick over all
item entities, each hopper queries it in O(1). Per-chunk per-tick
cost goes from O(hoppers × items) to O(items + hoppers).

#### Order of operations

1. Measure collection layer under real load (`itemsFound > 0` at
   scale). 100+ uncovered hoppers, `PickupDelay:200` to force item
   accumulation. Determines whether either phase is worth doing.
2. If signal is real: build Phase 1 (dirty flag). Pure Java, fast win.
3. Re-measure. Quantify the residual scan cost on hoppers that *do*
   have items (which Phase 1 cannot eliminate).
4. If residual is significant: build Phase 2 (Rust spatial hash).
   Apply the four checks against measured numbers, not against the
   architectural argument.

Hopper investigation stays open. Both phases are valid candidates;
neither is closed.

---

## No instrumentation at all

### Chunk IO / serialization
**Parked until external signal:** JFR logs from a real server with
active exploration and frequent autosave traffic showing this on the
hot path.

Vanilla's chunk IO path covers both directions: on load, palette
decode, heightmap rebuild, NBT parse from disk. On save, palette
encode, heightmap write, NBT serialize. Never profiled in Ferrite
context. On servers with active exploration or frequent autosaves
either side could be significant.

This is a strong architectural fit for Ferrite. Palette encode/decode
and heightmap rebuild are pure-math flat-buffer work: bit-packed input,
bit-packed output, no world-state reads, no JIT inlining vanilla has
already optimized to nanoseconds. The five questions plausibly all pass.

**Next step (when signal arrives):** instrument `ChunkSerializer.write`
and the chunk-load deserialize path. Measure during active play. If
hot, check Piano shape on palette decode first (smallest scope, fewest
dependencies).

**Uncertainty:** the IO path crosses several layers (region file
decompress, NBT tree walk, palette unpack). The flat-buffer kernel is
in there but may be sandwiched between non-flat work. Measure each
sub-stage before scoping.

### Lighting parallelism
**Parked until external signal:** real-server logs showing the
lighting thread as the long pole on chunk load or chunk-pipeline
init, not the edit-spike pattern that was already measured.

Lighting was instrumented and measured 2026-05-05. Steady-state cost
is near-zero idle, scales with column count not volume; peak observed
was ~117 ms on a 1024-column sky-light decrease (full breakdown in
`docs/PIANO_STATUS.md`). Edit spikes under heavy /fill or explosions
are not the signal that would justify a port; they are linear in
column count and the BFS itself is JIT-locked the same way density
functions are.

The signal that matters is different in shape: the init/pipeline
long-pole pattern, where the lighting thread blocks chunk loads or
chunk pipeline progress on a sustained server workload. That's a
threading-model concern, not an algorithmic one, and the Rust BFS
port that was investigated and shelved would not touch it.

**Next step (when signal arrives):** confirm via JFR that lighting is
the long pole on chunk load specifically, distinct from the edit-spike
pattern already characterized. If the long-pole pattern holds, the
work is on the threading model, not the BFS algorithm. That puts it
adjacent to chunk-pipeline orchestration territory (see chunk
generation pipeline parallelism above for the same architectural
question).

**Uncertainty:** changes to the lighting thread's threading model are
adjacent to chunk-pipeline orchestration, which is the same scope
expansion question raised under chunk-gen pipeline parallelism. The
two might collapse into a single "do we own a scheduler" decision if
both signals arrive together.

### Chunk generation pipeline parallelism
**Parked until external signal:** profiler data from a multi-core
server showing single-threaded chunk gen as the bottleneck *after* all
existing Ferrite wins are active (cramming, AC redstone, density
function pipeline, surface rule batch, etc.).

Running multiple chunk-gen stages concurrently requires touching the
ChunkStep dependency system. That's owning the scheduler, which means
owning the threading model, which means owning the compatibility
surface. A significant architectural commitment, not a small port.

**Next step (when signal arrives):** if a real workload shows
chunk-gen latency dominating after every other Ferrite optimization
is on, two paths are on the table. Recommend a pairing with a mod
that already owns a chunk-gen scheduler, or build our own. Picking
between them depends on what we already know by then about the
workload, the compatibility surface, and whether owning a scheduler
fits Ferrite's shape at that point in the project. Don't pre-decide.

**Uncertainty:** this category is fundamentally a different mod
shape than what Ferrite is today. Building it in-house would be a
deliberate scope expansion, not a port. Listing it as a candidate
keeps the door open without committing to it.

### Nether / End generation
Surface rule dispatcher was profiled and designed for overworld only.
Nether and End have different surface rule trees that the dispatcher
has never been measured against.

`EndIslandDensityFunction` is wired end-to-end via `OP_END_ISLAND`
(opcode `0x19`, see `rust/mod/src/density.rs:622`). The walker emits
it, the interpreter handles it, and `LazyEndIsland` lazily constructs
the SimplexNoise from the world seed on first use. End-island terrain
math therefore goes through the Rust DF interpreter the same way
overworld terrain does, not through a fallback path.

**Next step:** run `/ferrite surface stats` in Nether and End
dimensions. Check fallback rates on the *surface rule* side
(dispatcher coverage), not on the density function side. Fix
fallbacks if they show up.

### Structure generation
Placement scoring was measured as ~75-150µs/chunk — too small to port.
Actual structure generation (placing the structure blocks) was never
profiled. For complex structures (strongholds, ancient cities) this
could be significant.

**Next step:** instrument `StructureStart.place` timing. Measure on
a world near a stronghold or ancient city. If hot, check Piano shape.

### Raid / event logic
Never touched. Low priority — raids are infrequent and short-lived.
Document as out of scope unless user logs show it as a bottleneck.

---

## Open correctness gaps

### Aquifer Rust port
99.895% parity. Visible surface-grid artifacts at chunk boundaries.
7 sessions couldn't close the gap. Documented in `docs/AQUIFER_PORT.md`.

**Status:** default-off indefinitely. Revisit only with a fresh approach
to the surface-grid resolution problem.

### Surface rule dispatcher structural floor
~7ms irreducible gap vs ~6ms vanilla baseline. Documented in
`docs/PIANO_STATUS.md`. Closing it requires bypassing vanilla's chunk
API — incompatible with mod compatibility promise at current scope.

**Status:** default-off. Ships as opt-in. Gap is documented and honest.

### macOS runtime verification
Binary is structurally correct (`lipo -info` confirms both slices).
Runtime load unverified on real Apple hardware.

**Status:** waiting for a Mac user to confirm. Log snippet showing
`Loaded rust_mod from /tmp/rust_mod_*.dylib` is enough.

### ARM Linux
Not bundled. x86_64 Linux only.

**Status:** low priority until there's user demand.

---

## How new targets get added

The `[ferrite]` log lines are the discovery system. When users share
logs showing unexpected hot bands — hopper sorters, villager halls,
mob farms, specific biome chunk gen — that's the signal.

Apply the four checks. If they pass, add a section here with
"next step" and "uncertainty" noted. Don't port without measurement.

The instrumentation framework exists precisely so future work is
data-driven, not speculative.

---

### Pre-generation throughput note

Pregen + chunkforce competing for same region: ~53/s (49% drop).
Graceful split, no corruption. User workaround: /ferrite chunkforce off.
Future: optional cross-system inflight coordinator if operators need
guaranteed pregen throughput during active play.

### Brewing stand getSlotsEmpty() allocation

`getSlotsEmpty()` allocates `boolean[3]` every tick per brewing stand.
At 30 stands: ~600 allocations/sec, minor GC pressure.

Fix shape: three coordinated `@Redirect` on `tick()` body, brittle,
three redirects on a 40-line method, fragile to upstream changes.
Savings: ~14 KB/sec GC pressure at 30 stands, below TPS-relevant.

Deferred: fix complexity outweighs savings at realistic scale.
Revisit if brewing stand tick cost becomes measurable on large
servers.
