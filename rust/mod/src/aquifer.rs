//! Bit-exact Rust port of vanilla 1.21.11's
//! `AquiferSampler.Impl` — the per-chunk aquifer placement that decides,
//! for every block in a chunk during noise-fill, whether vanilla's
//! density-based block (stone/air) is overridden by water/lava from a
//! local aquifer cell.
//!
//! Source: `1.21.11/common/net/minecraft/world/gen/chunk/AquiferSampler.java`
//! (yarn-decompiled). Method-level numbered names below trace to that
//! file by line where the algorithm is non-obvious.
//!
//! # Architectural shape
//!
//! Per chunk, vanilla allocates a 3-D grid of "aquifer cells" sized
//! `sizeX × verticalCells × sizeZ` covering the chunk plus a small
//! padding (because aquifer cells span 16×12×16 blocks and the lookup
//! reads a 2×3×2 cell neighborhood). Each cell has:
//!   - A randomized "block position" inside it (lazy-init, derived from
//!     the world's positional Xoroshiro splitter at cell coords).
//!   - A `FluidLevel` (lazy-init, computed when first queried via
//!     surface-height estimate + multiple density-function samples).
//!
//! `apply(pos, density)` — the per-block hot path — finds the 4 closest
//! aquifer cells by squared block-distance from the query, then runs a
//! barrier-noise + ridge-density check between the closest and 2nd/3rd
//! closest fluid levels to decide whether the block is fluid or solid.
//!
//! # Dependencies
//!
//! - `XoroshiroPositionalRandomFactory` (already ported in
//!   `crate::xoroshiro`).
//! - 6 density functions, registered into `WORLDGEN_STATE` by name at
//!   world load: `ferrite:aquifer/barrier`,
//!   `.../fluid_level_floodedness`, `.../fluid_level_spread`,
//!   `.../lava`, `ferrite:climate/erosion`, `ferrite:climate/depth`.
//! - `chunkNoiseSampler.estimateSurfaceHeight(x, z)` — vanilla method.
//!   For now we accept this as a callback or precomputed table from the
//!   Java caller. Final shape TBD in JNI session.
//! - `fluidLevelSampler` — for overworld this is just "lava below
//!   y=min(-54, sea_level), water otherwise" + a guard for very-deep
//!   AIR. Encoded as a thin trait below.
//!
//! # What's stubbed in this commit
//!
//! - `VanillaBiomeParameters::in_deep_dark_parameters` — needs port;
//!   currently returns `false` (matches "not in deep dark", which is
//!   correct for the vast majority of blocks but wrong for ancient-city
//!   biomes). TODO before parity tests.
//! - JNI bridge — separate session.
//! - Java mixin wrapper — separate session.
//!
//! # Bit-exactness invariants
//!
//! Every numeric constant below traces to a `field_*` in the yarn
//! source by name. Coord helpers (`get_local_x` / `method_72677` etc.)
//! are direct ports of vanilla's static helpers, named identically so
//! the audit trail stays grep-able.

use crate::density::{DensityFunction, FunctionContext};
use crate::worldgen_state::WorldgenState;
use crate::xoroshiro::XoroshiroPositionalRandomFactory;

// ============================================================================
// Constants — direct from AquiferSampler.Impl
// ============================================================================

/// `field_31451` — randomized X-offset bound inside an aquifer cell.
/// `random.nextInt(10)` produces values in `0..=9`.
const RAND_BOUND_X: i32 = 10;
/// `field_31452` — randomized Y-offset bound. Note: cell Y-size is 12.
const RAND_BOUND_Y: i32 = 9;
/// `field_31453` — randomized Z-offset bound.
const RAND_BOUND_Z: i32 = 10;

/// `field_31457` — aquifer cell X-size in blocks. Cells tile the world
/// every 16 blocks horizontally. (Cell index = block_coord >> 4.)
const CELL_SIZE_X: i32 = 16;
/// `field_31458` — aquifer cell Y-size in blocks. Cells tile every 12
/// blocks vertically. (Cell index = floorDiv(block_coord, 12).)
const CELL_SIZE_Y: i32 = 12;
/// `field_31459` — aquifer cell Z-size in blocks.
const CELL_SIZE_Z: i32 = 16;

/// Default sea-level offset from `DimensionType.field_35479`. This is
/// the "very deep" cap; Y values < this are treated as no-fluid floor.
/// Vanilla source: `DimensionType.MIN_HEIGHT * 2`.
pub const VERY_DEEP_FLUID_FLOOR: i32 = -2032 * 2;

/// `NEEDS_FLUID_TICK_DISTANCE_THRESHOLD` = `maxDistance(square(10), square(12))`
/// = `1.0 - (144 - 100) / 25.0` = `-0.76`. Computed inline because
/// `square` is just `x*x`.
const NEEDS_FLUID_TICK_DISTANCE_THRESHOLD: f64 = 1.0 - ((12 * 12 - 10 * 10) as f64) / 25.0;

/// `CHUNK_POS_OFFSETS` — 13 chunk-coord offsets walked by `getFluidLevel`
/// when checking nearby chunks for above-surface fluid blocks.
/// First entry `(0, 0)` is the current chunk; rest are neighbors.
const CHUNK_POS_OFFSETS: [[i32; 2]; 13] = [
    [0, 0],
    [-2, -1], [-1, -1], [0, -1], [1, -1],
    [-3, 0], [-2, 0], [-1, 0], [1, 0],
    [-2, 1], [-1, 1], [0, 1], [1, 1],
];

// ============================================================================
// Coord helpers — direct ports of vanilla's static methods
// ============================================================================

/// `getLocalX(int)` — block-X to aquifer-cell-X. Cells are 16-wide so
/// this is just an arithmetic right shift (preserving sign for negative
/// block coords). Vanilla's `>> 4` is signed shift in Java; Rust's `>>`
/// on `i32` is also signed.
#[inline]
pub fn get_local_x(block_x: i32) -> i32 {
    block_x >> 4
}

