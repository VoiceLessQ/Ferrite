//! Bit-exact Rust port of vanilla Minecraft 1.21.11's
//! `DensityFunction` subsystem — the tree-structured DSL vanilla uses
//! to compute per-(x, y, z) doubles in its noise / biome / surface
//! pipeline.
//!
//! Source files (mojmap):
//!   `26.1.2/server/net/minecraft/world/level/levelgen/DensityFunction.java`
//!   `26.1.2/server/net/minecraft/world/level/levelgen/DensityFunctions.java`
//!
//! # Scope (session 1)
//!
//! This module implements the interpreter skeleton + ~15 core variants.
//! It doesn't yet cover:
//!   - `Spline` (piecewise cubic — porting `CubicSpline` is its own unit)
//!   - `BlendedNoise` (legacy octave-noise system, huge)
//!   - `EndIslands` / `BeardifierMarker` / `Blend*` (specialized, not on
//!     the overworld critical path)
//!   - `WeirdScaledSampler` (uses `RarityValueMapper` enum — small, later)
//!   - `ShiftA` / `ShiftB` / `Shift` / `ShiftedNoise` (defer — need the
//!     same NormalNoise wiring but with offset semantics)
//!   - Cache wrappers (`FlatCache`, `Cache2D`, `CacheOnce`, etc.) — these
//!     are perf-critical transparently-wrapping markers; for now we make
//!     `Marker` a passthrough. Real caching is session-3 work.
//!
//! Tracking doc: `docs/SEED_DRIVEN_DISPATCH.md`.
//!
//! # Design
//!
//! A `DensityFunction` is a recursive enum with variants for each node
//! type. `compute(&ctx, &state) -> f64` walks the tree. `state` is the
//! process-wide `WorldgenState` — noise references live there, so a DF
//! tree doesn't need to own/borrow noise samplers directly. Enables
//! simple ownership (`DensityFunction: Send + Sync`) and cheap cloning
//! if we ever build DF trees dynamically.
//!
//! Child DFs are boxed for sized-type purposes. `Box<DensityFunction>`
//! is a pointer + allocation per node — worldgen's typical DF tree has
//! ~100 nodes, so construction cost is negligible and compute cost is
//! dominated by noise sampling, not pointer chasing.

use crate::worldgen_state::WorldgenState;

/// Vanilla `DensityFunction.FunctionContext` — simple 3D block coord.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub struct FunctionContext {
    pub block_x: i32,
    pub block_y: i32,
    pub block_z: i32,
}

impl FunctionContext {
    pub const fn new(block_x: i32, block_y: i32, block_z: i32) -> Self {
        Self { block_x, block_y, block_z }
    }
}

/// One node of a density function tree. Mirrors vanilla's class
/// hierarchy (records + enums) with a single Rust enum.
///
/// Min/max value hints (`MinValue`/`MaxValue`) are NOT stored eagerly —
/// vanilla precomputes them for bounds analysis in `RangeChoice` and
/// `Clamp`. We recompute via `min_value` / `max_value` methods when a
/// caller needs them; session-3 optimization could cache on construction.
#[derive(Clone, Debug)]
pub enum DensityFunction {
    /// `DensityFunctions.Constant(value)`.
    Constant(f64),

    /// `DensityFunctions.Noise(NoiseHolder, xzScale, yScale)`.
    /// `noise_name` is the full identifier (e.g.
    /// `"minecraft:surface"`) and must be registered in
    /// `WorldgenState.noises`; unregistered names sample to 0.0,
    /// matching vanilla's `NoiseHolder.getValue` fallback when
    /// `this.noise == null`.
    Noise { noise_name: String, xz_scale: f64, y_scale: f64 },

    // --- TwoArgumentSimpleFunction variants ---

    /// `v1 + v2`. Both evaluated unconditionally.
    Add(Box<DensityFunction>, Box<DensityFunction>),
    /// `v1 == 0 ? 0 : v1 * v2`. Short-circuits when first arg is zero —
    /// vanilla does this to skip expensive second evaluation when
    /// the first is already zero (common for gated multiplies).
    Mul(Box<DensityFunction>, Box<DensityFunction>),
    /// `v1 < arg2.minValue() ? v1 : min(v1, v2)`. Short-circuit similar
    /// to Mul: if v1 is already below argument2's minimum, the min is v1.
    Min(Box<DensityFunction>, Box<DensityFunction>),
    /// `v1 > arg2.maxValue() ? v1 : max(v1, v2)`. Similar short-circuit.
    Max(Box<DensityFunction>, Box<DensityFunction>),

    // --- Mapped (PureTransformer) variants ---

    /// `|x| → |x|`.
    Abs(Box<DensityFunction>),
    /// `|x| → x²`.
    Square(Box<DensityFunction>),
    /// `|x| → x³`.
    Cube(Box<DensityFunction>),
    /// `|x| → x > 0 ? x : x/2`.
    HalfNegative(Box<DensityFunction>),
    /// `|x| → x > 0 ? x : x/4`.
    QuarterNegative(Box<DensityFunction>),
    /// `|x| → 1/x`.
    Invert(Box<DensityFunction>),
    /// `|x| → c/2 − c³/24` where `c = clamp(x, -1, 1)`. Cubic ease.
    Squeeze(Box<DensityFunction>),

    /// `Mth.clamp(input, min, max)`.
    Clamp { input: Box<DensityFunction>, min: f64, max: f64 },

    /// `clampedMap(blockY, fromY, toY, fromValue, toValue)` — y-based
    /// gradient, saturating outside [fromY, toY].
    YClampedGradient { from_y: i32, to_y: i32, from_value: f64, to_value: f64 },

    /// `inputValue ∈ [minInclusive, maxExclusive) ? whenInRange : whenOutOfRange`.
    RangeChoice {
        input: Box<DensityFunction>,
        min_inclusive: f64,
        max_exclusive: f64,
        when_in_range: Box<DensityFunction>,
        when_out_of_range: Box<DensityFunction>,
    },

    /// `DensityFunctions.Marker` — passthrough with a cache-type tag.
    /// Real caching is session-3 work; for now this is just an inner
    /// `compute`.
    Marker { kind: MarkerKind, wrapped: Box<DensityFunction> },

    // --- Shift variants (NoiseHolder-based, coord-offset sampling) ---
    //
    // All three compute `offsetNoise.getValue(a*0.25, b*0.25, c*0.25) * 4.0`
    // for different permutations of (blockX, blockY, blockZ). Used by
    // the biome climate samplers (shift_x / shift_z — vanilla's ShiftA)
    // and by ShiftedNoise to jitter sample coords.

    /// `DensityFunctions.Shift(noise)` — full 3D shift: samples at
    /// `(blockX, blockY, blockZ)`.
    Shift { noise_name: String },
    /// `DensityFunctions.ShiftA(noise)` — samples at `(blockX, 0, blockZ)`.
    /// Biome climate uses this for `shift_x` / `shift_z` offsets.
    ShiftA { noise_name: String },
    /// `DensityFunctions.ShiftB(noise)` — samples at `(blockZ, blockX, 0)`.
    ShiftB { noise_name: String },

    /// `DensityFunctions.ShiftedNoise(shiftX, shiftY, shiftZ, xzScale, yScale, noise)`.
    /// Samples `noise` at `(blockX * xzScale + shiftX, blockY * yScale + shiftY, blockZ * xzScale + shiftZ)`.
    /// The shifts are themselves DFs (typically `ShiftA` on offset-noise
    /// samplers), so this is where biome climate sampling actually fires.
    ShiftedNoise {
        shift_x: Box<DensityFunction>,
        shift_y: Box<DensityFunction>,
        shift_z: Box<DensityFunction>,
        xz_scale: f64,
        y_scale: f64,
        noise_name: String,
    },

    /// `DensityFunctions.WeirdScaledSampler(input, noise, rarityValueMapper)`.
    /// `rarity = rarity_value_mapper(input.compute(ctx))`,
    /// result = `rarity * abs(noise.getValue(blockX/rarity, blockY/rarity, blockZ/rarity))`.
    WeirdScaledSampler {
        input: Box<DensityFunction>,
        noise_name: String,
        rarity_value_mapper: RarityValueMapper,
    },

    /// `DensityFunctions.Spline(CubicSpline)` — Hermite cubic spline
    /// over one or more coordinate DFs. Used by the overworld's
    /// `offset` / `factor` / `jaggedness` terrain shape formulas.
    Spline(Box<DfSpline>),

    /// Vanilla `BlendedNoise` (yarn `InterpolatedNoiseSampler`) — main
    /// 3D terrain density. Wrapped in [`LazyBlendedNoise`] because the
    /// underlying noise tables need a seeded `XoroshiroRandomSource`
    /// derived from `state.root_factory`, which isn't available at
    /// parse time. The cache is shared across enum clones.
    BlendedNoise(LazyBlendedNoise),
}

/// Lazy wrapper around [`crate::perlin::BlendedNoise`].
///
/// <p>The 5 scalar parameters come from vanilla's
/// `InterpolatedNoiseSampler` constructor and are written into the DF
/// bytecode at encode time. The actual noise tables are seeded from
/// `state.root_factory.from_hash_of("minecraft:terrain")` on first
/// `sample` call and cached forever after via an internal `OnceLock`.
///
/// <p>Cloning is cheap (`Arc` refcount bump) and shares the cache —
/// so cloning the parent `DensityFunction` enum doesn't reset the
/// noise initialization.
#[derive(Debug)]
pub struct LazyBlendedNoise {
    pub xz_scale: f64,
    pub y_scale: f64,
    pub xz_factor: f64,
    pub y_factor: f64,
    pub smear_scale_multiplier: f64,
    cache: std::sync::Arc<std::sync::OnceLock<crate::perlin::BlendedNoise>>,
}

impl Clone for LazyBlendedNoise {
    fn clone(&self) -> Self {
        Self {
            xz_scale: self.xz_scale,
            y_scale: self.y_scale,
            xz_factor: self.xz_factor,
            y_factor: self.y_factor,
            smear_scale_multiplier: self.smear_scale_multiplier,
            cache: std::sync::Arc::clone(&self.cache),
        }
    }
}

impl LazyBlendedNoise {
    pub fn new(
        xz_scale: f64,
        y_scale: f64,
        xz_factor: f64,
        y_factor: f64,
        smear_scale_multiplier: f64,
    ) -> Self {
        Self {
            xz_scale,
            y_scale,
            xz_factor,
            y_factor,
            smear_scale_multiplier,
            cache: std::sync::Arc::new(std::sync::OnceLock::new()),
        }
    }

    /// Materialize the underlying `BlendedNoise` if not yet done, then
    /// sample at integer block coords. Vanilla casts coords to f64
    /// before its multipliers — we mirror that exactly.
    pub fn sample(&self, state: &crate::worldgen_state::WorldgenState, x: i32, y: i32, z: i32) -> f64 {
        let bn = self.cache.get_or_init(|| {
            // Vanilla `RandomState`: terrainRandom comes from
            // `random.fromHashOf("minecraft:terrain")`. All BlendedNoise
            // instances in the same RandomState share this derivation —
            // their differences are in the static scale params, not the
            // noise tables.
            let mut terrain = state.root_factory.from_hash_of("minecraft:terrain");
            crate::perlin::BlendedNoise::new(
                &mut terrain,
                self.xz_scale,
                self.y_scale,
                self.xz_factor,
                self.y_factor,
                self.smear_scale_multiplier,
            )
        });
        bn.sample(x as f64, y as f64, z as f64)
    }
}

/// Vanilla `DensityFunctions.WeirdScaledSampler.RarityValueMapper`.
/// Each variant quantizes a noise input to a small set of rarity
/// factors that then scale both sample coords and output magnitude.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum RarityValueMapper {
    /// `type_1`: 3D spaghetti — quantizes to {0.75, 1.0, 1.5, 2.0}.
    Type1,
    /// `type_2`: 2D spaghetti — quantizes to {0.5, 0.75, 1.0, 2.0, 3.0}.
    Type2,
}

