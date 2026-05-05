# Aquifer Port (2026-04, Minecraft 1.21.11)

Status snapshot of the Rust port of vanilla's `AquiferSampler.Impl` —
the per-chunk aquifer placement that decides, for every block in a
chunk during noise-fill, whether vanilla's density-based block is
overridden by water/lava from a local aquifer cell.

**Current status: not production-ready.** Parity is 99.895%
(0.105% block mismatch), which is **visible as chunk-aligned terrain
artifacts in-world**. Recommended setting: `/ferrite aquifer rust off`
until per-column surface estimates land (next-pass plan below).

**Update (2026-04-28):** `AquiferMonitor` is now **gated behind
`AquiferMonitor.ENABLED` (default false)** — commit `c91a12b`. JFR
profiler showed the per-block `@Inject HEAD/RETURN` pair on
`AquiferSampler$Impl.apply` (~98K calls/chunk) contributing **~3-5 ms
of pure observation overhead** to the `noise-sync` phase even when
the Rust aquifer port itself was off. Re-enable with
`AquiferMonitor.ENABLED = true` only when actively measuring aquifer
per-block cost. See [PIANO_STATUS.md](PIANO_STATUS.md) "diagnostic gating" section for
the full finding.

## TL;DR

- **Rust algorithm body fully ported**, ~770 lines, 9 unit tests passing.
- **JNI bridge wired**, handle-based per-chunk state, three entrypoints.
- **Java mixin + wrapper integrated**, default off behind
  `/ferrite aquifer rust on`.
- **Parity harness landed** behind `/ferrite aquifer parity on`.
- **Best parity achieved: 99.895% bit-exact** (0.105% block mismatch)
  over multiple sessions of millions of comparisons. Zero init failures.
- **Surface-grid abstraction is the bottleneck.** Three configurations
  tested (stride 16 partial coverage, stride 16 full coverage, stride 8
  full coverage); only the original undersized stride-16 hits 0.105%,
  and "improvements" make parity worse. The grid is fundamentally lossy
  vs. vanilla's per-column lookups. Real fix is per-column surface
  estimates, not denser grids.
- **Visible in-game artifacts confirmed** via user screenshots — at
  ~530 wrong blocks per chunk, chunk-aligned dirt/cliff anomalies
  appear at the surface.

## Sessions 1–5 recap

The port took five focused work sessions in one conversation arc.

### Session 1 — Rust skeleton

Created [`rust/mod/src/aquifer.rs`](../rust/mod/src/aquifer.rs).

- 13 vanilla constants traced to their `field_*` names with comments.
- Coord helpers (`get_local_x/y/z`, `cell_to_block_x/y/z`,
  `max_distance`) — direct ports, signed-shift / `floorDiv`
  semantics verified by negative-coord tests.
- `FluidKind` + `FluidLevel` + `FluidLevelSampler::Overworld` matching
  vanilla's `NoiseChunkGenerator` config (sea level + lava cutoff).
- `AquiferImpl` per-chunk struct, constructor mirroring vanilla's
  index math line-for-line.
- `cell_block_position()` — lazy randomized cell-position init using
  bit-exact `XoroshiroPositionalRandomFactory.at(x,y,z).next_int_bounded(n)`
  in vanilla's exact order (X bound 10, Y bound 9, Z bound 10).

### Session 2 — Algorithm body

Same file, completed.

- `AquiferDensityFunctions` — caches the 6 DF refs at construct time
  from `WorldgenState`. The 6 are `ferrite:aquifer/{barrier,
  fluid_level_floodedness, fluid_level_spread, lava}` and
  `ferrite:climate/{erosion, depth}`, registered into Rust by
  `WorldgenStateBootstrap` at world load.
- `apply(block_x, block_y, block_z, density, &state)` — main entry
  point with the 4-best-neighbor 2×3×2 ranking (12 cells walked,
  insertion-sort top 4 by squared block distance).
- `get_water_level()` + `get_fluid_level_for()` — lazy fluid-level
  computation with the 13-offset `CHUNK_POS_OFFSETS` walk.
- `compute_fluid_block_y` / `compute_noise_based_fluid_level` /
  `compute_fluid_block_kind` — fluid-Y math using floodedness,
  spread, and lava noises.