/// `getLocalY(int)` — block-Y to aquifer-cell-Y. Vanilla uses
/// `Math.floorDiv(i, 12)` because cells are 12 blocks tall and Y can be
/// negative.
#[inline]
pub fn get_local_y(block_y: i32) -> i32 {
    block_y.div_euclid(CELL_SIZE_Y)
}

/// `getLocalZ(int)` — analog of `get_local_x` for Z.
#[inline]
pub fn get_local_z(block_z: i32) -> i32 {
    block_z >> 4
}

/// `method_72677(cellX, offsetX)` — cell-X back to a block-X (cell
/// origin + offset).
#[inline]
pub fn cell_to_block_x(cell_x: i32, offset_x: i32) -> i32 {
    (cell_x << 4) + offset_x
}

/// `method_72678(cellY, offsetY)` — cell-Y back to block-Y.
#[inline]
pub fn cell_to_block_y(cell_y: i32, offset_y: i32) -> i32 {
    cell_y * CELL_SIZE_Y + offset_y
}

/// `method_72679(cellZ, offsetZ)` — cell-Z back to block-Z.
#[inline]
pub fn cell_to_block_z(cell_z: i32, offset_z: i32) -> i32 {
    (cell_z << 4) + offset_z
}

/// `method_72680(int)` — surface-height-estimate adjustment. Vanilla
/// adds a fixed 8 to the estimate before comparing to block-Y.
#[inline]
fn surface_height_plus_buffer(surface_estimate: i32) -> i32 {
    surface_estimate + 8
}

/// `maxDistance(int sq1, int sq2)` — converts two squared block
/// distances into a normalized "closeness ratio" used to weight the
/// barrier-density blend. Returns positive when the two cells are far
/// apart (>5 blocks), negative when one is much closer than the other.
#[inline]
fn max_distance(sq_closer: i32, sq_farther: i32) -> f64 {
    1.0 - ((sq_farther - sq_closer) as f64) / 25.0
}

// ============================================================================
// FluidLevel — record(int y, BlockState state)
// ============================================================================

/// Discriminant for the block state inside a `FluidLevel`. Vanilla
/// stores a `BlockState` reference; we only need to distinguish the
/// three meaningful cases since the aquifer algorithm only cares about
/// "is this WATER", "is this LAVA", "is this AIR (no fluid)".
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum FluidKind {
    Water,
    Lava,
    Air,
}

/// `AquiferSampler.FluidLevel(int y, BlockState state)` — fluid surface
/// for one aquifer cell. Y is the block-Y of the fluid surface; blocks
/// at Y < this are filled with `kind`, blocks at Y >= this are air.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct FluidLevel {
    pub y: i32,
    pub kind: FluidKind,
}

impl FluidLevel {
    #[inline]
    pub fn new(y: i32, kind: FluidKind) -> Self {
        Self { y, kind }
    }

    /// `getBlockState(int y)` — the block to place at this Y position.
    /// Below the fluid surface → `kind`; at or above → AIR.
    #[inline]
    pub fn block_state(self, y: i32) -> FluidKind {
        if y < self.y { self.kind } else { FluidKind::Air }
    }

    #[inline]
    pub fn is_lava(self) -> bool {
        matches!(self.kind, FluidKind::Lava)
    }

    #[inline]
    pub fn is_water(self) -> bool {
        matches!(self.kind, FluidKind::Water)
    }
}

/// What `apply(pos, density)` returns. `None` = vanilla's stone/air
/// density wins (no aquifer override). `Some(kind)` = the aquifer
/// places this fluid block (or air, for very-deep no-fluid cells).
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct ApplyResult {
    pub kind: FluidKind,
    pub needs_fluid_tick: bool,
}

// ============================================================================
// FluidLevelSampler — `(x, y, z) -> FluidLevel`
// ============================================================================

/// Vanilla's `AquiferSampler.FluidLevelSampler` — on overworld, this is
/// "lava below y=min(-54, sea_level), water above". For nether/end the
/// shape differs but all are simple branchy lookups.
///
/// We model it as a value rather than a trait object to avoid dyn-call
/// overhead in the hot path. The `OVERWORLD` variant captures the two
/// thresholds vanilla configures in `NoiseChunkGenerator`.
#[derive(Debug, Clone, Copy)]
pub enum FluidLevelSampler {
    /// Overworld: water at `sea_level`, lava at `lava_y`. `cutoff` =
    /// `min(lava_y, sea_level)`; below cutoff → lava level, otherwise →
    /// water level.
    Overworld {
        sea_level: i32,
        lava_y: i32,
        cutoff: i32,
    },
    /// Constant level (e.g. nether: lava at -54).
    Constant(FluidLevel),
}

impl FluidLevelSampler {
    /// Construct overworld sampler matching vanilla's
    /// `NoiseChunkGenerator` line ~80.
    #[inline]
    pub fn overworld(sea_level: i32) -> Self {
        let lava_y = -54;
        Self::Overworld {
            sea_level,
            lava_y,
            cutoff: lava_y.min(sea_level),
        }
    }

    /// `getFluidLevel(int x, int y, int z)` — vanilla's per-coord
    /// lookup. The (x, z) are unused for overworld but kept in the
    /// signature for parity.
    #[inline]
    pub fn get_fluid_level(self, _x: i32, y: i32, _z: i32) -> FluidLevel {
        match self {
            Self::Overworld {
                sea_level,
                lava_y,
                cutoff,
            } => {
                if y < cutoff {
                    FluidLevel::new(lava_y, FluidKind::Lava)
                } else {
                    FluidLevel::new(sea_level, FluidKind::Water)
                }
            }
            Self::Constant(level) => level,
        }
    }
}

// ============================================================================
// AquiferDensityFunctions — DF refs cached at construct time
// ============================================================================