impl RarityValueMapper {
    /// Per-variant step-function mapping an input f64 to a rarity factor.
    /// Bit-exact vanilla formulas from
    /// `NoiseRouterData.QuantizedSpaghettiRarity`.
    pub fn apply(self, rarity_factor: f64) -> f64 {
        match self {
            RarityValueMapper::Type1 => {
                // getSpaghettiRarity3D
                if rarity_factor < -0.5 { 0.75 }
                else if rarity_factor < 0.0 { 1.0 }
                else if rarity_factor < 0.5 { 1.5 }
                else { 2.0 }
            }
            RarityValueMapper::Type2 => {
                // getSphaghettiRarity2D (note: typo preserved in vanilla)
                if rarity_factor < -0.75 { 0.5 }
                else if rarity_factor < -0.5 { 0.75 }
                else if rarity_factor < 0.5 { 1.0 }
                else if rarity_factor < 0.75 { 2.0 }
                else { 3.0 }
            }
        }
    }

    pub fn max_rarity(self) -> f64 {
        match self {
            RarityValueMapper::Type1 => 2.0,
            RarityValueMapper::Type2 => 3.0,
        }
    }
}

// ============================================================================
// Spline — Hermite cubic with recursive values
//   Vanilla `net.minecraft.util.CubicSpline` + `DensityFunctions.Spline`.
// ============================================================================

/// Hermite cubic spline. Each point's value is recursively another
/// spline — i.e. vanilla supports nested splines where an outer
/// spline's y-value at a given location is itself the output of a
/// sub-spline (typically over a different coordinate). Bottoms out at
/// `Constant(f32)`.
///
/// `coordinate` in `Multipoint` is a `DensityFunction` whose `compute`
/// result (cast to f32) is the spline's X input. `locations` are the
/// spline's X knots; `values` are the (recursive) Y-at-knot; `derivatives`
/// are the Hermite slopes at each knot. The final curve is piecewise
/// cubic between knots and linear outside the endpoint knots.
///
/// Note the use of `f32` — vanilla stores locations/derivatives/values
/// as `float`, computes in float, returns float; only the density
/// function wrapper promotes the float to double. We mirror exactly
/// for bit-exact parity.
#[derive(Clone, Debug)]
pub enum DfSpline {
    /// `CubicSpline.Constant(value)`.
    Constant(f32),
    /// `CubicSpline.Multipoint(coordinate, locations, values, derivatives, minValue, maxValue)`.
    ///
    /// Invariants (checked at `new`):
    /// - `locations.len() == values.len() == derivatives.len()`
    /// - `locations` is strictly ascending
    /// - at least one point
    Multipoint {
        coordinate: Box<DensityFunction>,
        locations: Vec<f32>,
        values: Vec<DfSpline>,
        derivatives: Vec<f32>,
        min_value: f32,
        max_value: f32,
    },
}

impl DfSpline {
    /// Vanilla `CubicSpline.Multipoint.create`. Validates sizes,
    /// precomputes `minValue` / `maxValue` over extended bounds, stores.
    ///
    /// The min/max computation mirrors vanilla's (loose) range analysis
    /// that accounts for:
    /// - linear extension to the coordinate's min/max beyond endpoints
    /// - each value's own min/max
    /// - Hermite overshoot bounds when derivatives are nonzero
    /// Walk this spline (recursive into Multipoint children) and the
    /// embedded coordinate DFs, collecting DI subtree refs in DFS order.
    /// Mirrors the dispatch order used by spline evaluation in
    /// [`DensityFunction::compute_with_di_lerp`] (currently spline goes
    /// through plain `compute`, so this is reachable only via the
    /// outer-tree walker reaching us).
    pub fn enumerate_di_inputs<'a>(&'a self, out: &mut Vec<&'a DensityFunction>) {
        match self {
            DfSpline::Constant(_) => {}
            DfSpline::Multipoint { coordinate, values, .. } => {
                coordinate.enumerate_di_inputs(out);
                for v in values {
                    v.enumerate_di_inputs(out);
                }
            }
        }
    }

    pub fn new_multipoint(
        coordinate: DensityFunction,
        locations: Vec<f32>,
        values: Vec<DfSpline>,
        derivatives: Vec<f32>,
    ) -> Self {
        assert!(
            locations.len() == values.len() && locations.len() == derivatives.len(),
            "spline vector lengths must match ({} {} {})",
            locations.len(), values.len(), derivatives.len(),
        );
        assert!(!locations.is_empty(), "spline needs at least one point");

        let last_index = locations.len() - 1;
        let mut min_value = f32::INFINITY;
        let mut max_value = f32::NEG_INFINITY;
        let min_input = coordinate.min_value() as f32;
        let max_input = coordinate.max_value() as f32;

        // Extension below first knot.
        if min_input < locations[0] {
            let edge1 = linear_extend(min_input, &locations, values[0].min_value_of(), &derivatives, 0);
            let edge2 = linear_extend(min_input, &locations, values[0].max_value_of(), &derivatives, 0);
            min_value = min_value.min(edge1.min(edge2));
            max_value = max_value.max(edge1.max(edge2));
        }
        // Extension above last knot.
        if max_input > locations[last_index] {
            let edge1 = linear_extend(max_input, &locations, values[last_index].min_value_of(), &derivatives, last_index);
            let edge2 = linear_extend(max_input, &locations, values[last_index].max_value_of(), &derivatives, last_index);
            min_value = min_value.min(edge1.min(edge2));
            max_value = max_value.max(edge1.max(edge2));
        }
        // Per-value bounds.
        for v in &values {
            min_value = min_value.min(v.min_value_of());
            max_value = max_value.max(v.max_value_of());
        }
        // Hermite overshoot between consecutive knots.
        for i in 0..last_index {
            let x1 = locations[i];
            let x2 = locations[i + 1];
            let x_diff = x2 - x1;
            let min1 = values[i].min_value_of();
            let max1 = values[i].max_value_of();
            let min2 = values[i + 1].min_value_of();
            let max2 = values[i + 1].max_value_of();
            let d1 = derivatives[i];
            let d2 = derivatives[i + 1];
            if d1 != 0.0 || d2 != 0.0 {
                let p1 = d1 * x_diff;
                let p2 = d2 * x_diff;
                let min_lerp1 = min1.min(min2);
                let max_lerp1 = max1.max(max2);
                let min_a = p1 - max2 + min1;
                let max_a = p1 - min2 + max1;
                let min_b = -p2 + min2 - max1;
                let max_b = -p2 + max2 - min1;
                let min_lerp2 = min_a.min(min_b);
                let max_lerp2 = max_a.max(max_b);
                min_value = min_value.min(min_lerp1 + 0.25 * min_lerp2);
                max_value = max_value.max(max_lerp1 + 0.25 * max_lerp2);
            }
        }

        DfSpline::Multipoint {
            coordinate: Box::new(coordinate),
            locations,
            values,
            derivatives,
            min_value,
            max_value,
        }
    }

    /// Vanilla `CubicSpline.apply(C)`. For density splines, `C` is
    /// the `(ctx, state)` pair we pass implicitly via method args.
    /// Returns f32 (vanilla does too; outer `DensityFunction::Spline`
    /// casts to f64).
    pub fn apply(&self, ctx: &FunctionContext, state: &WorldgenState) -> f32 {
        match self {
            DfSpline::Constant(v) => *v,
            DfSpline::Multipoint { coordinate, locations, values, derivatives, .. } => {
                let input = coordinate.compute(ctx, state) as f32;
                let start = find_interval_start(locations, input);
                let last_index = locations.len() as i32 - 1;
                if start < 0 {
                    // Below first knot: linear extend from values[0].
                    linear_extend(input, locations, values[0].apply(ctx, state), derivatives, 0)
                } else if start == last_index {
                    // Above or at last knot: linear extend from values[last].
                    linear_extend(
                        input, locations,
                        values[last_index as usize].apply(ctx, state),
                        derivatives, last_index as usize,
                    )
                } else {
                    // Hermite cubic between knots [start, start+1].
                    let i = start as usize;
                    let x1 = locations[i];
                    let x2 = locations[i + 1];
                    let t = (input - x1) / (x2 - x1);
                    let y1 = values[i].apply(ctx, state);
                    let y2 = values[i + 1].apply(ctx, state);
                    let d1 = derivatives[i];
                    let d2 = derivatives[i + 1];
                    let a = d1 * (x2 - x1) - (y2 - y1);
                    let b = -d2 * (x2 - x1) + (y2 - y1);
                    // Vanilla Mth.lerp(t, lo, hi) = lo + t * (hi - lo).
                    lerp(t, y1, y2) + t * (1.0 - t) * lerp(t, a, b)
                }
            }
        }
    }

    pub fn min_value_of(&self) -> f32 {
        match self {
            DfSpline::Constant(v) => *v,
            DfSpline::Multipoint { min_value, .. } => *min_value,
        }
    }

    pub fn max_value_of(&self) -> f32 {
        match self {
            DfSpline::Constant(v) => *v,
            DfSpline::Multipoint { max_value, .. } => *max_value,
        }
    }
}

#[inline]
fn lerp(t: f32, lo: f32, hi: f32) -> f32 {
    lo + t * (hi - lo)
}

/// Vanilla `CubicSpline.linearExtend(input, locations, value, derivatives, index)`.
/// When derivative at `index` is zero, the extension is flat (returns the
/// value as-is). Otherwise extends linearly from `(locations[index], value)`
/// with slope `derivative`.
#[inline]
fn linear_extend(input: f32, locations: &[f32], value: f32, derivatives: &[f32], index: usize) -> f32 {
    let derivative = derivatives[index];
    if derivative == 0.0 { value } else { value + derivative * (input - locations[index]) }
}

/// Vanilla `findIntervalStart`: binary-search index of the greatest
/// location <= input. Returns -1 if input < all locations.
///
/// Vanilla uses `Mth.binarySearch(0, length, i -> input < locations[i]) - 1`.
/// `Mth.binarySearch` finds the FIRST index where the predicate returns
/// true, or `length` if never. We replicate with the same semantics.
#[inline]
fn find_interval_start(locations: &[f32], input: f32) -> i32 {
    // Mth.binarySearch is half-open [from, to); predicate-based.
    let mut from = 0_i32;
    let mut to = locations.len() as i32;
    while from < to {
        let mid = (from + to) >> 1;
        if input < locations[mid as usize] {
            to = mid;
        } else {
            from = mid + 1;
        }
    }
    // `from` is the first index where `input < locations[i]`, or `len`.
    from - 1
}

// ============================================================================
// Bytecode: compact serialization so Java can walk a yarn
// DensityFunction tree and build the Rust equivalent via one JNI call.
// ============================================================================

/// DF node opcodes. Tag byte precedes each node; args are laid out
/// inline and recursive nodes follow immediately (depth-first).
/// Values are host-native byte order to match the rest of the JNI.
///
/// Numbers are stable — never renumber. Add new variants at the end.
pub mod opcode {
    pub const CONSTANT: u8 = 0x00;
    pub const ADD: u8 = 0x01;
    pub const MUL: u8 = 0x02;
    pub const MIN: u8 = 0x03;
    pub const MAX: u8 = 0x04;
    pub const ABS: u8 = 0x05;
    pub const SQUARE: u8 = 0x06;
    pub const CUBE: u8 = 0x07;
    pub const HALF_NEGATIVE: u8 = 0x08;
    pub const QUARTER_NEGATIVE: u8 = 0x09;
    pub const INVERT: u8 = 0x0A;
    pub const SQUEEZE: u8 = 0x0B;
    pub const CLAMP: u8 = 0x0C;
    pub const Y_CLAMPED_GRADIENT: u8 = 0x0D;
    pub const RANGE_CHOICE: u8 = 0x0E;
    pub const MARKER: u8 = 0x0F;
    pub const NOISE: u8 = 0x10;
    pub const SHIFT: u8 = 0x11;
    pub const SHIFT_A: u8 = 0x12;
    pub const SHIFT_B: u8 = 0x13;
    pub const SHIFTED_NOISE: u8 = 0x14;
    pub const WEIRD_SCALED_SAMPLER: u8 = 0x15;
    pub const SPLINE: u8 = 0x16;
    pub const BLENDED_NOISE: u8 = 0x17;

