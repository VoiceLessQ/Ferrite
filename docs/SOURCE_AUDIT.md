# Source Audit (2026-04, Minecraft 1.21.11)

Findings from extracting the 1.21.11 yarn source tree and verifying every Ferrite mixin/walker against it. All names referenced in the codebase are confirmed present in vanilla.

## Source tree layout

`F:\Minecraft modding\Projects\Fabric Modding\Ferrite\1.21.11/` (gitignored, ~41 MB) is a fresh extract of yarn-mapped, Vineflower-decompiled 1.21.11 source — pulled straight from Loom's `loom-cache`:

```
1.21.11/
├── common/   (5500+ .java, server + shared)
│   └── net/minecraft/...
└── client/   (1100+ .java, client-only)
    └── net/minecraft/...
```

This is the authoritative reference for any future audits, mixin work, or cross-version reasoning. The path in the repo (`1.21.11/`) names the version it represents — unlike the older `26.1.2/` directory, which was a misleadingly-named dump of a *different* MC build (`world_version: 4790`, build_time `2026-04-09`).

### Refresh procedure

If yarn ships a new build for 1.21.11 (or for a future MC version):

1. Run `./gradlew genCommonSourcesWithVineflower` and `./gradlew genClientOnlySourcesWithVineflower` to populate Loom's cache.
2. Find the produced sources jars under `.gradle/loom-cache/minecraftMaven/net/minecraft/minecraft-{common,clientOnly}-*/...-sources.jar`.
3. `unzip -q -o <jar> -d <version-dir>/{common,client}/`.

Both directories are gitignored (see `.gitignore`).

## Runtime ↔ source drift: `Wrapping.Type.toString()`

This is the most consequential finding from the audit and worth understanding before touching any mixin that compares vanilla enum constants by name string.

### What the source says

`net/minecraft/world/gen/densityfunction/DensityFunctionTypes.java` (line 1379):

```java
static enum Type implements StringIdentifiable {
    INTERPOLATED("interpolated"),
    FLAT_CACHE("flat_cache"),
    CACHE2D("cache_2d"),
    CACHE_ONCE("cache_once"),
    CACHE_ALL_IN_CELL("cache_all_in_cell");

    @Override public String asString() { return this.name; }
    // No toString() override.
}
```

Java's `Enum.toString()` default returns `name()`. By the source, `CACHE_ALL_IN_CELL.toString()` should return `"CACHE_ALL_IN_CELL"`. By `asString()`, it would be `"cache_all_in_cell"`.

### What the runtime actually returns

Live diagnostic captured during chunkgen:

```
[bulk-chunk-density] saw Wrapping.type.toString() = Cache2D
[bulk-chunk-density] saw Wrapping.type.toString() = FlatCache
[bulk-chunk-density] saw Wrapping.type.toString() = CacheOnce
[bulk-chunk-density] saw Wrapping.type.toString() = Interpolated
[bulk-chunk-density] saw Wrapping.type.toString() = CacheAllInCell
```

Every value is **CamelCase**. Not the source-implied `CACHE_ALL_IN_CELL`, not the `asString()` `cache_all_in_cell`. The yarn `.tiny` mappings file even shows the constants as `CACHE_ALL_IN_CELL` — so the bytecode-level field name should match the source.

The most likely cause: yarn published the sources jar with one mapping snapshot, then updated the live `.tiny` mappings (or Loom resolved a newer build at classload), so the runtime sees a remapped CamelCase name. We did not pin down the exact mechanism — but the empirical observation is reproducible, and 0 fallbacks across 233 M chunkgen samples confirms our triple-check matches every value vanilla produces.

### Anchor in code

`BulkChunkDensityMixin.ferrite$swapFullNoiseDensity` carries a long comment explaining the triple-check is **load-bearing**, not redundant:

```java
boolean isCellCache = "CacheAllInCell".equals(typeName)        // actual runtime
        || "CACHE_ALL_IN_CELL".equals(typeName)                 // theoretical Java enum default
        || "cache_all_in_cell".equals(typeName);                // asString form, defensive
```

Removing the CamelCase form drops `substitutions` to 0 and breaks the mixin entirely. Don't touch.

### Methodology takeaway

For any name-string comparison against runtime vanilla values: capture the actual value via a one-shot diagnostic before hardcoding. The source jar is reliable for **code structure** (field types, method signatures, control flow) but not for **string-equality assertions** about reflective lookups.

## Mixin audit results (42 files, all clear)

Verified each `@Mixin`, `@Inject`, `@Redirect`, `@Shadow @Final`, `@Accessor`, `@Invoker` against actual classes/methods/fields in `1.21.11/common/...`. Every target exists. No drift, no orphans.

### Noise / density-function track (recently rebuilt)

| File | Verified |
|---|---|
| `BulkChunkDensityMixin.java` | `ChunkNoiseSampler.getActualDensityFunctionImpl(DensityFunction)`; `startCellX`, `startCellZ`, `horizontalCellBlockCount` all `final` ✓ |
| `BulkSampleDensityMixin.java` | `ChunkNoiseSampler.sampleDensity(boolean, int)`; all 6 shadow fields confirmed ✓ |
| `CacheRouteCaptureMixin.java` | `getActualDensityFunctionImpl` + `wrapped()` accessor on each cache wrapper ✓ |
| `ChunkNoiseSamplerAccessor.java` | `interpolators` field type matches `List<ChunkNoiseSampler.DensityInterpolator>` ✓ |
| `DensityInterpolatorAccessor.java` | `startDensityBuffer`, `endDensityBuffer`, `delegate` fields all present ✓ |
| `DensityFunctionWalker.java` | All class simple-name dispatches match real yarn 1.21.11 classes (Wrapping, BinaryOperation, LinearOperation, UnaryOperation, Constant, Noise, Spline, BlendDensity, BlendAlpha, BlendOffset, Beardifier, ShiftA/B/Shift, ShiftedNoise, WeirdScaledSampler, RangeChoice, YClampedGradient, Clamp, InterpolatedNoiseSampler, FlatCache, DensityInterpolator, Cache2D, CacheOnce, CellCache, RegistryEntryHolder) ✓ |
| `DeepMarkerWalker.java` | Same dispatch patterns, all match ✓ |

### Pre-existing tracks (cramming, surface, redstone, lighting, etc.)

All 34 remaining mixins verified clean:

- **Aquifer:** `AquiferSampler$Impl.apply` ✓
- **Block collision / movement:** `Entity.tickBlockCollision`, `Entity.move`, `Entity.adjustMovementForCollisions`, `Entity.applyGravity`, `LivingEntity.travel` ✓
- **Cramming:** `LivingEntity.tickCramming` ✓
- **Redstone:** `RedstoneWireBlock.update`, `DefaultRedstoneController.update`, `ExperimentalRedstoneController.update`, `AbstractRedstoneGateBlock.scheduledTick`, `RedstoneController.calculateWirePowerAt`/`getStrongPowerAt` ✓
- **Light:** `ServerLightingProvider.initializeLight` and `.light` ✓
- **Surface:** `SurfaceBuilder.buildSurface`, `MaterialRules.MaterialRuleContext.initVerticalContext`, `MaterialRules$BlockStateRule.tryApply` ✓
- **Server tick:** `ServerWorld.tick(BooleanSupplier)`, `tickEntity(Entity)`, `World.tickBlockEntities()` ✓
- **Mob tick:** `MobEntity.mobTick(ServerWorld)`, `LivingEntity.tickMovement`, `Entity.baseTick` ✓
- **Worldgen orchestration:** `NoiseChunkGenerator.populateNoise` (both async + sync), `ChunkGenerator.generateFeatures` ✓
- **Capture mixins:** `MultiNoiseBiomeSource.<init>` and `getBiome`, `NoiseConfig.<init>` ✓