/// Cached references to the 6 density functions vanilla's aquifer
/// reads. Resolved once at constructor time from `WorldgenState` to
/// avoid HashMap lookups on the per-block hot path. Each is the
/// `ferrite:*` name as registered by `WorldgenStateBootstrap`.
#[derive(Clone)]
pub struct AquiferDensityFunctions {
    pub barrier_noise: Option<DensityFunction>,
    pub fluid_level_floodedness_noise: Option<DensityFunction>,
    pub fluid_level_spread_noise: Option<DensityFunction>,
    pub fluid_type_noise: Option<DensityFunction>, // vanilla's lavaNoise
    pub erosion: Option<DensityFunction>,
    pub depth: Option<DensityFunction>,
}

impl AquiferDensityFunctions {
    /// Resolve all 6 by their registered ferrite names. Any miss leaves
    /// the corresponding `Option` as `None`, which `apply` handles by
    /// returning `0.0` for that noise sample (matches vanilla's
    /// behavior when a DF is the constant zero).
    pub fn from_state(state: &WorldgenState) -> Self {
        let lookup = |name: &str| state.density_functions.get(name).cloned();
        Self {
            barrier_noise: lookup("ferrite:aquifer/barrier"),
            fluid_level_floodedness_noise: lookup("ferrite:aquifer/fluid_level_floodedness"),
            fluid_level_spread_noise: lookup("ferrite:aquifer/fluid_level_spread"),
            fluid_type_noise: lookup("ferrite:aquifer/lava"),
            erosion: lookup("ferrite:climate/erosion"),
            depth: lookup("ferrite:climate/depth"),
        }
    }

    /// Empty (test-only) — every DF resolves to `None`, so all noise
    /// samples in `apply` return their fallback `0.0`.
    pub fn empty() -> Self {
        Self {
            barrier_noise: None,
            fluid_level_floodedness_noise: None,
            fluid_level_spread_noise: None,
            fluid_type_noise: None,
            erosion: None,
            depth: None,
        }
    }
}

// ============================================================================
// AquiferImpl — per-chunk state
// ============================================================================

/// Per-chunk aquifer state. Allocated once at chunk init, queried
/// `~98K` times per chunk during noise-fill (one `apply` per block).
///
/// Memory: dominated by `block_positions` (`size × Long.BYTES`) and
/// `water_levels` (`size × ~24 bytes`). For an overworld chunk
/// `size ≈ 4 cells × 32 cells × 4 cells = 512`, so ~16 KB total.
pub struct AquiferImpl {
    /// Positional Xoroshiro splitter rooted at the world's aquifer
    /// random factory (vanilla's `aquiferRandomDeriver` in
    /// `NoiseConfig`).
    random_deriver: XoroshiroPositionalRandomFactory,
    /// Overworld sea-level config, used both directly and via
    /// `fluid_level_sampler`.
    fluid_level_sampler: FluidLevelSampler,
    /// 6 cached DF references resolved at construct time.
    dfs: AquiferDensityFunctions,
    /// Surface-height estimator at arbitrary `(block_x, block_z)`.
    /// Vanilla calls `chunkNoiseSampler.estimateSurfaceHeight(x, z)`.
    /// Boxed so the struct doesn't need a type parameter; the cost is
    /// one heap alloc per chunk and one indirect call per
    /// `get_fluid_level` (cold path — only fires on lazy water-level
    /// init, ~10–100 cells per chunk).
    surface_estimator: Box<dyn Fn(i32, i32) -> i32 + Send + Sync>,
    /// Aquifer cell grid origin. All cells in this chunk's grid have
    /// indices in `[start_x, start_x + size_x)`, etc.
    start_x: i32,
    start_y: i32,
    start_z: i32,
    size_x: i32,
    /// Vertical cell count (`(end_y - start_y + 1)`). Stored to compute
    /// the linear `index()`.
    size_y: i32,
    size_z: i32,
    /// `field_61452` — Y-cap above which fluid lookups short-circuit to
    /// the dimension default. Computed from the chunk's surface-height
    /// estimate at constructor time.
    high_cell_y_cap: i32,
    /// Lazy-init: vanilla's `blockPositions[size]`, packed `(x, y, z)`
    /// per cell. `i32::MIN` markers indicate uninitialized.
    /// Stored as parallel arrays rather than `i64` packing because Rust
    /// has cheap (i32, i32, i32) triples and we don't need bit-exact
    /// match with Java's `BlockPos.asLong` encoding (this is purely
    /// internal cache).
    block_positions_x: Vec<i32>,
    block_positions_y: Vec<i32>,
    block_positions_z: Vec<i32>,
    /// Lazy-init: vanilla's `waterLevels[size]`. `None` = not yet
    /// computed.
    water_levels: Vec<Option<FluidLevel>>,
    /// Mutable side-effect bit, vanilla's `needsFluidTick`. Set per
    /// `apply` call, read separately by vanilla via `needsFluidTick()`.
    /// Public so the Java side can read it after each apply call (will
    /// be packed into the JNI return shape later).
    pub needs_fluid_tick: bool,
}