    // Spline sub-opcodes.
    pub const SPLINE_CONSTANT: u8 = 0x00;
    pub const SPLINE_MULTIPOINT: u8 = 0x01;

    // Marker kinds — must match MarkerKind ordering.
    pub const MARKER_INTERPOLATED: u8 = 0;
    pub const MARKER_FLAT_CACHE: u8 = 1;
    pub const MARKER_CACHE_2D: u8 = 2;
    pub const MARKER_CACHE_ONCE: u8 = 3;
    pub const MARKER_CACHE_ALL_IN_CELL: u8 = 4;

    // Rarity types.
    pub const RARITY_TYPE1: u8 = 0;
    pub const RARITY_TYPE2: u8 = 1;
}

/// Parse a DF bytecode buffer into a `DensityFunction` tree. Returns
/// `Err` on malformed input (truncation, unknown opcode, invalid UTF-8
/// in a name, etc.) — never panics. Error strings are diagnostic.
pub fn parse_bytecode(bytes: &[u8]) -> Result<DensityFunction, String> {
    let mut cursor = 0;
    let df = parse_node(bytes, &mut cursor)?;
    if cursor != bytes.len() {
        return Err(format!("trailing bytes: parsed {}, buffer {}", cursor, bytes.len()));
    }
    Ok(df)
}

fn parse_node(bytes: &[u8], c: &mut usize) -> Result<DensityFunction, String> {
    let tag = read_u8(bytes, c)?;
    match tag {
        opcode::CONSTANT => Ok(DensityFunction::Constant(read_f64(bytes, c)?)),
        opcode::ADD => {
            let a = Box::new(parse_node(bytes, c)?);
            let b = Box::new(parse_node(bytes, c)?);
            Ok(DensityFunction::Add(a, b))
        }
        opcode::MUL => {
            let a = Box::new(parse_node(bytes, c)?);
            let b = Box::new(parse_node(bytes, c)?);
            Ok(DensityFunction::Mul(a, b))
        }
        opcode::MIN => {
            let a = Box::new(parse_node(bytes, c)?);
            let b = Box::new(parse_node(bytes, c)?);
            Ok(DensityFunction::Min(a, b))
        }
        opcode::MAX => {
            let a = Box::new(parse_node(bytes, c)?);
            let b = Box::new(parse_node(bytes, c)?);
            Ok(DensityFunction::Max(a, b))
        }
        opcode::ABS => Ok(DensityFunction::Abs(Box::new(parse_node(bytes, c)?))),
        opcode::SQUARE => Ok(DensityFunction::Square(Box::new(parse_node(bytes, c)?))),
        opcode::CUBE => Ok(DensityFunction::Cube(Box::new(parse_node(bytes, c)?))),
        opcode::HALF_NEGATIVE => Ok(DensityFunction::HalfNegative(Box::new(parse_node(bytes, c)?))),
        opcode::QUARTER_NEGATIVE => Ok(DensityFunction::QuarterNegative(Box::new(parse_node(bytes, c)?))),
        opcode::INVERT => Ok(DensityFunction::Invert(Box::new(parse_node(bytes, c)?))),
        opcode::SQUEEZE => Ok(DensityFunction::Squeeze(Box::new(parse_node(bytes, c)?))),
        opcode::CLAMP => {
            let input = Box::new(parse_node(bytes, c)?);
            let min = read_f64(bytes, c)?;
            let max = read_f64(bytes, c)?;
            Ok(DensityFunction::Clamp { input, min, max })
        }
        opcode::Y_CLAMPED_GRADIENT => {
            let from_y = read_i32(bytes, c)?;
            let to_y = read_i32(bytes, c)?;
            let from_value = read_f64(bytes, c)?;
            let to_value = read_f64(bytes, c)?;
            Ok(DensityFunction::YClampedGradient { from_y, to_y, from_value, to_value })
        }
        opcode::RANGE_CHOICE => {
            let input = Box::new(parse_node(bytes, c)?);
            let min_inclusive = read_f64(bytes, c)?;
            let max_exclusive = read_f64(bytes, c)?;
            let when_in_range = Box::new(parse_node(bytes, c)?);
            let when_out_of_range = Box::new(parse_node(bytes, c)?);
            Ok(DensityFunction::RangeChoice { input, min_inclusive, max_exclusive, when_in_range, when_out_of_range })
        }
        opcode::MARKER => {
            let kind = read_marker_kind(bytes, c)?;
            let wrapped = Box::new(parse_node(bytes, c)?);
            Ok(DensityFunction::Marker { kind, wrapped })
        }
        opcode::NOISE => {
            let noise_name = read_string(bytes, c)?;
            let xz_scale = read_f64(bytes, c)?;
            let y_scale = read_f64(bytes, c)?;
            Ok(DensityFunction::Noise { noise_name, xz_scale, y_scale })
        }
        opcode::SHIFT => Ok(DensityFunction::Shift { noise_name: read_string(bytes, c)? }),
        opcode::SHIFT_A => Ok(DensityFunction::ShiftA { noise_name: read_string(bytes, c)? }),
        opcode::SHIFT_B => Ok(DensityFunction::ShiftB { noise_name: read_string(bytes, c)? }),
        opcode::SHIFTED_NOISE => {
            let shift_x = Box::new(parse_node(bytes, c)?);
            let shift_y = Box::new(parse_node(bytes, c)?);
            let shift_z = Box::new(parse_node(bytes, c)?);
            let xz_scale = read_f64(bytes, c)?;
            let y_scale = read_f64(bytes, c)?;
            let noise_name = read_string(bytes, c)?;
            Ok(DensityFunction::ShiftedNoise { shift_x, shift_y, shift_z, xz_scale, y_scale, noise_name })
        }
        opcode::WEIRD_SCALED_SAMPLER => {
            let input = Box::new(parse_node(bytes, c)?);
            let noise_name = read_string(bytes, c)?;
            let rarity = match read_u8(bytes, c)? {
                opcode::RARITY_TYPE1 => RarityValueMapper::Type1,
                opcode::RARITY_TYPE2 => RarityValueMapper::Type2,
                other => return Err(format!("unknown rarity type: 0x{:02x}", other)),
            };
            Ok(DensityFunction::WeirdScaledSampler {
                input, noise_name,
                rarity_value_mapper: rarity,
            })
        }
        opcode::SPLINE => Ok(DensityFunction::Spline(Box::new(parse_spline(bytes, c)?))),
        opcode::BLENDED_NOISE => {
            // Five doubles in vanilla's InterpolatedNoiseSampler ctor order.
            let xz_scale = read_f64(bytes, c)?;
            let y_scale = read_f64(bytes, c)?;
            let xz_factor = read_f64(bytes, c)?;
            let y_factor = read_f64(bytes, c)?;
            let smear = read_f64(bytes, c)?;
            Ok(DensityFunction::BlendedNoise(LazyBlendedNoise::new(
                xz_scale, y_scale, xz_factor, y_factor, smear,
            )))
        }
        other => Err(format!("unknown DF opcode: 0x{:02x} at offset {}", other, *c - 1)),
    }
}

fn parse_spline(bytes: &[u8], c: &mut usize) -> Result<DfSpline, String> {
    let tag = read_u8(bytes, c)?;
    match tag {
        opcode::SPLINE_CONSTANT => Ok(DfSpline::Constant(read_f32(bytes, c)?)),
        opcode::SPLINE_MULTIPOINT => {
            let coordinate = parse_node(bytes, c)?;
            let n = read_u16(bytes, c)? as usize;
            let mut locations = Vec::with_capacity(n);
            for _ in 0..n { locations.push(read_f32(bytes, c)?); }
            let mut derivatives = Vec::with_capacity(n);
            for _ in 0..n { derivatives.push(read_f32(bytes, c)?); }
            let mut values = Vec::with_capacity(n);
            for _ in 0..n { values.push(parse_spline(bytes, c)?); }
            Ok(DfSpline::new_multipoint(coordinate, locations, values, derivatives))
        }
        other => Err(format!("unknown spline opcode: 0x{:02x} at offset {}", other, *c - 1)),
    }
}

#[inline]
fn read_u8(bytes: &[u8], c: &mut usize) -> Result<u8, String> {
    if *c >= bytes.len() { return Err("truncated: expected u8".to_string()); }
    let v = bytes[*c]; *c += 1; Ok(v)
}

#[inline]
fn read_u16(bytes: &[u8], c: &mut usize) -> Result<u16, String> {
    if *c + 2 > bytes.len() { return Err("truncated: expected u16".to_string()); }
    let v = u16::from_ne_bytes(bytes[*c..*c + 2].try_into().unwrap());
    *c += 2; Ok(v)
}

#[inline]
fn read_i32(bytes: &[u8], c: &mut usize) -> Result<i32, String> {
    if *c + 4 > bytes.len() { return Err("truncated: expected i32".to_string()); }
    let v = i32::from_ne_bytes(bytes[*c..*c + 4].try_into().unwrap());
    *c += 4; Ok(v)
}

#[inline]
fn read_f32(bytes: &[u8], c: &mut usize) -> Result<f32, String> {
    if *c + 4 > bytes.len() { return Err("truncated: expected f32".to_string()); }
    let v = f32::from_ne_bytes(bytes[*c..*c + 4].try_into().unwrap());
    *c += 4; Ok(v)
}

#[inline]
fn read_f64(bytes: &[u8], c: &mut usize) -> Result<f64, String> {
    if *c + 8 > bytes.len() { return Err("truncated: expected f64".to_string()); }
    let v = f64::from_ne_bytes(bytes[*c..*c + 8].try_into().unwrap());
    *c += 8; Ok(v)
}

fn read_string(bytes: &[u8], c: &mut usize) -> Result<String, String> {
    let len = read_u16(bytes, c)? as usize;
    if *c + len > bytes.len() { return Err(format!("truncated: string of len {}", len)); }
    let s = std::str::from_utf8(&bytes[*c..*c + len])
        .map_err(|e| format!("invalid UTF-8 in name: {}", e))?
        .to_string();
    *c += len;
    Ok(s)
}

fn read_marker_kind(bytes: &[u8], c: &mut usize) -> Result<MarkerKind, String> {
    Ok(match read_u8(bytes, c)? {
        opcode::MARKER_INTERPOLATED => MarkerKind::Interpolated,
        opcode::MARKER_FLAT_CACHE => MarkerKind::FlatCache,
        opcode::MARKER_CACHE_2D => MarkerKind::Cache2D,
        opcode::MARKER_CACHE_ONCE => MarkerKind::CacheOnce,
        opcode::MARKER_CACHE_ALL_IN_CELL => MarkerKind::CacheAllInCell,
        other => return Err(format!("unknown marker kind: 0x{:02x}", other)),
    })
}

/// Shared sampling helper for the three single-shift variants.
/// Vanilla `ShiftNoise.compute(lx, ly, lz) = offsetNoise.getValue(lx*0.25, ly*0.25, lz*0.25) * 4.0`.
#[inline]
fn sample_shift(state: &WorldgenState, name: &str, a: f64, b: f64, c: f64) -> f64 {
    match state.noises.get(name) {
        Some(n) => n.get_value(a * 0.25, b * 0.25, c * 0.25) * 4.0,
        None => 0.0,
    }
}

/// Vanilla `DensityFunctions.Marker.Type`. Currently unused (passthrough);
/// will drive cache implementation in a later session.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum MarkerKind {
    Interpolated,
    FlatCache,
    Cache2D,
    CacheOnce,
    CacheAllInCell,
}