- `calculate_density()` — barrier-noise modulated ridge density
  between two fluid levels, with `barrier_cache: Option<f64>`
  mirroring vanilla's `MutableDouble` lazy memo.
- `MathHelper` analogues (`clamp_f64`, `linear_map`, `clamped_map`,
  `round_down_to_multiple`) — kept inline (no module split).
- `in_deep_dark_parameters` — uses real erosion/depth DFs when
  registered; thresholds `erosion < -0.225 && depth > 0.9` taken from
  the vanilla source comments. **Unverified against
  `VanillaBiomeParameters` source — TODO.**

### Session 3 — JNI bridge (Rust side)

Created [`rust/mod/src/aquifer_jni.rs`](../rust/mod/src/aquifer_jni.rs).

Three entrypoints:

- `Java_..._initAquifer(...)` — allocates `AquiferImpl`, returns a
  `jlong` handle (`Box::into_raw`). 0 = failure (worldgen state not
  finalized, surface grid invalid, etc.).
- `Java_..._applyAquifer(handle, x, y, z, density)` — per-block query.
  Returns a packed `jlong`:
  - low 8 bits: result (0=NONE, 1=AIR, 2=WATER, 3=LAVA)
  - bit 8: `needs_fluid_tick`
- `Java_..._freeAquifer(handle)` — reclaims the box.

**Surface-height grid:** `AquiferImpl::get_fluid_level_for` calls a
surface estimator at arbitrary `(block_x, block_z)` coords inside
chunk + ~48-block padding. Java pre-computes a sparse 2D grid; Rust
does nearest-cell lookup with fallback to a scalar estimate when out
of range. Grid stored as boxed closure in `AquiferImpl` to avoid
type-parameter spread on the struct.

**Random factory derivation:** Rust pulls
`root_factory.from_hash_of("minecraft:aquifer").fork_positional()`
inside `initAquifer` — bit-exact equivalent of vanilla's
`randomDeriver.split(Identifier.ofVanilla("aquifer")).nextSplitter()`.
No factory params crossed JNI.

`lib.rs` registers the new module + 3 `pub use` exports.

### Session 4 — Java wrapper + mixin + command

Three new files:

- [`RustAquiferDispatch.java`](../src/main/java/me/apika/apikaprobe/RustAquiferDispatch.java)
  — toggle (`ENABLED` / `PARITY_MODE`) and cold-path counters.