impl AquiferImpl {
    /// Construct per-chunk aquifer state. Mirrors vanilla's
    /// `Impl(ChunkNoiseSampler, ChunkPos, NoiseRouter, RandomSplitter,
    /// minimumY, height, FluidLevelSampler)` constructor, with
    /// dependencies inverted: the caller pre-resolves the splitter and
    /// the surface-height estimate (a single i32 covering the cell-
    /// padded query rectangle vanilla calls
    /// `chunkNoiseSampler.estimateHighestSurfaceLevel(...)`).
    ///
    /// `chunk_min_block_x/z`: `chunkPos.getStartX()/Z()`.
    /// `chunk_max_block_x/z`: `chunkPos.getEndX()/Z()`.
    /// `min_y`: vanilla's `minimumY` (chunk's bottom block-Y).
    /// `height`: vertical block range.
    /// `surface_height_estimate`: precomputed
    ///   `chunkNoiseSampler.estimateHighestSurfaceLevel(...)` over the
    ///   padded query rectangle (vanilla's
    ///   `method_72677(start_x, 0)` etc.). Java will pass this in.
    pub fn new(
        random_deriver: XoroshiroPositionalRandomFactory,
        fluid_level_sampler: FluidLevelSampler,
        dfs: AquiferDensityFunctions,
        surface_estimator: Box<dyn Fn(i32, i32) -> i32 + Send + Sync>,
        chunk_min_block_x: i32,
        chunk_min_block_z: i32,
        chunk_max_block_x: i32,
        chunk_max_block_z: i32,
        min_y: i32,
        height: i32,
        surface_height_estimate: i32,
    ) -> Self {
        // Vanilla constructor lines 126-145 — verbatim port.
        let start_x = get_local_x(chunk_min_block_x + -5) + 0;
        let end_x_cell = get_local_x(chunk_max_block_x + -5) + 1;
        let size_x = end_x_cell - start_x + 1;

        let start_y = get_local_y(min_y + 1) + -1;
        let end_y_cell = get_local_y(min_y + height + 1) + 1;
        let size_y = end_y_cell - start_y + 1;

        let start_z = get_local_z(chunk_min_block_z + -5) + 0;
        let end_z_cell = get_local_z(chunk_max_block_z + -5) + 1;
        let size_z = end_z_cell - start_z + 1;

        let total_cells = (size_x * size_y * size_z) as usize;

        // `field_61452` cap — see vanilla lines 140-144.
        let n = surface_height_plus_buffer(surface_height_estimate);
        let o = get_local_y(n + CELL_SIZE_Y) - -1;
        let high_cell_y_cap = cell_to_block_y(o, 11) - 1;

        Self {
            random_deriver,
            fluid_level_sampler,
            dfs,
            surface_estimator,
            start_x,
            start_y,
            start_z,
            size_x,
            size_y,
            size_z,
            high_cell_y_cap,
            // i32::MIN sentinel = uninitialized. Vanilla uses
            // Long.MAX_VALUE on a long[] array; we use a parallel
            // i32::MIN sentinel on x because (i32::MIN, _, _) is never
            // a valid block coordinate.
            block_positions_x: vec![i32::MIN; total_cells],
            block_positions_y: vec![0; total_cells],
            block_positions_z: vec![0; total_cells],
            water_levels: vec![None; total_cells],
            needs_fluid_tick: false,
        }
    }

    /// Vanilla's `index(int x, int y, int z)` — linearize cell-coords
    /// to the parallel arrays. Same packing order: `(y * size_z + z) *
    /// size_x + x`.
    #[inline]
    fn index(&self, cell_x: i32, cell_y: i32, cell_z: i32) -> usize {
        let i = cell_x - self.start_x;
        let j = cell_y - self.start_y;
        let k = cell_z - self.start_z;
        ((j * self.size_z + k) * self.size_x + i) as usize
    }

    /// Lazy-initialize the cell's randomized block position. Vanilla
    /// inlines this inside `apply`; we extract it for clarity.
    /// Returns `(block_x, block_y, block_z)`.
    #[inline]
    fn cell_block_position(&mut self, cell_x: i32, cell_y: i32, cell_z: i32) -> (i32, i32, i32) {
        let idx = self.index(cell_x, cell_y, cell_z);
        if self.block_positions_x[idx] != i32::MIN {
            return (
                self.block_positions_x[idx],
                self.block_positions_y[idx],
                self.block_positions_z[idx],
            );
        }
        let mut random = self.random_deriver.at(cell_x, cell_y, cell_z);
        // Vanilla calls nextInt(10), nextInt(9), nextInt(10) in that
        // order — order matters because each nextInt consumes one long
        // from the underlying generator.
        let off_x = random.next_int_bounded(RAND_BOUND_X);
        let off_y = random.next_int_bounded(RAND_BOUND_Y);
        let off_z = random.next_int_bounded(RAND_BOUND_Z);
        let bx = cell_to_block_x(cell_x, off_x);
        let by = cell_to_block_y(cell_y, off_y);
        let bz = cell_to_block_z(cell_z, off_z);
        self.block_positions_x[idx] = bx;
        self.block_positions_y[idx] = by;
        self.block_positions_z[idx] = bz;
        (bx, by, bz)
    }
}

// ============================================================================
// MathHelper analogues — vanilla `MathHelper.{clamp, clampedMap, map,
// roundDownToMultiple}` translated. Kept inline (no module split) since
// they're only used by the aquifer algorithm.
// ============================================================================

#[inline]
fn clamp_f64(v: f64, lo: f64, hi: f64) -> f64 {
    v.max(lo).min(hi)
}

/// Vanilla `MathHelper.map(value, lo_in, hi_in, lo_out, hi_out)` —
/// linear remap, no clamping.
#[inline]
fn linear_map(v: f64, lo_in: f64, hi_in: f64, lo_out: f64, hi_out: f64) -> f64 {
    let t = (v - lo_in) / (hi_in - lo_in);
    lo_out + t * (hi_out - lo_out)
}

/// Vanilla `MathHelper.clampedMap` — `map` plus output clamp.
#[inline]
fn clamped_map(v: f64, lo_in: f64, hi_in: f64, lo_out: f64, hi_out: f64) -> f64 {
    clamp_f64(linear_map(v, lo_in, hi_in, lo_out, hi_out), lo_out.min(hi_out), lo_out.max(hi_out))
}

/// Vanilla `MathHelper.roundDownToMultiple(double, int)` — floor toward
/// the nearest multiple of `step` (Java rounds toward zero on the
/// truncation, but `floor` matches the documented behavior for
/// negatives in this context).
#[inline]
fn round_down_to_multiple(v: f64, step: i32) -> i32 {
    (v / step as f64).floor() as i32 * step
}