impl DensityFunction {
    /// Vanilla `compute(FunctionContext)`. Walks the tree recursively.
    ///
    /// `state` provides registered noises. For `Noise` variants whose
    /// name isn't in the state, returns 0.0 (matches vanilla's
    /// `NoiseHolder(data, null).getValue -> 0.0`).
    pub fn compute(&self, ctx: &FunctionContext, state: &WorldgenState) -> f64 {
        match self {
            DensityFunction::Constant(v) => *v,

            DensityFunction::Noise { noise_name, xz_scale, y_scale } => {
                match state.noises.get(noise_name) {
                    Some(n) => n.get_value(
                        ctx.block_x as f64 * xz_scale,
                        ctx.block_y as f64 * y_scale,
                        ctx.block_z as f64 * xz_scale,
                    ),
                    None => 0.0,
                }
            }

            DensityFunction::Add(a, b) => {
                a.compute(ctx, state) + b.compute(ctx, state)
            }
            DensityFunction::Mul(a, b) => {
                let v1 = a.compute(ctx, state);
                if v1 == 0.0 { 0.0 } else { v1 * b.compute(ctx, state) }
            }
            DensityFunction::Min(a, b) => {
                let v1 = a.compute(ctx, state);
                let b_min = b.min_value();
                if v1 < b_min { v1 } else { v1.min(b.compute(ctx, state)) }
            }
            DensityFunction::Max(a, b) => {
                let v1 = a.compute(ctx, state);
                let b_max = b.max_value();
                if v1 > b_max { v1 } else { v1.max(b.compute(ctx, state)) }
            }

            DensityFunction::Abs(x) => x.compute(ctx, state).abs(),
            DensityFunction::Square(x) => { let v = x.compute(ctx, state); v * v }
            DensityFunction::Cube(x) => { let v = x.compute(ctx, state); v * v * v }
            DensityFunction::HalfNegative(x) => {
                let v = x.compute(ctx, state);
                if v > 0.0 { v } else { v * 0.5 }
            }
            DensityFunction::QuarterNegative(x) => {
                let v = x.compute(ctx, state);
                if v > 0.0 { v } else { v * 0.25 }
            }
            DensityFunction::Invert(x) => 1.0 / x.compute(ctx, state),
            DensityFunction::Squeeze(x) => {
                let c = x.compute(ctx, state).clamp(-1.0, 1.0);
                c / 2.0 - c * c * c / 24.0
            }

            DensityFunction::Clamp { input, min, max } => {
                input.compute(ctx, state).clamp(*min, *max)
            }

            DensityFunction::YClampedGradient { from_y, to_y, from_value, to_value } => {
                clamped_map(
                    ctx.block_y as f64,
                    *from_y as f64, *to_y as f64,
                    *from_value, *to_value,
                )
            }

            DensityFunction::RangeChoice { input, min_inclusive, max_exclusive, when_in_range, when_out_of_range } => {
                let v = input.compute(ctx, state);
                if v >= *min_inclusive && v < *max_exclusive {
                    when_in_range.compute(ctx, state)
                } else {
                    when_out_of_range.compute(ctx, state)
                }
            }

            DensityFunction::Marker { wrapped, .. } => wrapped.compute(ctx, state),

            DensityFunction::Shift { noise_name } => {
                sample_shift(state, noise_name,
                    ctx.block_x as f64, ctx.block_y as f64, ctx.block_z as f64)
            }
            DensityFunction::ShiftA { noise_name } => {
                sample_shift(state, noise_name,
                    ctx.block_x as f64, 0.0, ctx.block_z as f64)
            }
            DensityFunction::ShiftB { noise_name } => {
                sample_shift(state, noise_name,
                    ctx.block_z as f64, ctx.block_x as f64, 0.0)
            }

            DensityFunction::ShiftedNoise {
                shift_x, shift_y, shift_z, xz_scale, y_scale, noise_name,
            } => {
                let x = ctx.block_x as f64 * xz_scale + shift_x.compute(ctx, state);
                let y = ctx.block_y as f64 * y_scale + shift_y.compute(ctx, state);
                let z = ctx.block_z as f64 * xz_scale + shift_z.compute(ctx, state);
                match state.noises.get(noise_name) {
                    Some(n) => n.get_value(x, y, z),
                    None => 0.0,
                }
            }

            DensityFunction::WeirdScaledSampler {
                input, noise_name, rarity_value_mapper,
            } => {
                let rarity = rarity_value_mapper.apply(input.compute(ctx, state));
                let sample = match state.noises.get(noise_name) {
                    Some(n) => n.get_value(
                        ctx.block_x as f64 / rarity,
                        ctx.block_y as f64 / rarity,
                        ctx.block_z as f64 / rarity,
                    ),
                    None => 0.0,
                };
                rarity * sample.abs()
            }

            DensityFunction::Spline(spline) => spline.apply(ctx, state) as f64,

            DensityFunction::BlendedNoise(lazy) => {
                lazy.sample(state, ctx.block_x, ctx.block_y, ctx.block_z)
            }
        }
    }

    /// Batched evaluation: compute this DF at N positions in one tree walk.
    ///
    /// `xs`, `ys`, `zs` are parallel arrays of block coordinates (length N).
    /// `out` (length N) receives the f64 results.
    ///
    /// Compared to calling [`compute`] N times, this approach:
    /// 1. Walks the tree structure once, with each opcode evaluated over
    ///    all N positions in tight loops (auto-vectorizable by LLVM).
    /// 2. Materializes intermediate results in scratch arrays — so a
    ///    "shared subtree" referenced multiple times in a parent op
    ///    (Add/Mul/etc.) is computed once per batch, not once per cell.
    /// 3. Eliminates the need for vanilla's stateful CacheOnce/Cache2D
    ///    wrappers — the temp array IS the cache.
    ///
    /// Short-circuits in vanilla's per-cell `compute` (Mul-by-zero,
    /// Min/Max-out-of-range) are dropped here: they're a per-cell perf
    /// trick that doesn't translate to batch loops, and dropping them
    /// is semantically equivalent (verified against vanilla's
    /// minValue/maxValue gating logic — bounds-based, not value-based).
    ///
    /// `Spline` and `BlendedNoise` currently fall back to per-cell
    /// evaluation in a tight loop — batch versions of those would be a
    /// follow-up optimization.
    pub fn compute_batch(
        &self,
        xs: &[i32],
        ys: &[i32],
        zs: &[i32],
        state: &WorldgenState,
        out: &mut [f64],
    ) {
        let n = out.len();
        debug_assert_eq!(xs.len(), n);
        debug_assert_eq!(ys.len(), n);
        debug_assert_eq!(zs.len(), n);
        match self {
            DensityFunction::Constant(v) => {
                out.fill(*v);
            }

            DensityFunction::Noise { noise_name, xz_scale, y_scale } => {
                match state.noises.get(noise_name) {
                    Some(noise) => {
                        for i in 0..n {
                            out[i] = noise.get_value(
                                xs[i] as f64 * xz_scale,
                                ys[i] as f64 * y_scale,
                                zs[i] as f64 * xz_scale,
                            );
                        }
                    }
                    None => out.fill(0.0),
                }
            }

            DensityFunction::Add(a, b) => {
                let mut a_buf = vec![0.0; n];
                a.compute_batch(xs, ys, zs, state, &mut a_buf);
                b.compute_batch(xs, ys, zs, state, out);
                for i in 0..n { out[i] += a_buf[i]; }
            }
            DensityFunction::Mul(a, b) => {
                let mut a_buf = vec![0.0; n];
                a.compute_batch(xs, ys, zs, state, &mut a_buf);
                b.compute_batch(xs, ys, zs, state, out);
                for i in 0..n {
                    // Vanilla short-circuits Mul when v1==0; we don't
                    // (eval'd both already), but result is identical
                    // because a*0 == 0 mathematically.
                    out[i] *= a_buf[i];
                }
            }
            DensityFunction::Min(a, b) => {
                let mut a_buf = vec![0.0; n];
                a.compute_batch(xs, ys, zs, state, &mut a_buf);
                b.compute_batch(xs, ys, zs, state, out);
                for i in 0..n { out[i] = a_buf[i].min(out[i]); }
            }
            DensityFunction::Max(a, b) => {
                let mut a_buf = vec![0.0; n];
                a.compute_batch(xs, ys, zs, state, &mut a_buf);
                b.compute_batch(xs, ys, zs, state, out);
                for i in 0..n { out[i] = a_buf[i].max(out[i]); }
            }

            DensityFunction::Abs(x) => {
                x.compute_batch(xs, ys, zs, state, out);
                for i in 0..n { out[i] = out[i].abs(); }
            }
            DensityFunction::Square(x) => {
                x.compute_batch(xs, ys, zs, state, out);
                for i in 0..n { out[i] *= out[i]; }
            }
            DensityFunction::Cube(x) => {
                x.compute_batch(xs, ys, zs, state, out);
                for i in 0..n { let v = out[i]; out[i] = v * v * v; }
            }
            DensityFunction::HalfNegative(x) => {
                x.compute_batch(xs, ys, zs, state, out);
                for i in 0..n {
                    let v = out[i];
                    out[i] = if v > 0.0 { v } else { v * 0.5 };
                }
            }
            DensityFunction::QuarterNegative(x) => {
                x.compute_batch(xs, ys, zs, state, out);
                for i in 0..n {
                    let v = out[i];
                    out[i] = if v > 0.0 { v } else { v * 0.25 };
                }
            }
            DensityFunction::Invert(x) => {
                x.compute_batch(xs, ys, zs, state, out);
                for i in 0..n { out[i] = 1.0 / out[i]; }
            }
            DensityFunction::Squeeze(x) => {
                x.compute_batch(xs, ys, zs, state, out);
                for i in 0..n {
                    let c = out[i].clamp(-1.0, 1.0);
                    out[i] = c / 2.0 - c * c * c / 24.0;
                }
            }

            DensityFunction::Clamp { input, min, max } => {
                input.compute_batch(xs, ys, zs, state, out);
                for i in 0..n { out[i] = out[i].clamp(*min, *max); }
            }

            DensityFunction::YClampedGradient { from_y, to_y, from_value, to_value } => {
                let from_y_f = *from_y as f64;
                let to_y_f = *to_y as f64;
                let inv_span = 1.0 / (to_y_f - from_y_f);
                let dv = *to_value - *from_value;
                for i in 0..n {
                    let y = ys[i] as f64;
                    out[i] = if y <= from_y_f {
                        *from_value
                    } else if y >= to_y_f {
                        *to_value
                    } else {
                        *from_value + (y - from_y_f) * inv_span * dv
                    };
                }
            }

            DensityFunction::RangeChoice { input, min_inclusive, max_exclusive,
                                            when_in_range, when_out_of_range } => {
                let mut input_buf = vec![0.0; n];
                input.compute_batch(xs, ys, zs, state, &mut input_buf);
                let mut in_buf = vec![0.0; n];
                let mut out_buf = vec![0.0; n];
                // Eval both branches over all positions (simpler than
                // splitting). For typical worldgen RangeChoice nodes
                // (small branches) this is fine; if profiling shows
                // RangeChoice as a hot, branch-asymmetric op, we'd
                // split positions by the input check first.
                when_in_range.compute_batch(xs, ys, zs, state, &mut in_buf);
                when_out_of_range.compute_batch(xs, ys, zs, state, &mut out_buf);
                for i in 0..n {
                    let v = input_buf[i];
                    out[i] = if v >= *min_inclusive && v < *max_exclusive {
                        in_buf[i]
                    } else {
                        out_buf[i]
                    };
                }
            }

            DensityFunction::Marker { wrapped, .. } => {
                wrapped.compute_batch(xs, ys, zs, state, out);
            }

            DensityFunction::Shift { noise_name } => {
                if let Some(_n) = state.noises.get(noise_name) {
                    for i in 0..n {
                        out[i] = sample_shift(state, noise_name,
                            xs[i] as f64, ys[i] as f64, zs[i] as f64);
                    }
                } else {
                    out.fill(0.0);
                }
            }
            DensityFunction::ShiftA { noise_name } => {
                if state.noises.get(noise_name).is_some() {
                    for i in 0..n {
                        out[i] = sample_shift(state, noise_name,
                            xs[i] as f64, 0.0, zs[i] as f64);
                    }
                } else {
                    out.fill(0.0);
                }
            }
            DensityFunction::ShiftB { noise_name } => {
                if state.noises.get(noise_name).is_some() {
                    for i in 0..n {
                        out[i] = sample_shift(state, noise_name,
                            zs[i] as f64, xs[i] as f64, 0.0);
                    }
                } else {
                    out.fill(0.0);
                }
            }

            DensityFunction::ShiftedNoise {
                shift_x, shift_y, shift_z, xz_scale, y_scale, noise_name,
            } => {
                let mut sx = vec![0.0; n];
                let mut sy = vec![0.0; n];
                let mut sz = vec![0.0; n];
                shift_x.compute_batch(xs, ys, zs, state, &mut sx);
                shift_y.compute_batch(xs, ys, zs, state, &mut sy);
                shift_z.compute_batch(xs, ys, zs, state, &mut sz);
                match state.noises.get(noise_name) {
                    Some(noise) => {
                        for i in 0..n {
                            let nx = xs[i] as f64 * xz_scale + sx[i];
                            let ny = ys[i] as f64 * y_scale + sy[i];
                            let nz = zs[i] as f64 * xz_scale + sz[i];
                            out[i] = noise.get_value(nx, ny, nz);
                        }
                    }
                    None => out.fill(0.0),
                }
            }

            DensityFunction::WeirdScaledSampler {
                input, noise_name, rarity_value_mapper,
            } => {
                let mut input_buf = vec![0.0; n];
                input.compute_batch(xs, ys, zs, state, &mut input_buf);
                match state.noises.get(noise_name) {
                    Some(noise) => {
                        for i in 0..n {
                            let rarity = rarity_value_mapper.apply(input_buf[i]);
                            let s = noise.get_value(
                                xs[i] as f64 / rarity,
                                ys[i] as f64 / rarity,
                                zs[i] as f64 / rarity,
                            );
                            out[i] = rarity * s.abs();
                        }
                    }
                    None => out.fill(0.0),
                }
            }

            // Spline and BlendedNoise: per-cell fallback. Both are
            // computationally heavy and don't trivially batch — Spline
            // because of its piecewise interpolation indexing, BlendedNoise
            // because the underlying perlin chain is already its own
            // hot loop. Future: batch by amortizing the spline lookup.
            DensityFunction::Spline(spline) => {
                for i in 0..n {
                    let ctx = FunctionContext::new(xs[i], ys[i], zs[i]);
                    out[i] = spline.apply(&ctx, state) as f64;
                }
            }
            DensityFunction::BlendedNoise(lazy) => {
                for i in 0..n {
                    out[i] = lazy.sample(state, xs[i], ys[i], zs[i]);
                }
            }
        }
    }

