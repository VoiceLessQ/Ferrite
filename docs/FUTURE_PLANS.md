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

---

## Instrumented but never ported

### Fluid ticks
`scheduledTicks` band exists in `[server-tick-phase]` but fluid vs block
ticks are bundled together. Never isolated. On servers with large water
systems (kelp farms, water elevators, lava lakes) this could be significant.

**Next step:** split `WorldTickScheduler` into fluid vs block buckets.
Measure on a world with active fluid systems. Apply four checks.

**Uncertainty:** fluid spreading reads neighbor block states per step —
possible snapshot trap. Needs investigation before committing to a port.

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

### Block entity ticks — hoppers, furnaces
`[worldtick] blockEntities` exists but not broken down by type. Hoppers
at scale do `getEntitiesOfClass(ItemEntity, bbox)` — O(N) scan per tick.
Furnaces are cheap per-tick. Never profiled at scale.

**Next step:** add per-type breakdown to block entity monitor. Measure
on a world with active hopper sorter + furnace array. If hoppers dominate,
the spatial hash reuse plan from `docs/SPATIAL_HASH_REUSE_PLAN.md` applies.

---

## No instrumentation at all

### Chunk saving / serialization
Vanilla serializes chunks to NBT on save — palette encoding, heightmap
write, entity serialization. Never profiled. On servers with active
exploration or frequent saves this could be significant.

**Next step:** instrument `ChunkSerializer.serialize` timing. Measure
during active play. Palette encoding is pure math — possible Rust target
if it's hot.

### Nether / End generation
Surface rule dispatcher was profiled and designed for overworld only.
Nether and End have different surface rule trees. End has
`EndIslandDensityFunction` which is currently `OP_FALLBACK` in the
compiler.

**Next step:** run `/ferrite surface stats` in Nether and End dimensions.
Check if the compiled trees have high fallback rates. Fix if needed.

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