- [`RustAquiferSampler.java`](../src/main/java/me/apika/apikaprobe/RustAquiferSampler.java)
  — implements vanilla's `AquiferSampler` interface. Owns the Rust
  handle. Builds the surface-height grid (`8 × 4` cells, stride 16
  blocks, covering CHUNK_POS_OFFSETS extents) at construct time.
  Registers a `java.lang.ref.Cleaner` action that fires
  `RustBridge.freeAquifer` when the wrapper becomes phantom-reachable
  (i.e. when its owning `ChunkNoiseSampler` is GC'd).
- [`AquiferRouteMixin.java`](../src/main/java/me/apika/apikaprobe/mixin/AquiferRouteMixin.java)
  — `@Redirect` on the `AquiferSampler.aquifer(...)` static factory
  call inside `ChunkNoiseSampler.<init>`. Always builds the vanilla
  sampler (used as fallback); when `ENABLED`, wraps it in
  `RustAquiferSampler`. Uses `@Shadow estimateSurfaceHeight(int, int)`
  to call vanilla's package-private method during grid pre-compute.

Mixin registered in `ferrite.mixins.json`. Added
`/ferrite aquifer rust on/off/status` to `FerriteCommand.java`.

### Session 5 — Parity harness + smoke test

Extended `RustAquiferSampler.apply()` to support a parity mode: when
`PARITY_MODE` is on, every Rust call also runs the vanilla fallback
and compares results, logging mismatches (rate-limited: first 20
verbose, then every 4096th). Added `parityCompared` /
`parityBlockMismatch` / `parityTickMismatch` counters and the
`/ferrite aquifer parity on/off/reset` command path.

## Empirical results (smoke test, 2026-04-26)

In-world session: ~3 minutes of spectator flight from spawn,
generating 4269 fresh chunks under sustained chunkgen pressure.

```
[aquifer-rust] enabled=true parity=true
               wrappers=4269 freed=0 (leak=4269)
               fallbacks=0 gridBuild=93.4µs avg
               parity n=343362400
               blockMis=403479 (0.118%)
               tickMis=405183
```

Reading the numbers:

- **`fallbacks=0`** — every chunk's `initAquifer` returned a non-zero
  handle. JNI plumbing solid.
- **`blockMis=0.118%`** — 99.882% of the 343 million `apply()` calls
  matched vanilla bit-exact. First-pass near-miss; not bit-exact but
  not pervasive either.
- **`tickMis ≈ blockMis`** — the two track each other; tick
  mismatches mostly come *with* block mismatches, not independently.
- **`gridBuild=93.4µs avg`** — grid pre-compute is cheap (~93 µs per
  chunk for 32 surface-height samples).
- **`leak=4269`** — expected. Cleaner runs only when chunks become
  phantom-reachable; with 4269 chunks still in caches, GC hasn't
  fired. Verify after a longer-running session.

## Drift patterns

Three failure modes visible in the verbose mismatch log:

### Pattern 1 (dominant) — over-bailout at high Y

Form: `rust=null vanilla=Block{minecraft:air}` at y=100+, often
y=80–120.

What's happening: vanilla takes its early `if (j > field_61452)`
bailout that returns `dimension_fluid.block_state(j)` (which evaluates
to AIR for blocks above sea level). Rust enters the full algorithm
body and returns `None` (no aquifer override).

Root cause: our `high_cell_y_cap` is computed differently from
vanilla's `field_61452`. Vanilla calls
`chunkNoiseSampler.estimateHighestSurfaceLevel(...)` over the
cell-padded rectangle; we approximate with `max` over an 8×4 grid at
stride 16. The two values disagree by a few blocks, sometimes putting
a block on different sides of the bailout threshold.

This pattern accounts for the majority of the visible mismatches.

### Pattern 2 — algorithm-body divergence

Form: `rust=Block{water} vanilla=Block{air}` (and inverse) at mid/low
Y values (y=14, 46, 86 region).

Real differences in the 4-best ranking, the barrier-noise blend, or
the fluid-Y decision. Lower frequency than Pattern 1.

Likely contributors:

- Surface-grid stride 16 losing within-chunk surface variation, which
  affects `compute_fluid_block_y` (uses surface estimate as input).
- `in_deep_dark_parameters` thresholds — if the literals
  `erosion < -0.225 && depth > 0.9` don't match vanilla
  `VanillaBiomeParameters` exactly, the
  `getFluidBlockY` branch decision flips.

### Pattern 3 — needs-fluid-tick alone

Form: `tick mismatch` at coords where `block mismatch` does not also
fire on the same call.

Lower magnitude relative to total tick mismatches; mostly correlated
with block mismatches via state machine carry-over.

## Files inventory

### Added

| File | Purpose | Status |
|---|---|---|
| [`rust/mod/src/aquifer.rs`](../rust/mod/src/aquifer.rs) | Rust port of `AquiferSampler.Impl` algorithm | 9 unit tests passing |
| [`rust/mod/src/aquifer_jni.rs`](../rust/mod/src/aquifer_jni.rs) | JNI bridge (init / apply / free) | Compiles clean |
| [`src/main/java/me/apika/apikaprobe/RustAquiferDispatch.java`](../src/main/java/me/apika/apikaprobe/RustAquiferDispatch.java) | Toggle + cold-path counters + parity counters | — |
| [`src/main/java/me/apika/apikaprobe/RustAquiferSampler.java`](../src/main/java/me/apika/apikaprobe/RustAquiferSampler.java) | Java wrapper with handle, parity mode, surface-grid build | — |
| [`src/main/java/me/apika/apikaprobe/mixin/AquiferRouteMixin.java`](../src/main/java/me/apika/apikaprobe/mixin/AquiferRouteMixin.java) | `@Redirect` on `AquiferSampler.aquifer(...)` factory call | — |
| [`docs/AQUIFER_PORT.md`](AQUIFER_PORT.md) | This document | — |

### Modified

| File | Change |
|---|---|
| [`rust/mod/src/lib.rs`](../rust/mod/src/lib.rs) | `pub mod aquifer; mod aquifer_jni;` + 3 `pub use` exports for the JNI entrypoints |
| [`src/main/java/me/apika/apikaprobe/RustBridge.java`](../src/main/java/me/apika/apikaprobe/RustBridge.java) | 3 `native` method declarations: `initAquifer`, `applyAquifer`, `freeAquifer` |
| [`src/main/resources/ferrite.mixins.json`](../src/main/resources/ferrite.mixins.json) | Registered `AquiferRouteMixin` |
| [`src/main/java/me/apika/apikaprobe/FerriteCommand.java`](../src/main/java/me/apika/apikaprobe/FerriteCommand.java) | Added `/ferrite aquifer rust {on,off,status}` and `/ferrite aquifer parity {on,off,reset}` subcommand paths + 6 handler methods |

## Known approximations / open issues

- **`high_cell_y_cap` from grid `max`** instead of vanilla's
  `estimateHighestSurfaceLevel`. Drives Pattern 1 mismatches. Highest-
  leverage fix.
- **Surface-grid stride 16, nearest-neighbor** lookup. Loses
  within-chunk surface variation. Likely contributes to Pattern 2
  but harder to attribute precisely without further isolation.
- **`in_deep_dark_parameters` thresholds unverified** against
  `VanillaBiomeParameters` source. Match the comments in vanilla
  AquiferSampler but not double-checked.
- **`fluidLevelSampler` overworld extraction** (in
  `AquiferRouteMixin`) calls `getFluidLevel(0, 256, 0).y()` to recover
  the sea-level constant — works for vanilla overworld, may break for
  custom dimensions with non-overworld FluidLevelSampler shapes.
- **Cleaner-driven free** — depends on JVM GC seeing the wrapper as
  phantom-reachable. With long-running chunkgen pressure the wrappers
  may pile up before the first major GC. Monitor `leak=` over longer
  sessions; fall back to explicit `close()` via mixin into chunk
  unload if the leak grows unbounded.

## Sessions 6-7 — surface-grid investigation

Spent two sessions trying to close the 0.118% baseline parity. Net
result: **the grid abstraction is the wrong approach**, and the
"baseline" is not improvable along the current architecture. Detailed
findings recorded so future-me doesn't replay the same experiments.

### Session 6 — exact `estimateHighestSurfaceLevel`

**Hypothesis:** Pattern 1 (over-bailout at high Y) was caused by our
`high_cell_y_cap` using `max` over the surface grid instead of vanilla's
`estimateHighestSurfaceLevel(rect)` rect-max.

**Implementation:** added `@Shadow estimateHighestSurfaceLevel(int,
int, int, int)` to `AquiferRouteMixin`. Constructor handler now
replicates vanilla's exact rect args (cell-padded extents) and passes
the result to Rust as `surfaceHeightEstimate`.

**Result:** 0.118% → 0.105%. Modest 11% improvement. **Pattern 1 was
not the dominant cause.** Reading the verbose mismatch log post-fix
showed the same `rust=null vanilla=AIR` pattern at y=70-90, but vanilla
is *not* taking the high-Y branch at those Ys (cap is ~94 for typical
overworld terrain). It's taking the `dist_ab <= 0 → return blockState2`
fast path with `blockState2 = AIR`. Rust is somehow ranking cells
differently or computing a positive `e` in the partial-blend logic.

### Session 7 — surface-grid stride and coverage

**Hypothesis:** finer stride or wider coverage on the surface-height
grid would close the remaining gap.

**Three configurations tested:**

| Config | side | padding | stride | blockMis | Notes |
|---|---|---|---|---|---|
| Original (post Session 6 cap fix) | 8×4 | 48/16 | 16 | **0.105%** | Insufficient coverage; edge queries fall back to scalar `estimateHighestSurfaceLevel` value |
| Stride-8, broken origin | 16×8 | 24/8 (×stride bug) | 8 | 0.466% | Origin formula multiplied by stride, halving padding |
| Stride-8, origin fixed | 16×8 | 48/16 | 8 | 0.132% | Origin in blocks; still undersized Z |
| Stride-8, full coverage | 16×12 | 64/32 | 8 | 0.275% | Fully covered queryable range |
| Stride-16, full coverage | 8×6 | 64/32 | 16 | 0.541% | Same coverage as stride-8 full, denser-than-needed grid |

**Conclusion:** the original 8×4/48/16 configuration (technically
*undersized* — some queries fall back to the scalar) is the empirical
best. Every "correctness" improvement (denser grid, wider coverage,
better fallback) makes parity *worse*. This means:

1. The grid lookup itself is lossy in a way the aquifer algorithm is
   sensitive to.
2. The scalar fallback (vanilla's exact `estimateHighestSurfaceLevel`
   rect-max) happens to match vanilla's behavior for *some* algorithm
   decision points more often than the per-cell grid lookup does.
3. The "more coverage" configurations route more queries through the
   lossy grid and fewer through the fortuitously-helpful scalar
   fallback, hurting parity.

**Reverted to** the empirical-best configuration: side=8/4,
padding=48/16, stride=16. This gives 0.105% block mismatch.

### Visible in-game artifacts

User reported chunk-aligned terrain anomalies (rectangular dirt
patches, abrupt cliff faces at chunk boundaries) when running with
the broken 0.541% configuration mid-experimentation. Even at 0.105%,
the artifact rate is roughly 100 wrong blocks per chunk — enough to
be visible at the surface in steep terrain.

**Therefore: aquifer rust path is not production-ready** at any
configuration we've tested. Recommend running with `/ferrite aquifer
rust off` until per-column surface estimates land.

## Next pass plan (revised after session 7)

Surface-grid resolution does not converge to parity. The real fix is
exact per-column surface estimates. Three options, ranked by effort:

1. **Per-call JNI callback for `estimateSurfaceHeight`.** Each Rust
   `get_fluid_level_for` call would cross JNI for each of the 13
   chunk-pos-offset queries (~13 callbacks per cold cell × ~50 cold
   cells per chunk ≈ 650 callbacks per chunk, after vanilla cache
   amortization on Java side). Heavy JNI cost, but bit-exact match.
   Likely closes parity to within `in_deep_dark_parameters`-level
   noise (single-digit mismatches per chunk).
2. **Bilinear interpolation on a denser grid.** Replace nearest-
   neighbor with bilinear over a stride-4 or stride-8 grid. Smaller
   perf cost than callbacks but still approximate; uncertain whether
   it converges to bit-exact.
3. **Pre-compute per-column estimates for the full queryable
   rectangle.** ~7,500 cells per chunk. Vanilla's
   `surfaceHeightEstimateCache` (a `Long2IntOpenHashMap`) amortizes
   identical column queries, so the actual cost is ~"unique columns
   touched" × per-column DF sample cost. May be tractable per
   chunk; needs a measurement first. Bit-exact match.

Option 3 is the most promising — bit-exact and likely faster than
option 1 (one JNI handoff per chunk vs. hundreds per chunk).

Other deferred items from session 5:

- **Verify `in_deep_dark_parameters` literals** against
  `VanillaBiomeParameters` source.
- **Mismatch coordinate logger improvements** — group mismatches
  by `(rust_kind, vanilla_kind)` and `(y_band)` to pinpoint which
  algorithm-body decision points actually divergence at the
  per-block level.
- **Cleaner-driven free** — confirm `leak=` converges to 0 over
  longer sessions; add explicit close path if it doesn't.

## Risk register

| Risk | Likelihood | Mitigation |
|---|---|---|
| Surface-grid approximation can't close to bit-exact | Medium | Stride 8 grid → 16 → callback to Java if grid resolution alone insufficient. |
| Cleaner-based free leaks under heavy chunkgen | Low | Diagnostic counters track `created` vs `freed`; explicit close path available if needed. |
| Custom dimensions break sea-level extraction | Low | Sampler is opt-in; default off. Add dimension-type guard before enabling on non-overworld. |
| Per-block JNI cost dominates the gain | Medium | Need a perf measurement after parity is closed. If JNI cost is high, consider a per-chunk bulk-fill (`apply` for every block at chunk init, return a buffer) similar to the noise-fill bulk path. |