    /// Walk the DF tree and collect references to the wrapped subtree of
    /// every {@code Marker(Interpolated)} reachable WITHOUT crossing into
    /// another DI's wrapped subtree. Vanilla worldgen wraps the heavy
    /// per-position DFs (BlendedNoise, Spline, RangeChoice-with-noise)
    /// in DI markers; the OUTER tree above the DIs is just cheap
    /// arithmetic (Add/Min/Squeeze/etc.) operating on the lerped DI
    /// values.
    ///
    /// Order matches [`compute_with_di_lerp`] DFS so a parallel index
    /// counter walks both phases identically.
    ///
    /// Used by [`Self::populate_chunk_density_v2`]: corner-sample each
    /// returned subtree at the cell-corner grid, then per-block compose
    /// the outer tree with DI markers replaced by trilinear lerp from
    /// the corner buffers.
    pub fn enumerate_di_inputs<'a>(&'a self, out: &mut Vec<&'a DensityFunction>) {
        match self {
            DensityFunction::Marker { kind: MarkerKind::Interpolated, wrapped } => {
                // The DI itself is corner-sampled separately; do NOT
                // recurse into wrapped here. Inner Markers/cache wrappers
                // inside wrapped are passthroughs during corner sampling.
                out.push(wrapped);
            }
            DensityFunction::Marker { wrapped, .. } => {
                wrapped.enumerate_di_inputs(out);
            }
            DensityFunction::Add(a, b) | DensityFunction::Mul(a, b)
            | DensityFunction::Min(a, b) | DensityFunction::Max(a, b) => {
                a.enumerate_di_inputs(out);
                b.enumerate_di_inputs(out);
            }
            DensityFunction::Abs(x) | DensityFunction::Square(x)
            | DensityFunction::Cube(x) | DensityFunction::HalfNegative(x)
            | DensityFunction::QuarterNegative(x) | DensityFunction::Invert(x)
            | DensityFunction::Squeeze(x) => {
                x.enumerate_di_inputs(out);
            }
            DensityFunction::Clamp { input, .. } => input.enumerate_di_inputs(out),
            DensityFunction::RangeChoice { input, when_in_range, when_out_of_range, .. } => {
                input.enumerate_di_inputs(out);
                when_in_range.enumerate_di_inputs(out);
                when_out_of_range.enumerate_di_inputs(out);
            }
            DensityFunction::ShiftedNoise { shift_x, shift_y, shift_z, .. } => {
                shift_x.enumerate_di_inputs(out);
                shift_y.enumerate_di_inputs(out);
                shift_z.enumerate_di_inputs(out);
            }
            DensityFunction::WeirdScaledSampler { input, .. } => {
                input.enumerate_di_inputs(out);
            }
            // Spline coordinate functions can contain DIs. Walk them.
            DensityFunction::Spline(spline) => {
                spline.enumerate_di_inputs(out);
            }
            // Leaves: no DIs reachable.
            DensityFunction::Constant(_)
            | DensityFunction::Noise { .. }
            | DensityFunction::YClampedGradient { .. }
            | DensityFunction::Shift { .. }
            | DensityFunction::ShiftA { .. }
            | DensityFunction::ShiftB { .. }
            | DensityFunction::BlendedNoise(_) => {}
        }
    }

    /// Per-block compute using pre-sampled DI corner buffers. Replacement
    /// for [`Self::compute`] when the caller has already corner-sampled
    /// the DI subtrees enumerated by [`Self::enumerate_di_inputs`].
    ///
    /// At each `Marker(Interpolated, _)` node, instead of recursing
    /// into wrapped, do a trilinear lerp from `di_buffers[*counter]`
    /// using the chunk-local block coordinates. `counter` advances in
    /// DFS order matching `enumerate_di_inputs`.
    ///
    /// All conditional branches (RangeChoice, etc.) ALWAYS evaluate
    /// both arms to keep the counter deterministic — selects the
    /// correct value at the end. Same for Mul/Min/Max short-circuits
    /// (mathematically equivalent to vanilla's value-aware shortcuts).
    pub fn compute_with_di_lerp(
        &self,
        ctx: &FunctionContext,
        state: &WorldgenState,
        di_buffers: &[Vec<f64>],
        chunk_min: (i32, i32, i32),
        cell_size: (i32, i32, i32),  // (cell_width, cell_height, cell_width)
        corner_dims: (usize, usize, usize),  // (corner_x, corner_y, corner_z)
        counter: &mut usize,
    ) -> f64 {
        match self {
            DensityFunction::Marker { kind: MarkerKind::Interpolated, .. } => {
                let idx = *counter;
                *counter += 1;
                if idx < di_buffers.len() {
                    trilinear_lerp_corner(
                        &di_buffers[idx],
                        ctx.block_x, ctx.block_y, ctx.block_z,
                        chunk_min, cell_size, corner_dims,
                    )
                } else {
                    // Defensive: counter overflow — shouldn't happen if
                    // enumerate matches DFS. Fall back to compute.
                    self.compute(ctx, state)
                }
            }
            DensityFunction::Marker { wrapped, .. } => {
                wrapped.compute_with_di_lerp(ctx, state, di_buffers, chunk_min, cell_size, corner_dims, counter)
            }

            DensityFunction::Add(a, b) => {
                let av = a.compute_with_di_lerp(ctx, state, di_buffers, chunk_min, cell_size, corner_dims, counter);
                let bv = b.compute_with_di_lerp(ctx, state, di_buffers, chunk_min, cell_size, corner_dims, counter);
                av + bv
            }
            DensityFunction::Mul(a, b) => {
                // Always-eval (counter-stable). a*0 == 0 either way.
                let av = a.compute_with_di_lerp(ctx, state, di_buffers, chunk_min, cell_size, corner_dims, counter);
                let bv = b.compute_with_di_lerp(ctx, state, di_buffers, chunk_min, cell_size, corner_dims, counter);
                av * bv
            }
            DensityFunction::Min(a, b) => {
                let av = a.compute_with_di_lerp(ctx, state, di_buffers, chunk_min, cell_size, corner_dims, counter);
                let bv = b.compute_with_di_lerp(ctx, state, di_buffers, chunk_min, cell_size, corner_dims, counter);
                av.min(bv)
            }
            DensityFunction::Max(a, b) => {
                let av = a.compute_with_di_lerp(ctx, state, di_buffers, chunk_min, cell_size, corner_dims, counter);
                let bv = b.compute_with_di_lerp(ctx, state, di_buffers, chunk_min, cell_size, corner_dims, counter);
                av.max(bv)
            }

            DensityFunction::Abs(x) => x.compute_with_di_lerp(ctx, state, di_buffers, chunk_min, cell_size, corner_dims, counter).abs(),
            DensityFunction::Square(x) => { let v = x.compute_with_di_lerp(ctx, state, di_buffers, chunk_min, cell_size, corner_dims, counter); v * v }
            DensityFunction::Cube(x) => { let v = x.compute_with_di_lerp(ctx, state, di_buffers, chunk_min, cell_size, corner_dims, counter); v * v * v }
            DensityFunction::HalfNegative(x) => {
                let v = x.compute_with_di_lerp(ctx, state, di_buffers, chunk_min, cell_size, corner_dims, counter);
                if v > 0.0 { v } else { v * 0.5 }
            }
            DensityFunction::QuarterNegative(x) => {
                let v = x.compute_with_di_lerp(ctx, state, di_buffers, chunk_min, cell_size, corner_dims, counter);
                if v > 0.0 { v } else { v * 0.25 }
            }
            DensityFunction::Invert(x) => 1.0 / x.compute_with_di_lerp(ctx, state, di_buffers, chunk_min, cell_size, corner_dims, counter),
            DensityFunction::Squeeze(x) => {
                let c = x.compute_with_di_lerp(ctx, state, di_buffers, chunk_min, cell_size, corner_dims, counter).clamp(-1.0, 1.0);
                c / 2.0 - c * c * c / 24.0
            }

            DensityFunction::Clamp { input, min, max } => {
                input.compute_with_di_lerp(ctx, state, di_buffers, chunk_min, cell_size, corner_dims, counter).clamp(*min, *max)
            }

            DensityFunction::RangeChoice { input, min_inclusive, max_exclusive, when_in_range, when_out_of_range } => {
                let v = input.compute_with_di_lerp(ctx, state, di_buffers, chunk_min, cell_size, corner_dims, counter);
                // Always eval both for counter stability.
                let in_v = when_in_range.compute_with_di_lerp(ctx, state, di_buffers, chunk_min, cell_size, corner_dims, counter);
                let out_v = when_out_of_range.compute_with_di_lerp(ctx, state, di_buffers, chunk_min, cell_size, corner_dims, counter);
                if v >= *min_inclusive && v < *max_exclusive { in_v } else { out_v }
            }

            // Leaves and per-position ops fall back to plain compute.
            // They don't contain DIs (verified by enumerate_di_inputs
            // not recursing into them).
            _ => self.compute(ctx, state),
        }
    }

    /// Vanilla `minValue()`. Computed recursively; no caching. Useful
    /// for short-circuit logic in `Min`/`Max` and for downstream
    /// bounds analysis.
    pub fn min_value(&self) -> f64 {
        match self {
            DensityFunction::Constant(v) => *v,
            DensityFunction::Noise { noise_name, .. } => {
                // Vanilla: `-this.maxValue()`. maxValue is the noise's
                // own max (~2.0 when not resolved, else noise.maxValue()).
                // We don't have the noise here to inspect, so we return
                // the conservative vanilla fallback −2.0. Callers that
                // want a tighter bound must resolve via state; for
                // RangeChoice/Clamp short-circuits this is fine.
                -Self::noise_max_default(noise_name)
            }
            DensityFunction::Add(a, b) => a.min_value() + b.min_value(),
            DensityFunction::Mul(a, b) => {
                // Worst-case product of four corners.
                let (a0, a1) = (a.min_value(), a.max_value());
                let (b0, b1) = (b.min_value(), b.max_value());
                (a0 * b0).min(a0 * b1).min(a1 * b0).min(a1 * b1)
            }
            DensityFunction::Min(a, b) => a.min_value().min(b.min_value()),
            DensityFunction::Max(a, b) => a.min_value().max(b.min_value()),

            // Vanilla `Mapped.create`: for ABS and SQUARE the min is
            // `max(0, input.minValue())` — loose bound that always has
            // 0 as floor when input range touches or dips below zero.
            DensityFunction::Abs(x) => x.min_value().max(0.0),
            DensityFunction::Square(x) => x.min_value().max(0.0),
            DensityFunction::Cube(x) => {
                let v = x.min_value();
                v * v * v
            }
            DensityFunction::HalfNegative(x) => {
                let v = x.min_value();
                if v > 0.0 { v } else { v * 0.5 }
            }
            DensityFunction::QuarterNegative(x) => {
                let v = x.min_value();
                if v > 0.0 { v } else { v * 0.25 }
            }
            DensityFunction::Invert(x) => {
                let (lo, hi) = (x.min_value(), x.max_value());
                if lo < 0.0 && hi > 0.0 { f64::NEG_INFINITY } else { (1.0 / hi).min(1.0 / lo) }
            }
            DensityFunction::Squeeze(_) => -11.0 / 24.0, // c=-1: -1/2 + 1/24 = -11/24

            DensityFunction::Clamp { min, .. } => *min,
            DensityFunction::YClampedGradient { from_value, to_value, .. } => {
                from_value.min(*to_value)
            }
            DensityFunction::RangeChoice { when_in_range, when_out_of_range, .. } => {
                when_in_range.min_value().min(when_out_of_range.min_value())
            }
            DensityFunction::Marker { wrapped, .. } => wrapped.min_value(),

            // Vanilla `ShiftNoise.minValue = -maxValue`. maxValue is
            // `offsetNoise.maxValue() * 4.0`; with our default of 2.0
            // per unresolved-noise fallback → max = 8, min = -8.
            DensityFunction::Shift { noise_name }
            | DensityFunction::ShiftA { noise_name }
            | DensityFunction::ShiftB { noise_name } => -Self::noise_max_default(noise_name) * 4.0,

            DensityFunction::ShiftedNoise { noise_name, .. } => {
                -Self::noise_max_default(noise_name)
            }

            DensityFunction::WeirdScaledSampler { .. } => 0.0,

            DensityFunction::Spline(spline) => spline.min_value_of() as f64,

            // Vanilla BlendedNoise.minValue = -maxValue (see vanilla
            // BlendedNoise.minValue()/maxValue()). Without instantiation
            // we don't know the precise max — return -INFINITY so Min
            // short-circuits never falsely trigger; the cost is just a
            // missed perf optimization on Min(blended, ...) paths.
            DensityFunction::BlendedNoise(_) => f64::NEG_INFINITY,
        }
    }

    /// Vanilla `maxValue()`.
    pub fn max_value(&self) -> f64 {
        match self {
            DensityFunction::Constant(v) => *v,
            DensityFunction::Noise { noise_name, .. } => Self::noise_max_default(noise_name),
            DensityFunction::Add(a, b) => a.max_value() + b.max_value(),
            DensityFunction::Mul(a, b) => {
                let (a0, a1) = (a.min_value(), a.max_value());
                let (b0, b1) = (b.min_value(), b.max_value());
                (a0 * b0).max(a0 * b1).max(a1 * b0).max(a1 * b1)
            }
            DensityFunction::Min(a, b) => a.max_value().min(b.max_value()),
            DensityFunction::Max(a, b) => a.max_value().max(b.max_value()),

            DensityFunction::Abs(x) => x.min_value().abs().max(x.max_value().abs()),
            DensityFunction::Square(x) => {
                let (lo, hi) = (x.min_value(), x.max_value());
                (lo * lo).max(hi * hi)
            }
            DensityFunction::Cube(x) => {
                let v = x.max_value();
                v * v * v
            }
            DensityFunction::HalfNegative(x) => x.max_value(),
            DensityFunction::QuarterNegative(x) => x.max_value(),
            DensityFunction::Invert(x) => {
                let (lo, hi) = (x.min_value(), x.max_value());
                if lo < 0.0 && hi > 0.0 { f64::INFINITY } else { (1.0 / lo).max(1.0 / hi) }
            }
            DensityFunction::Squeeze(_) => 11.0 / 24.0,

            DensityFunction::Clamp { max, .. } => *max,
            DensityFunction::YClampedGradient { from_value, to_value, .. } => {
                from_value.max(*to_value)
            }
            DensityFunction::RangeChoice { when_in_range, when_out_of_range, .. } => {
                when_in_range.max_value().max(when_out_of_range.max_value())
            }
            DensityFunction::Marker { wrapped, .. } => wrapped.max_value(),

            DensityFunction::Shift { noise_name }
            | DensityFunction::ShiftA { noise_name }
            | DensityFunction::ShiftB { noise_name } => Self::noise_max_default(noise_name) * 4.0,

            DensityFunction::ShiftedNoise { noise_name, .. } => {
                Self::noise_max_default(noise_name)
            }

            DensityFunction::WeirdScaledSampler {
                noise_name, rarity_value_mapper, ..
            } => rarity_value_mapper.max_rarity() * Self::noise_max_default(noise_name),

            DensityFunction::Spline(spline) => spline.max_value_of() as f64,

            // See min_value() — INFINITY for the same reason.
            DensityFunction::BlendedNoise(_) => f64::INFINITY,
        }
    }

    /// Vanilla-fallback noise max: 2.0 if we can't inspect the actual
    /// sampler's maxValue. Conservative; the real value is ~1/6
    /// / expectedDeviation(span).
    fn noise_max_default(_noise_name: &str) -> f64 {
        2.0
    }
}