// ============================================================================
// VanillaBiomeParameters stub — `inDeepDarkParameters(erosion, depth, pos)`
// ============================================================================
//
// Vanilla checks two conditions: `erosion.compute(pos) < -0.225` AND
// `depth.compute(pos) > 0.9`. Returning `false` here is correct for the
// vast majority of overworld blocks — the deep dark biome is small and
// blocks not in it must return `false`. This stub is precise as long as
// the erosion+depth DFs are stubbed (return 0); when wired to live DFs,
// it will be slightly wrong inside ancient cities. TODO: port properly
// before parity gates.
#[inline]
fn in_deep_dark_parameters(
    erosion: Option<&DensityFunction>,
    depth: Option<&DensityFunction>,
    state: &WorldgenState,
    block_x: i32,
    block_y: i32,
    block_z: i32,
) -> bool {
    let ctx = FunctionContext::new(block_x, block_y, block_z);
    let e = erosion.map(|df| df.compute(&ctx, state)).unwrap_or(0.0);
    let d = depth.map(|df| df.compute(&ctx, state)).unwrap_or(0.0);
    e < -0.225 && d > 0.9
}

// ============================================================================
// AquiferImpl algorithm body
// ============================================================================

impl AquiferImpl {
    /// Vanilla `apply(NoisePos pos, double density)` — line 156.
    /// Decides whether the block at `(block_x, block_y, block_z)` is
    /// fluid (some `FluidKind`) or solid (returns `None`).
    /// `density` is the per-block sample of `finalDensity` from
    /// `ChunkNoiseSampler`. Sets `self.needs_fluid_tick` as a side
    /// effect.
    pub fn apply(
        &mut self,
        block_x: i32,
        block_y: i32,
        block_z: i32,
        density: f64,
        state: &WorldgenState,
    ) -> Option<FluidKind> {
        // Density-positive blocks are stone/air per vanilla — aquifer
        // doesn't override.
        if density > 0.0 {
            self.needs_fluid_tick = false;
            return None;
        }

        let dimension_fluid = self.fluid_level_sampler.get_fluid_level(block_x, block_y, block_z);

        // Above the high-Y cap → just return the dimension default.
        if block_y > self.high_cell_y_cap {
            self.needs_fluid_tick = false;
            return Some(dimension_fluid.block_state(block_y));
        }

        // Inside lava territory → place lava (the dimension default
        // already says LAVA at this Y).
        if dimension_fluid.block_state(block_y) == FluidKind::Lava {
            self.needs_fluid_tick = false;
            return Some(FluidKind::Lava);
        }

        // 4-best ranking over the 2×3×2 cell neighborhood.
        let cell_x = get_local_x(block_x + -5);
        let cell_y = get_local_y(block_y + 1);
        let cell_z = get_local_z(block_z + -5);

        // Indices into our parallel arrays for the 4 closest cells, in
        // sq-distance order.
        let (mut idx0, mut idx1, mut idx2, mut idx3) = (0usize, 0usize, 0usize, 0usize);
        // Squared distances corresponding to idx0..3.
        let (mut d0, mut d1, mut d2, mut d3) =
            (i32::MAX, i32::MAX, i32::MAX, i32::MAX);

        for w in 0..=1i32 {
            for x in -1..=1i32 {
                for y in 0..=1i32 {
                    let cz_x = cell_x + w;
                    let cz_y = cell_y + x;
                    let cz_z = cell_z + y;
                    let ac = self.index(cz_x, cz_y, cz_z);
                    let (bx, by, bz) = self.cell_block_position(cz_x, cz_y, cz_z);
                    let af = bx - block_x;
                    let ag = by - block_y;
                    let ah = bz - block_z;
                    let sq = af * af + ag * ag + ah * ah;

                    // 4-way insertion sort. Vanilla uses `>=` so ties
                    // shift older entries down (stable-ish — last
                    // visited tied entry wins index 0).
                    if d0 >= sq {
                        idx3 = idx2; idx2 = idx1; idx1 = idx0; idx0 = ac;
                        d3 = d2; d2 = d1; d1 = d0; d0 = sq;
                    } else if d1 >= sq {
                        idx3 = idx2; idx2 = idx1; idx1 = ac;
                        d3 = d2; d2 = d1; d1 = sq;
                    } else if d2 >= sq {
                        idx3 = idx2; idx2 = ac;
                        d3 = d2; d2 = sq;
                    } else if d3 >= sq {
                        idx3 = ac;
                        d3 = sq;
                    }
                }
            }
        }

        // Closest cell's fluid level.
        let level_a = self.get_water_level(idx0, state);
        let dist_ab = max_distance(d0, d1);
        let block_at_a = level_a.block_state(block_y);

        if dist_ab <= 0.0 {
            // Closest cell dominates by a wide margin — just place
            // its fluid (or air if above level_a.y).
            if dist_ab >= NEEDS_FLUID_TICK_DISTANCE_THRESHOLD {
                let level_b = self.get_water_level(idx1, state);
                self.needs_fluid_tick = level_a != level_b;
            } else {
                self.needs_fluid_tick = false;
            }
            return Some(block_at_a);
        }

        // Lava-water-adjacency check: if level_a says water and the
        // block one below is lava territory, force-tick water.
        if level_a.is_water() {
            let below = self.fluid_level_sampler.get_fluid_level(block_x, block_y - 1, block_z);
            if below.block_state(block_y - 1) == FluidKind::Lava {
                self.needs_fluid_tick = true;
                return Some(block_at_a);
            }
        }

        // Barrier-density modulated blend with 2nd-closest cell.
        let mut barrier_cache: Option<f64> = None;
        let level_b = self.get_water_level(idx1, state);
        let e = dist_ab * self.calculate_density(block_x, block_y, block_z, &mut barrier_cache, level_a, level_b, state);
        if density + e > 0.0 {
            self.needs_fluid_tick = false;
            return None;
        }

        // 3rd-closest contribution.
        let level_c = self.get_water_level(idx2, state);
        let dist_ac = max_distance(d0, d2);
        if dist_ac > 0.0 {
            let g = dist_ab * dist_ac * self.calculate_density(block_x, block_y, block_z, &mut barrier_cache, level_a, level_c, state);
            if density + g > 0.0 {
                self.needs_fluid_tick = false;
                return None;
            }
        }
        let dist_bc = max_distance(d1, d2);
        if dist_bc > 0.0 {
            let h = dist_ab * dist_bc * self.calculate_density(block_x, block_y, block_z, &mut barrier_cache, level_b, level_c, state);
            if density + h > 0.0 {
                self.needs_fluid_tick = false;
                return None;
            }
        }

        // Fluid-tick decision based on which neighboring water levels
        // disagree with `level_a` and how close they are.
        let bl = level_a != level_b;
        let bl2 = dist_bc >= NEEDS_FLUID_TICK_DISTANCE_THRESHOLD && level_b != level_c;
        let bl3 = dist_ac >= NEEDS_FLUID_TICK_DISTANCE_THRESHOLD && level_a != level_c;
        if !bl && !bl2 && !bl3 {
            let level_d = self.get_water_level(idx3, state);
            self.needs_fluid_tick = dist_ac >= NEEDS_FLUID_TICK_DISTANCE_THRESHOLD
                && max_distance(d0, d3) >= NEEDS_FLUID_TICK_DISTANCE_THRESHOLD
                && level_a != level_d;
        } else {
            self.needs_fluid_tick = true;
        }

        Some(block_at_a)
    }