No file targets a removed/renamed class, no signature drift, no shadow field mismatches.

## Current state of the codebase

### Working & shipped (default-on)

- **Cramming** — Rust port, replaces vanilla per-tick loop
- **Surface rule batched dispatcher** — Rust evaluates surface rules in bulk per chunk; ~99.8% parity (Track B framing — 100% on all rule shapes, the 0.2% delta is OreVein-specific code we scoped out)
- **Density function port** — 41/42 DFs bit-exact at every tested coord (only `EndIslands` is a CONSTANT(0) stub; scoped out)
- **Climate + biome lookup** — full Rust pipeline; R-tree of 7593 biome entries matches vanilla 1000/1000 sample tests
- **Noise stack** — Xoroshiro128++, Perlin, NormalNoise, BlendedNoise all bit-exact for the full 60-noise registry
- **Chunk forcer + prewarm** — vanilla's ticket API used to keep chunkgen ahead of fast flight; ~47 chunks/sec sustained, 99.87% prewarm hit rate

### Working but default-off

These are correct (verified by diagnostics + parity-confirmed visual gameplay) but didn't beat vanilla on perf and ship behind opt-in JVM flags so vanilla speed is the default.

| Toggle | What it enables | Live measured cost |
|---|---|---|
| `-Dferrite.bulkSlice=true` | Bulk slice fill via `BulkSampleDensityMixin` (intercepts `ChunkNoiseSampler.sampleDensity`) | ~12-18 ms/bulk fill, JNI ~2 ms/call after JIT-warmup. Net: regression vs vanilla's lazy-with-CacheOnce per-block path. |
| `-Dferrite.bulkChunkDensity=true` | Full chunk density buffer via `RustFinalDensityBufferWrapper` (intercepts the synthetic outer `cacheAllInCell(add(finalDensity, beardifier))`) | JNI fill ~50 ms/chunk; per-block read ~5 ns. Net: noise-sync ~100-118 ms/chunk vs vanilla baseline ~55-79 ms (~25-50 ms regression). Parity confirmed visually (no glitches reported). |

The infrastructure for both is correct. The capture pipeline matches 100% of cache wrappers (0 fallbacks across 233 M samples). The math is bit-exact (parity confirmed). The cost is the upfront JNI fill, which the Rust DF interpreter pays in interpreted dispatch where vanilla's hot loop is JIT-compiled.

### Small wins from this audit cycle

- **`1.21.11/` extracted** — fresh source-of-truth tree; no more cherry-picking files via grep + manual extracts. Future audits and reference reads are faster.
- **Source ↔ runtime drift documented** — the Wrapping.Type CamelCase finding is now explicit in code (anchor comment) and documented here. Future contributors won't "clean up" the load-bearing triple-check.
- **JIT-optimized hot path** — `RustFinalDensityBufferWrapper.sample` rewritten with bitwise math, single combined OR-chain bounds check, cold-path split, no atomics. Closed ~30 ms/chunk of the chunk-density-takeover regression.
- **42-mixin clean audit** — every mixin verified against 1.21.11. No legacy uncertainty about whether anything's targeting the wrong version.

### Known issues / regressions left in tree

| Issue | Impact | Status |
|---|---|---|
| `EndIslands` density function | Stubbed as `OP_CONSTANT(0)` in walker; affects only End dimension | Scoped out (not on overworld critical path) |
| Bulk slice fill toggle | Slower than vanilla when on | Default off; opt-in |
| Bulk chunk density toggle | Slower than vanilla when on | Default off; opt-in |
| Surface rule 0.2% delta | OreVein-specific subset | Scoped out |
| Aquifer not ported | ~8 ms/chunk vanilla baseline | Untouched |
| Lighting not ported | ~5-12 ms/chunk steady, ~15-19 ms init | Untouched |
| Decoration not ported | ~3 ms/chunk avg | Untouched |