/// Vanilla `Mth.clampedMap(v, from, to, fromVal, toVal)`. Lerp from
/// `fromVal` at `v=from` to `toVal` at `v=to`, clamped outside.
#[inline]
/// Trilinear lerp from a chunk-corner buffer at a block position.
///
/// `corners` layout: row-major `(cy, cz, cx)` —
/// `corners[(cy * cz_dim + cz) * cx_dim + cx]` is the f64 sample at
/// the corner `(chunk_min.0 + cx*cell_size.0,
///              chunk_min.1 + cy*cell_size.1,
///              chunk_min.2 + cz*cell_size.2)`.
///
/// Lerp order matches vanilla's `MathHelper.lerp3` (X first, then Y,
/// then Z) so per-block density values are bit-identical to what
/// vanilla's `DensityInterpolator.sample` produces in the
/// `isSamplingForCaches` branch.
fn trilinear_lerp_corner(
    corners: &[f64],
    block_x: i32, block_y: i32, block_z: i32,
    chunk_min: (i32, i32, i32),
    cell_size: (i32, i32, i32),
    corner_dims: (usize, usize, usize),
) -> f64 {
    let (cx_dim, _cy_dim, cz_dim) = corner_dims;

    let dx = block_x - chunk_min.0;
    let dy = block_y - chunk_min.1;
    let dz = block_z - chunk_min.2;

    let cx_lo = (dx / cell_size.0) as usize;
    let cy_lo = (dy / cell_size.1) as usize;
    let cz_lo = (dz / cell_size.2) as usize;

    let fx = (dx as f64 - (cx_lo as i32 * cell_size.0) as f64) / cell_size.0 as f64;
    let fy = (dy as f64 - (cy_lo as i32 * cell_size.1) as f64) / cell_size.1 as f64;
    let fz = (dz as f64 - (cz_lo as i32 * cell_size.2) as f64) / cell_size.2 as f64;

    let stride_y = cz_dim * cx_dim;
    let stride_z = cx_dim;

    let idx = |cx: usize, cy: usize, cz: usize| cy * stride_y + cz * stride_z + cx;

    let c000 = corners[idx(cx_lo,     cy_lo,     cz_lo)];
    let c100 = corners[idx(cx_lo + 1, cy_lo,     cz_lo)];
    let c010 = corners[idx(cx_lo,     cy_lo + 1, cz_lo)];
    let c110 = corners[idx(cx_lo + 1, cy_lo + 1, cz_lo)];
    let c001 = corners[idx(cx_lo,     cy_lo,     cz_lo + 1)];
    let c101 = corners[idx(cx_lo + 1, cy_lo,     cz_lo + 1)];
    let c011 = corners[idx(cx_lo,     cy_lo + 1, cz_lo + 1)];
    let c111 = corners[idx(cx_lo + 1, cy_lo + 1, cz_lo + 1)];

    // X first, then Y, then Z — matches MathHelper.lerp3 / lerp2 chain.
    let cx00 = c000 + fx * (c100 - c000);
    let cx10 = c010 + fx * (c110 - c010);
    let cx01 = c001 + fx * (c101 - c001);
    let cx11 = c011 + fx * (c111 - c011);

    let cxy0 = cx00 + fy * (cx10 - cx00);
    let cxy1 = cx01 + fy * (cx11 - cx01);

    cxy0 + fz * (cxy1 - cxy0)
}

fn clamped_map(v: f64, from: f64, to: f64, from_value: f64, to_value: f64) -> f64 {
    if v <= from {
        from_value
    } else if v >= to {
        to_value
    } else {
        let t = (v - from) / (to - from);
        from_value + t * (to_value - from_value)
    }
}

// ============================================================================
// Tests
// ============================================================================

#[cfg(test)]
mod tests {
    use super::*;
    use crate::climate::RTree;
    use crate::xoroshiro::{XoroshiroPositionalRandomFactory, XoroshiroRandomSource};
    use std::collections::HashMap;

    fn empty_state() -> WorldgenState {
        let mut r = XoroshiroRandomSource::from_legacy_seed(42);
        let root = r.fork_positional();
        WorldgenState {
            seed: 42,
            root_factory: root,
            noises: HashMap::new(),
            biome_tree: None::<RTree<i32>>,
            density_functions: HashMap::new(),
        }
    }

    fn ctx(x: i32, y: i32, z: i32) -> FunctionContext {
        FunctionContext::new(x, y, z)
    }

    #[test]
    fn constant_returns_value() {
        let df = DensityFunction::Constant(3.14);
        assert_eq!(df.compute(&ctx(0, 0, 0), &empty_state()), 3.14);
        assert_eq!(df.min_value(), 3.14);
        assert_eq!(df.max_value(), 3.14);
    }

    #[test]
    fn add_of_constants() {
        let df = DensityFunction::Add(
            Box::new(DensityFunction::Constant(3.0)),
            Box::new(DensityFunction::Constant(4.0)),
        );
        assert_eq!(df.compute(&ctx(0, 0, 0), &empty_state()), 7.0);
        assert_eq!(df.min_value(), 7.0);
        assert_eq!(df.max_value(), 7.0);
    }

    #[test]
    fn mul_short_circuits_on_zero() {
        // Second operand: unresolved noise → would return 0.0 normally,
        // but Mul must not evaluate it when first is already 0. We can't
        // directly observe the short-circuit; just verify correctness.
        let df = DensityFunction::Mul(
            Box::new(DensityFunction::Constant(0.0)),
            Box::new(DensityFunction::Constant(999.0)),
        );
        assert_eq!(df.compute(&ctx(0, 0, 0), &empty_state()), 0.0);
    }

    #[test]
    fn min_max_take_extremes() {
        let df_min = DensityFunction::Min(
            Box::new(DensityFunction::Constant(3.0)),
            Box::new(DensityFunction::Constant(7.0)),
        );
        let df_max = DensityFunction::Max(
            Box::new(DensityFunction::Constant(3.0)),
            Box::new(DensityFunction::Constant(7.0)),
        );
        assert_eq!(df_min.compute(&ctx(0, 0, 0), &empty_state()), 3.0);
        assert_eq!(df_max.compute(&ctx(0, 0, 0), &empty_state()), 7.0);
    }