    /// Vanilla `getWaterLevel(int)` line 389. Lazy-init: cell `idx`'s
    /// water level is computed once via `get_fluid_level_for` and
    /// memoized.
    fn get_water_level(&mut self, idx: usize, state: &WorldgenState) -> FluidLevel {
        if let Some(level) = self.water_levels[idx] {
            return level;
        }
        let bx = self.block_positions_x[idx];
        let by = self.block_positions_y[idx];
        let bz = self.block_positions_z[idx];
        let computed = self.get_fluid_level_for(bx, by, bz, state);
        self.water_levels[idx] = Some(computed);
        computed
    }

    /// Vanilla `getFluidLevel(int, int, int)` line 401. Walks 13 chunk-
    /// position offsets, calling the surface-height estimator at each
    /// to decide whether the cell is below or above the surface, then
    /// derives a fluid-Y from noise-driven helpers.
    fn get_fluid_level_for(
        &self,
        block_x: i32,
        block_y: i32,
        block_z: i32,
        state: &WorldgenState,
    ) -> FluidLevel {
        let dimension_fluid = self.fluid_level_sampler.get_fluid_level(block_x, block_y, block_z);
        let mut min_surface = i32::MAX;
        let above_threshold_y = block_y + 12;
        let below_threshold_y = block_y - 12;
        let mut center_above_surface_air_block = false;

        for offset in CHUNK_POS_OFFSETS.iter() {
            let l = block_x + (offset[0] << 4); // ChunkSectionPos.getBlockCoord = << 4
            let m = block_z + (offset[1] << 4);
            let surface = (self.surface_estimator)(l, m);
            let surface_capped = surface_height_plus_buffer(surface);
            let is_center = offset[0] == 0 && offset[1] == 0;

            // Center cell entirely below the surface (well underground)
            // — return the dimension default fluid for this column.
            if is_center && below_threshold_y > surface_capped {
                return dimension_fluid;
            }

            let any_above_surface = above_threshold_y > surface_capped;
            if any_above_surface || is_center {
                let surface_fluid = self.fluid_level_sampler.get_fluid_level(l, surface_capped, m);
                if surface_fluid.block_state(surface_capped) != FluidKind::Air {
                    if is_center {
                        center_above_surface_air_block = true;
                    }
                    if any_above_surface {
                        return surface_fluid;
                    }
                }
            }

            min_surface = min_surface.min(surface);
        }

        let fluid_y = self.compute_fluid_block_y(
            block_x, block_y, block_z, dimension_fluid, min_surface, center_above_surface_air_block, state,
        );
        let kind = self.compute_fluid_block_kind(block_x, block_y, block_z, dimension_fluid, fluid_y, state);
        FluidLevel::new(fluid_y, kind)
    }

    /// Vanilla `getFluidBlockY(int, int, int, FluidLevel, int, boolean)`
    /// line 443.
    #[allow(clippy::too_many_arguments)]
    fn compute_fluid_block_y(
        &self,
        block_x: i32,
        block_y: i32,
        block_z: i32,
        default_fluid: FluidLevel,
        surface_estimate: i32,
        center_air_block_above: bool,
        state: &WorldgenState,
    ) -> i32 {
        let (d, e) = if in_deep_dark_parameters(
            self.dfs.erosion.as_ref(),
            self.dfs.depth.as_ref(),
            state,
            block_x,
            block_y,
            block_z,
        ) {
            (-1.0, -1.0)
        } else {
            let i = surface_estimate + 8 - block_y;
            let f = if center_air_block_above {
                clamped_map(i as f64, 0.0, 64.0, 1.0, 0.0)
            } else {
                0.0
            };
            let g = self
                .dfs
                .fluid_level_floodedness_noise
                .as_ref()
                .map(|df| df.compute(&FunctionContext::new(block_x, block_y, block_z), state))
                .unwrap_or(0.0);
            let g_clamped = clamp_f64(g, -1.0, 1.0);
            let h = linear_map(f, 1.0, 0.0, -0.3, 0.8);
            let k = linear_map(f, 1.0, 0.0, -0.8, 0.4);
            (g_clamped - k, g_clamped - h)
        };

        if e > 0.0 {
            default_fluid.y
        } else if d > 0.0 {
            self.compute_noise_based_fluid_level(block_x, block_y, block_z, surface_estimate, state)
        } else {
            VERY_DEEP_FLUID_FLOOR
        }
    }