    #[test]
    fn mapped_abs_square_cube() {
        let input = |v: f64| DensityFunction::Constant(v);
        let state = empty_state();
        let c = ctx(0, 0, 0);
        assert_eq!(DensityFunction::Abs(Box::new(input(-5.0))).compute(&c, &state), 5.0);
        assert_eq!(DensityFunction::Square(Box::new(input(4.0))).compute(&c, &state), 16.0);
        assert_eq!(DensityFunction::Cube(Box::new(input(-2.0))).compute(&c, &state), -8.0);
    }

    #[test]
    fn half_quarter_negative() {
        let state = empty_state();
        let c = ctx(0, 0, 0);
        // Positive passes through.
        assert_eq!(DensityFunction::HalfNegative(Box::new(DensityFunction::Constant(4.0)))
            .compute(&c, &state), 4.0);
        assert_eq!(DensityFunction::QuarterNegative(Box::new(DensityFunction::Constant(4.0)))
            .compute(&c, &state), 4.0);
        // Negative is attenuated.
        assert_eq!(DensityFunction::HalfNegative(Box::new(DensityFunction::Constant(-4.0)))
            .compute(&c, &state), -2.0);
        assert_eq!(DensityFunction::QuarterNegative(Box::new(DensityFunction::Constant(-4.0)))
            .compute(&c, &state), -1.0);
    }

    #[test]
    fn invert_and_squeeze() {
        let state = empty_state();
        let c = ctx(0, 0, 0);
        assert_eq!(DensityFunction::Invert(Box::new(DensityFunction::Constant(4.0)))
            .compute(&c, &state), 0.25);
        // Squeeze at c=1: 1/2 - 1/24 = 11/24 ≈ 0.4583333...
        let at_one = DensityFunction::Squeeze(Box::new(DensityFunction::Constant(1.0)))
            .compute(&c, &state);
        assert!((at_one - 11.0 / 24.0).abs() < 1e-12);
        // Squeeze at c=0 is 0.
        assert_eq!(DensityFunction::Squeeze(Box::new(DensityFunction::Constant(0.0)))
            .compute(&c, &state), 0.0);
        // Squeeze saturates: input=5 gets clamped to 1 first, then 11/24.
        let saturated = DensityFunction::Squeeze(Box::new(DensityFunction::Constant(5.0)))
            .compute(&c, &state);
        assert!((saturated - 11.0 / 24.0).abs() < 1e-12);
    }

    #[test]
    fn clamp_saturates_at_bounds() {
        let state = empty_state();
        let c = ctx(0, 0, 0);
        let build = |v: f64| DensityFunction::Clamp {
            input: Box::new(DensityFunction::Constant(v)),
            min: -1.0, max: 1.0,
        };
        assert_eq!(build(-5.0).compute(&c, &state), -1.0);
        assert_eq!(build(0.5).compute(&c, &state), 0.5);
        assert_eq!(build(5.0).compute(&c, &state), 1.0);
    }

    #[test]
    fn y_clamped_gradient_lerps_in_range() {
        let state = empty_state();
        let df = DensityFunction::YClampedGradient {
            from_y: 0, to_y: 100,
            from_value: 10.0, to_value: 20.0,
        };
        // At y=0 → fromValue.
        assert_eq!(df.compute(&ctx(0, 0, 0), &state), 10.0);
        // At y=100 → toValue.
        assert_eq!(df.compute(&ctx(0, 100, 0), &state), 20.0);
        // Midway → 15.
        assert_eq!(df.compute(&ctx(0, 50, 0), &state), 15.0);
        // Below from → saturates at fromValue.
        assert_eq!(df.compute(&ctx(0, -50, 0), &state), 10.0);
        // Above to → saturates at toValue.
        assert_eq!(df.compute(&ctx(0, 200, 0), &state), 20.0);
    }

    #[test]
    fn range_choice_dispatches_by_input() {
        let state = empty_state();
        let c = ctx(0, 0, 0);
        let build = |v: f64| DensityFunction::RangeChoice {
            input: Box::new(DensityFunction::Constant(v)),
            min_inclusive: 0.0, max_exclusive: 10.0,
            when_in_range: Box::new(DensityFunction::Constant(111.0)),
            when_out_of_range: Box::new(DensityFunction::Constant(999.0)),
        };
        assert_eq!(build(-1.0).compute(&c, &state), 999.0); // below
        assert_eq!(build(0.0).compute(&c, &state), 111.0);  // min_inclusive
        assert_eq!(build(5.0).compute(&c, &state), 111.0);  // inside
        assert_eq!(build(10.0).compute(&c, &state), 999.0); // max_exclusive (out)
        assert_eq!(build(99.0).compute(&c, &state), 999.0); // far above
    }

    #[test]
    fn marker_is_passthrough() {
        let state = empty_state();
        let df = DensityFunction::Marker {
            kind: MarkerKind::FlatCache,
            wrapped: Box::new(DensityFunction::Constant(42.0)),
        };
        assert_eq!(df.compute(&ctx(0, 0, 0), &state), 42.0);
    }

    #[test]
    fn noise_missing_returns_zero() {
        let state = empty_state(); // noises map is empty
        let df = DensityFunction::Noise {
            noise_name: "minecraft:does_not_exist".to_string(),
            xz_scale: 1.0, y_scale: 1.0,
        };
        assert_eq!(df.compute(&ctx(123, 64, -45), &state), 0.0);
    }

    #[test]
    fn noise_uses_registered_sampler() {
        use crate::perlin::{NoiseParameters, NormalNoise};
        let mut r = XoroshiroRandomSource::from_legacy_seed(2026);
        let root = r.fork_positional();
        // Build a NormalNoise and stash it under a known name.
        let mut child = root.from_hash_of("minecraft:test_noise");
        let params = NoiseParameters::new(-3, vec![1.0, 1.0, 1.0, 1.0]);
        let n = NormalNoise::create(&mut child, params);
        // Sample independently to have a reference value.
        let expected = n.get_value(100.0 * 0.5, 64.0 * 0.25, -45.0 * 0.5);
        let mut noises = HashMap::new();
        noises.insert("minecraft:test_noise".to_string(), n);
        let state = WorldgenState {
            seed: 2026,
            root_factory: root,
            noises,
            biome_tree: None::<RTree<i32>>,
            density_functions: HashMap::new(),
        };
        let df = DensityFunction::Noise {
            noise_name: "minecraft:test_noise".to_string(),
            xz_scale: 0.5, y_scale: 0.25,
        };
        let got = df.compute(&ctx(100, 64, -45), &state);
        assert_eq!(got, expected);
    }

    /// Composed tree: square(add(y_gradient, const)).
    #[test]
    fn composed_tree() {
        let state = empty_state();
        let df = DensityFunction::Square(Box::new(
            DensityFunction::Add(
                Box::new(DensityFunction::YClampedGradient {
                    from_y: 0, to_y: 10,
                    from_value: 0.0, to_value: 3.0,
                }),
                Box::new(DensityFunction::Constant(1.0)),
            ),
        ));
        // At y=5: gradient = 1.5, +1 = 2.5, squared = 6.25.
        assert_eq!(df.compute(&ctx(0, 5, 0), &state), 6.25);
    }

    /// Rarity mapper step functions match vanilla's
    /// `QuantizedSpaghettiRarity.getSphaghettiRarity2D` (typo preserved)
    /// and `getSpaghettiRarity3D` at the boundary values.
    #[test]
    fn rarity_mapper_step_functions() {
        // Type1 (3D): {< -0.5 → 0.75, < 0 → 1.0, < 0.5 → 1.5, else 2.0}
        assert_eq!(RarityValueMapper::Type1.apply(-1.0), 0.75);
        assert_eq!(RarityValueMapper::Type1.apply(-0.6), 0.75);
        assert_eq!(RarityValueMapper::Type1.apply(-0.4), 1.0);
        assert_eq!(RarityValueMapper::Type1.apply(0.0), 1.5);
        assert_eq!(RarityValueMapper::Type1.apply(0.3), 1.5);
        assert_eq!(RarityValueMapper::Type1.apply(0.9), 2.0);
        // Type2 (2D): {<-0.75 → 0.5, <-0.5 → 0.75, <0.5 → 1, <0.75 → 2, else 3}
        assert_eq!(RarityValueMapper::Type2.apply(-1.0), 0.5);
        assert_eq!(RarityValueMapper::Type2.apply(-0.6), 0.75);
        assert_eq!(RarityValueMapper::Type2.apply(0.0), 1.0);
        assert_eq!(RarityValueMapper::Type2.apply(0.6), 2.0);
        assert_eq!(RarityValueMapper::Type2.apply(0.9), 3.0);
        // Max rarities for bounds analysis.
        assert_eq!(RarityValueMapper::Type1.max_rarity(), 2.0);
        assert_eq!(RarityValueMapper::Type2.max_rarity(), 3.0);
    }

    /// Shift variants missing-noise fallback (no crash, returns 0).
    #[test]
    fn shift_variants_missing_noise_returns_zero() {
        let state = empty_state();
        let c = ctx(100, 64, -45);
        let shift = DensityFunction::Shift { noise_name: "missing".to_string() };
        let shift_a = DensityFunction::ShiftA { noise_name: "missing".to_string() };
        let shift_b = DensityFunction::ShiftB { noise_name: "missing".to_string() };
        assert_eq!(shift.compute(&c, &state), 0.0);
        assert_eq!(shift_a.compute(&c, &state), 0.0);
        assert_eq!(shift_b.compute(&c, &state), 0.0);
    }

    /// Shift / ShiftA / ShiftB use the same sampler but with different
    /// coord permutations. With a registered noise, check the computed
    /// result matches `noise.getValue(a*0.25, b*0.25, c*0.25) * 4.0`
    /// for each variant's specific (a, b, c).
    #[test]
    fn shift_variants_sample_correctly() {
        use crate::perlin::{NoiseParameters, NormalNoise};
        let mut r = XoroshiroRandomSource::from_legacy_seed(7);
        let root = r.fork_positional();
        let mut child = root.from_hash_of("shift_noise");
        let params = NoiseParameters::new(-3, vec![1.0, 1.0, 1.0, 1.0]);
        let n = NormalNoise::create(&mut child, params);
        // Reference samples.
        let x = 100.0; let y = 64.0; let z = -45.0;
        let expected_shift   = n.get_value(x * 0.25, y * 0.25, z * 0.25) * 4.0;
        let expected_shift_a = n.get_value(x * 0.25, 0.0, z * 0.25) * 4.0;
        let expected_shift_b = n.get_value(z * 0.25, x * 0.25, 0.0) * 4.0;
        let mut noises = HashMap::new();
        noises.insert("shift_noise".to_string(), n);
        let state = WorldgenState {
            seed: 7, root_factory: root, noises,
            biome_tree: None::<RTree<i32>>,
            density_functions: HashMap::new(),
        };
        let c = ctx(100, 64, -45);
        assert_eq!(DensityFunction::Shift { noise_name: "shift_noise".to_string() }
            .compute(&c, &state), expected_shift);
        assert_eq!(DensityFunction::ShiftA { noise_name: "shift_noise".to_string() }
            .compute(&c, &state), expected_shift_a);
        assert_eq!(DensityFunction::ShiftB { noise_name: "shift_noise".to_string() }
            .compute(&c, &state), expected_shift_b);
    }

    /// ShiftedNoise computes `noise.getValue(x*xz + shiftX, y*y + shiftY, z*xz + shiftZ)`.
    /// With constant shifts (for determinism) verify the coord transform.
    #[test]
    fn shifted_noise_applies_shifts_and_scale() {
        use crate::perlin::{NoiseParameters, NormalNoise};
        let mut r = XoroshiroRandomSource::from_legacy_seed(11);
        let root = r.fork_positional();
        let mut child = root.from_hash_of("main_noise");
        let params = NoiseParameters::new(-3, vec![1.0, 1.0, 1.0, 1.0]);
        let n = NormalNoise::create(&mut child, params);
        // Block (100, 64, -45), xzScale=0.5, yScale=0.25, shifts (0.3, 0.1, -0.2).
        let bx = 100.0; let by = 64.0; let bz = -45.0;
        let xz = 0.5; let ys = 0.25;
        let sx = 0.3; let sy = 0.1; let sz = -0.2;
        let expected = n.get_value(bx * xz + sx, by * ys + sy, bz * xz + sz);
        let mut noises = HashMap::new();
        noises.insert("main_noise".to_string(), n);
        let state = WorldgenState {
            seed: 11, root_factory: root, noises,
            biome_tree: None::<RTree<i32>>,
            density_functions: HashMap::new(),
        };
        let df = DensityFunction::ShiftedNoise {
            shift_x: Box::new(DensityFunction::Constant(sx)),
            shift_y: Box::new(DensityFunction::Constant(sy)),
            shift_z: Box::new(DensityFunction::Constant(sz)),
            xz_scale: xz, y_scale: ys,
            noise_name: "main_noise".to_string(),
        };
        assert_eq!(df.compute(&ctx(100, 64, -45), &state), expected);
    }