    /// Vanilla `getNoiseBasedFluidLevel` line 473.
    fn compute_noise_based_fluid_level(
        &self,
        block_x: i32,
        block_y: i32,
        block_z: i32,
        surface_estimate: i32,
        state: &WorldgenState,
    ) -> i32 {
        let k = block_x.div_euclid(16);
        let l = block_y.div_euclid(40);
        let m = block_z.div_euclid(16);
        let n = l * 40 + 20;
        let raw = self
            .dfs
            .fluid_level_spread_noise
            .as_ref()
            .map(|df| df.compute(&FunctionContext::new(k, l, m), state))
            .unwrap_or(0.0)
            * 10.0;
        let p = round_down_to_multiple(raw, 3);
        let q = n + p;
        surface_estimate.min(q)
    }

    /// Vanilla `getFluidBlockState` line 487. Decides WATER vs LAVA at
    /// `(block_x, block_y, block_z)` given the dimension default and a
    /// fluid-Y. Mostly defers to `default_fluid.kind`; flips to LAVA
    /// only deep underground when the fluid-type noise crosses a
    /// magnitude threshold.
    fn compute_fluid_block_kind(
        &self,
        block_x: i32,
        _block_y: i32,
        block_z: i32,
        default_fluid: FluidLevel,
        fluid_y: i32,
        state: &WorldgenState,
    ) -> FluidKind {
        let mut kind = default_fluid.kind;
        if fluid_y <= -10 && fluid_y != VERY_DEEP_FLUID_FLOOR && !default_fluid.is_lava() {
            let k = block_x.div_euclid(64);
            let l = fluid_y.div_euclid(40);
            let m = block_z.div_euclid(64);
            let d = self
                .dfs
                .fluid_type_noise
                .as_ref()
                .map(|df| df.compute(&FunctionContext::new(k, l, m), state))
                .unwrap_or(0.0);
            if d.abs() > 0.3 {
                kind = FluidKind::Lava;
            }
        }
        kind
    }

    /// Vanilla `calculateDensity(NoisePos, MutableDouble, FluidLevel,
    /// FluidLevel)` line 305. The barrier-noise modulated ridge density
    /// between two fluid levels. `barrier_cache` mirrors vanilla's
    /// `MutableDouble` lazy cache — barrier noise is sampled at most
    /// once per `apply` call.
    #[allow(clippy::too_many_arguments)]
    fn calculate_density(
        &self,
        block_x: i32,
        block_y: i32,
        block_z: i32,
        barrier_cache: &mut Option<f64>,
        a: FluidLevel,
        b: FluidLevel,
        state: &WorldgenState,
    ) -> f64 {
        let i = block_y;
        let state_a = a.block_state(i);
        let state_b = b.block_state(i);

        // If lava-vs-water boundary at this Y, vanilla returns 2.0.
        let lava_water_pair = (state_a == FluidKind::Lava && state_b == FluidKind::Water)
            || (state_a == FluidKind::Water && state_b == FluidKind::Lava);
        if lava_water_pair {
            return 2.0;
        }

        let j = (a.y - b.y).abs();
        if j == 0 {
            return 0.0;
        }

        let d = 0.5 * ((a.y + b.y) as f64);
        let e = (i as f64) + 0.5 - d;
        let f = (j as f64) / 2.0;
        let o = f - e.abs();
        let q = if e > 0.0 {
            let p = 0.0 + o;
            if p > 0.0 { p / 1.5 } else { p / 2.5 }
        } else {
            let p = 3.0 + o;
            if p > 0.0 { p / 3.0 } else { p / 10.0 }
        };

        if !(q < -2.0) && !(q > 2.0) {
            // In the transition band — sample (or reuse) barrier noise
            // and add to the displacement.
            let s = match *barrier_cache {
                Some(v) => v,
                None => {
                    let t = self
                        .dfs
                        .barrier_noise
                        .as_ref()
                        .map(|df| df.compute(&FunctionContext::new(block_x, block_y, block_z), state))
                        .unwrap_or(0.0);
                    *barrier_cache = Some(t);
                    t
                }
            };
            let r = if !(s < -2.0) && !(s > 2.0) {
                if q > 0.0 {
                    if s + q > 0.0 { s } else { q }
                } else if s - q > 0.0 {
                    s
                } else {
                    q
                }
            } else {
                0.0
            };
            2.0 * (r + q)
        } else {
            2.0
        }
    }
}

// ============================================================================
// Tests
// ============================================================================

#[cfg(test)]
mod tests {
    use super::*;
    use crate::xoroshiro::XoroshiroRandomSource;
    use std::collections::HashMap;

    /// Empty `WorldgenState` for unit tests where DFs aren't needed —
    /// every DF lookup misses, falls through to `0.0` defaults.
    fn empty_state() -> WorldgenState {
        let mut root = XoroshiroRandomSource::from_legacy_seed(0);
        let root_factory = root.fork_positional();
        WorldgenState {
            seed: 0,
            root_factory,
            noises: HashMap::new(),
            biome_tree: None,
            density_functions: HashMap::new(),
        }
    }

    #[test]
    fn coord_helpers_match_vanilla_for_negative_coords() {
        // Vanilla's `>> 4` and `floorDiv(_, 12)` semantics differ for
        // negative inputs — these spot-checks cover the corners.
        assert_eq!(get_local_x(-1), -1, "block_x=-1 → cell_x=-1");
        assert_eq!(get_local_x(-16), -1);
        assert_eq!(get_local_x(-17), -2);
        assert_eq!(get_local_x(15), 0);
        assert_eq!(get_local_x(16), 1);

        assert_eq!(get_local_y(-1), -1, "block_y=-1 → cell_y=-1 (floorDiv)");
        assert_eq!(get_local_y(-12), -1);
        assert_eq!(get_local_y(-13), -2);
        assert_eq!(get_local_y(0), 0);
        assert_eq!(get_local_y(11), 0);
        assert_eq!(get_local_y(12), 1);
    }

    #[test]
    fn cell_to_block_round_trips_zero_offset() {
        for cell in -8..8 {
            assert_eq!(get_local_x(cell_to_block_x(cell, 0)), cell);
            assert_eq!(get_local_y(cell_to_block_y(cell, 0)), cell);
            assert_eq!(get_local_z(cell_to_block_z(cell, 0)), cell);
        }
    }

    #[test]
    fn fluid_level_block_state_threshold() {
        let lvl = FluidLevel::new(63, FluidKind::Water);
        assert_eq!(lvl.block_state(62), FluidKind::Water);
        assert_eq!(lvl.block_state(63), FluidKind::Air, "y=fluid_y is air");
        assert_eq!(lvl.block_state(64), FluidKind::Air);
    }

    #[test]
    fn overworld_sampler_lava_below_water_above() {
        let s = FluidLevelSampler::overworld(63);
        // Above sea level: water with sea-level y.
        assert_eq!(
            s.get_fluid_level(0, 100, 0),
            FluidLevel::new(63, FluidKind::Water)
        );
        // Below the cutoff (min(-54, 63) = -54): lava.
        assert_eq!(
            s.get_fluid_level(0, -55, 0),
            FluidLevel::new(-54, FluidKind::Lava)
        );
        // Right at the cutoff: water (cutoff is exclusive on the lava
        // side, vanilla uses `y < cutoff`).
        assert_eq!(
            s.get_fluid_level(0, -54, 0),
            FluidLevel::new(63, FluidKind::Water)
        );
    }

    #[test]
    fn apply_density_positive_returns_none() {
        let factory = XoroshiroPositionalRandomFactory::new(1, 2);
        let mut aq = AquiferImpl::new(
            factory,
            FluidLevelSampler::overworld(63),
            AquiferDensityFunctions::empty(),
            Box::new(|_, _| 72),
            0, 0, 15, 15, -64, 384, 72,
        );
        let state = empty_state();
        // Density > 0 → solid block, no aquifer override.
        assert_eq!(aq.apply(8, 50, 8, 0.5, &state), None);
        assert!(!aq.needs_fluid_tick);
    }

    #[test]
    fn apply_above_high_cap_returns_dimension_default() {
        let factory = XoroshiroPositionalRandomFactory::new(1, 2);
        // Surface estimate of 30 → high_cell_y_cap = (30+8) snapped, etc.
        // Pick a Y high above the estimate; should return AIR (dimension default
        // says water above sea_level=63, but the block is at y=200 which is
        // > water level → AIR).
        let mut aq = AquiferImpl::new(
            factory,
            FluidLevelSampler::overworld(63),
            AquiferDensityFunctions::empty(),
            Box::new(|_, _| 30),
            0, 0, 15, 15, -64, 384, 30,
        );
        let state = empty_state();
        // density <= 0 (cave/fluid territory) so we don't bail at the
        // density-positive branch; but block_y > high_cell_y_cap so we
        // short-circuit to dimension_fluid.block_state(y).
        let result = aq.apply(8, 200, 8, -0.5, &state);
        // dimension_fluid for overworld at y=200 is FluidLevel(63, Water).
        // block_state(200) is AIR since 200 >= 63.
        assert_eq!(result, Some(FluidKind::Air));
        assert!(!aq.needs_fluid_tick);
    }

    #[test]
    fn apply_below_lava_y_returns_lava() {
        let factory = XoroshiroPositionalRandomFactory::new(1, 2);
        let mut aq = AquiferImpl::new(
            factory,
            FluidLevelSampler::overworld(63),
            AquiferDensityFunctions::empty(),
            Box::new(|_, _| 30),
            0, 0, 15, 15, -64, 384, 30,
        );
        let state = empty_state();
        // y = -100 < cutoff(-54) → dimension_fluid says lava territory.
        // block_state(-100) is Lava since -100 < lava_y(-54).
        // y > high_cell_y_cap is false here (y=-100 well below), but the
        // dimension_fluid lava branch fires first regardless.
        // Actually wait — the high_cap branch is `if y > cap`. cap is computed
        // from surface_estimate=30 and ends up around 47-ish; -100 is not above.
        // So we hit the lava branch directly.
        let result = aq.apply(8, -100, 8, -0.5, &state);
        assert_eq!(result, Some(FluidKind::Lava));
        assert!(!aq.needs_fluid_tick);
    }

    #[test]
    fn needs_fluid_tick_threshold_constant_matches_inline_computation() {
        // Sanity-check the pre-computed constant against the formula.
        let manual = 1.0 - ((144 - 100) as f64) / 25.0;
        assert!((NEEDS_FLUID_TICK_DISTANCE_THRESHOLD - manual).abs() < 1e-12);
    }

    #[test]
    fn aquifer_impl_constructor_sizes_are_consistent() {
        // Vanilla overworld chunk at (0, 0): chunkMinBlockX=0,
        // chunkMaxBlockX=15. minY=-64, height=384.
        // After the +/-5 padding and >>4: start_x = (0-5)>>4 = -1,
        // end_x_cell = (15-5)>>4 + 1 = 1. size_x = 1 - (-1) + 1 = 3.
        let factory = XoroshiroPositionalRandomFactory::new(1, 2);
        let aq = AquiferImpl::new(
            factory,
            FluidLevelSampler::overworld(63),
            AquiferDensityFunctions::empty(),
            Box::new(|_x, _z| 72), // mock surface estimator
            0,   // chunk_min_block_x
            0,   // chunk_min_block_z
            15,  // chunk_max_block_x
            15,  // chunk_max_block_z
            -64, // min_y
            384, // height
            72,  // surface_height_estimate (made up)
        );
        assert_eq!(aq.size_x, 3, "size_x for chunk (0,0)");
        assert_eq!(aq.size_z, 3);
        // size_y depends on height + start_y math; just sanity check
        // it's positive and matches the array allocation.
        assert!(aq.size_y > 0);
        let total = (aq.size_x * aq.size_y * aq.size_z) as usize;
        assert_eq!(aq.block_positions_x.len(), total);
        assert_eq!(aq.water_levels.len(), total);
        assert_eq!(aq.needs_fluid_tick, false);
    }
}