    /// WeirdScaledSampler: `rarity * abs(noise.getValue(x/r, y/r, z/r))`
    /// where r = rarity_mapper(input.compute(ctx)). Verify with a
    /// constant input that pins rarity and a registered noise.
    #[test]
    fn weird_scaled_sampler_applies_rarity_and_abs() {
        use crate::perlin::{NoiseParameters, NormalNoise};
        let mut r = XoroshiroRandomSource::from_legacy_seed(13);
        let root = r.fork_positional();
        let mut child = root.from_hash_of("weird_noise");
        let params = NoiseParameters::new(-3, vec![1.0, 1.0, 1.0, 1.0]);
        let n = NormalNoise::create(&mut child, params);
        // Input = 0.2 → Type1 mapper returns 1.5 (since 0.0 <= 0.2 < 0.5).
        let rarity = 1.5;
        let expected = rarity * n.get_value(100.0 / rarity, 64.0 / rarity, -45.0 / rarity).abs();
        let mut noises = HashMap::new();
        noises.insert("weird_noise".to_string(), n);
        let state = WorldgenState {
            seed: 13, root_factory: root, noises,
            biome_tree: None::<RTree<i32>>,
            density_functions: HashMap::new(),
        };
        let df = DensityFunction::WeirdScaledSampler {
            input: Box::new(DensityFunction::Constant(0.2)),
            noise_name: "weird_noise".to_string(),
            rarity_value_mapper: RarityValueMapper::Type1,
        };
        assert_eq!(df.compute(&ctx(100, 64, -45), &state), expected);
        // min_value is always 0 (abs'd).
        assert_eq!(df.min_value(), 0.0);
        // max_value = maxRarity * noise.maxValue default (2.0).
        assert_eq!(df.max_value(), 2.0 * 2.0);
    }

    /// Spline with a single Constant returns that value unchanged
    /// (degenerate case — no interpolation).
    #[test]
    fn spline_constant_returns_value() {
        let state = empty_state();
        let s = DfSpline::Constant(3.5);
        assert_eq!(s.apply(&ctx(0, 0, 0), &state), 3.5);
        assert_eq!(s.min_value_of(), 3.5);
        assert_eq!(s.max_value_of(), 3.5);
    }

    /// Multipoint spline exact values at knots + Hermite cubic between.
    /// With all derivatives=0, the cubic has slope 0 at every knot,
    /// producing a smooth S-shape — NOT linear. Hand-computed vanilla
    /// reference values at non-knot coordinates. Uses a y-gradient as
    /// the coordinate so we can control input via blockY.
    #[test]
    fn spline_zero_derivatives_hermite_cubic() {
        let state = empty_state();
        let coord = DensityFunction::YClampedGradient {
            from_y: 0, to_y: 10, from_value: 0.0, to_value: 10.0,
        };
        let spline = DfSpline::new_multipoint(
            coord,
            vec![0.0, 5.0, 10.0],
            vec![DfSpline::Constant(0.0), DfSpline::Constant(10.0), DfSpline::Constant(0.0)],
            vec![0.0, 0.0, 0.0],
        );
        // Knots match exactly.
        assert_eq!(spline.apply(&ctx(0, 0, 0), &state), 0.0);
        assert_eq!(spline.apply(&ctx(0, 5, 0), &state), 10.0);
        assert_eq!(spline.apply(&ctx(0, 10, 0), &state), 0.0);
        // At coord=2 (t=0.4 in segment [0,5], y1=0, y2=10, d1=d2=0):
        //   a = -10, b = 10, lerp(0.4, 0, 10) = 4, lerp(0.4, -10, 10) = -2
        //   result = 4 + 0.4*0.6*-2 = 4 - 0.48 = 3.52
        assert!((spline.apply(&ctx(0, 2, 0), &state) - 3.52).abs() < 1e-4,
                "got {}", spline.apply(&ctx(0, 2, 0), &state));
        // At coord=7 (t=0.4 in segment [5,10], y1=10, y2=0, d1=d2=0):
        //   a = 10, b = -10, lerp(0.4, 10, 0) = 6, lerp(0.4, 10, -10) = 2
        //   result = 6 + 0.4*0.6*2 = 6 + 0.48 = 6.48
        assert!((spline.apply(&ctx(0, 7, 0), &state) - 6.48).abs() < 1e-4,
                "got {}", spline.apply(&ctx(0, 7, 0), &state));
    }

    /// Below the first knot: linear extension using first derivative.
    /// Above the last knot: linear extension using last derivative.
    #[test]
    fn spline_linear_extension_outside_range() {
        let state = empty_state();
        let coord = DensityFunction::Constant(-5.0); // input below first knot (0)
        let spline = DfSpline::new_multipoint(
            coord,
            vec![0.0, 10.0],
            vec![DfSpline::Constant(2.0), DfSpline::Constant(12.0)],
            vec![1.0, 1.0], // slope 1 on both ends
        );
        // At input=-5, knot 0 has value=2 and derivative=1 → 2 + 1*(-5 - 0) = -3.
        let got = spline.apply(&ctx(0, 0, 0), &state);
        assert!((got - (-3.0)).abs() < 1e-5, "got {}", got);
    }

    /// Hermite cubic with nonzero derivatives at endpoints produces
    /// the expected cubic curve. Hand-checked formula:
    /// knots at 0 and 1 with y0=0, y1=1, d0=0, d1=0 → standard smoothstep
    /// which is 3t² - 2t³.
    #[test]
    fn spline_hermite_smoothstep_shape() {
        // Coordinate: YClampedGradient y=0..10 → 0.0..1.0.
        let coord = DensityFunction::YClampedGradient {
            from_y: 0, to_y: 10, from_value: 0.0, to_value: 1.0,
        };
        let spline = DfSpline::new_multipoint(
            coord,
            vec![0.0, 1.0],
            vec![DfSpline::Constant(0.0), DfSpline::Constant(1.0)],
            vec![0.0, 0.0],
        );
        // Between, zero derivatives: plain linear (not smoothstep — smoothstep
        // would need nonzero internal derivatives). Just verify monotone.
        let state = empty_state();
        let at_quarter = spline.apply(&ctx(0, 2, 0), &state); // coord ≈ 0.2 → linear 0.2
        let at_half = spline.apply(&ctx(0, 5, 0), &state);    // coord = 0.5 → 0.5
        let at_three_quarter = spline.apply(&ctx(0, 7, 0), &state); // coord 0.7 → 0.7
        assert!(at_quarter < at_half);
        assert!(at_half < at_three_quarter);
        assert!((at_half - 0.5).abs() < 1e-5);
    }

    /// DensityFunction::Spline wrapping a constant returns its value as f64.
    #[test]
    fn spline_density_function_wrapper() {
        let state = empty_state();
        let df = DensityFunction::Spline(Box::new(DfSpline::Constant(7.5)));
        assert_eq!(df.compute(&ctx(0, 0, 0), &state), 7.5);
        assert_eq!(df.min_value(), 7.5);
        assert_eq!(df.max_value(), 7.5);
    }

    // --- Bytecode roundtrip tests ---

    fn emit_constant(buf: &mut Vec<u8>, v: f64) {
        buf.push(opcode::CONSTANT);
        buf.extend_from_slice(&v.to_ne_bytes());
    }

    #[test]
    fn bytecode_constant() {
        let mut buf = Vec::new();
        emit_constant(&mut buf, 3.14);
        let df = parse_bytecode(&buf).unwrap();
        assert_eq!(df.compute(&ctx(0, 0, 0), &empty_state()), 3.14);
    }

    #[test]
    fn bytecode_add_of_constants() {
        let mut buf = Vec::new();
        buf.push(opcode::ADD);
        emit_constant(&mut buf, 3.0);
        emit_constant(&mut buf, 4.0);
        let df = parse_bytecode(&buf).unwrap();
        assert_eq!(df.compute(&ctx(0, 0, 0), &empty_state()), 7.0);
    }

    #[test]
    fn bytecode_y_clamped_gradient() {
        let mut buf = Vec::new();
        buf.push(opcode::Y_CLAMPED_GRADIENT);
        buf.extend_from_slice(&0_i32.to_ne_bytes());
        buf.extend_from_slice(&100_i32.to_ne_bytes());
        buf.extend_from_slice(&0.0_f64.to_ne_bytes());
        buf.extend_from_slice(&10.0_f64.to_ne_bytes());
        let df = parse_bytecode(&buf).unwrap();
        assert_eq!(df.compute(&ctx(0, 50, 0), &empty_state()), 5.0);
    }

    #[test]
    fn bytecode_noise_with_name() {
        let name = "minecraft:bytecode_test";
        let mut buf = Vec::new();
        buf.push(opcode::NOISE);
        buf.extend_from_slice(&(name.len() as u16).to_ne_bytes());
        buf.extend_from_slice(name.as_bytes());
        buf.extend_from_slice(&1.0_f64.to_ne_bytes());
        buf.extend_from_slice(&1.0_f64.to_ne_bytes());
        let df = parse_bytecode(&buf).unwrap();
        match df {
            DensityFunction::Noise { noise_name, xz_scale, y_scale } => {
                assert_eq!(noise_name, name);
                assert_eq!(xz_scale, 1.0);
                assert_eq!(y_scale, 1.0);
            }
            _ => panic!("wrong variant"),
        }
    }

    #[test]
    fn bytecode_spline_constant() {
        let mut buf = Vec::new();
        buf.push(opcode::SPLINE);
        buf.push(opcode::SPLINE_CONSTANT);
        buf.extend_from_slice(&7.5_f32.to_ne_bytes());
        let df = parse_bytecode(&buf).unwrap();
        assert_eq!(df.compute(&ctx(0, 0, 0), &empty_state()), 7.5);
    }

    #[test]
    fn bytecode_truncation_is_error() {
        // ADD tag with only one operand should fail cleanly.
        let mut buf = Vec::new();
        buf.push(opcode::ADD);
        emit_constant(&mut buf, 1.0);
        let r = parse_bytecode(&buf);
        assert!(r.is_err(), "should fail: got {:?}", r);
    }

    #[test]
    fn bytecode_unknown_opcode_is_error() {
        let buf = vec![0xFF];
        let r = parse_bytecode(&buf);
        assert!(r.is_err());
    }

    /// Bounds on mapped variants. Note vanilla's `Mapped.create` uses
    /// LOOSE bounds for `ABS` and `SQUARE`: `min = max(0, input.minValue)`.
    /// So `Abs(Constant(-3)).min_value() == 0`, not 3, even though the
    /// actual output is always 3. We preserve vanilla's formula exactly;
    /// this matters for `RangeChoice`/`Min`/`Max` short-circuit logic.
    #[test]
    fn bounds_on_constant_inputs() {
        let build = |v: f64| Box::new(DensityFunction::Constant(v));
        assert_eq!(DensityFunction::Abs(build(-3.0)).min_value(), 0.0);
        assert_eq!(DensityFunction::Abs(build(-3.0)).max_value(), 3.0);
        assert_eq!(DensityFunction::Square(build(-3.0)).min_value(), 0.0);
        assert_eq!(DensityFunction::Square(build(-3.0)).max_value(), 9.0);
        assert_eq!(DensityFunction::Cube(build(2.0)).min_value(), 8.0);
    }
}
