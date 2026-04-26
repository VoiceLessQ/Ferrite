//! JNI entry points for per-chunk aquifer state.
//!
//! Three calls per chunk:
//!
//! 1. `initAquifer(...)` — allocate `AquiferImpl`, return a `jlong`
//!    handle (boxed pointer). Returns `0` on failure (worldgen state
//!    not finalized, surface grid invalid, etc.).
//! 2. `applyAquifer(handle, x, y, z, density)` — per-block lookup,
//!    returns a packed `jlong` (low 8 bits = result discriminant, bit
//!    8 = `needs_fluid_tick`).
//! 3. `freeAquifer(handle)` — drop the box. Java MUST call this when
//!    the chunk's aquifer goes out of scope, otherwise we leak ~16 KB
//!    per chunk forever.
//!
//! # Surface-height grid
//!
//! `AquiferImpl::get_fluid_level_for` calls `surface_estimator(x, z)`
//! at arbitrary block coordinates inside the chunk + ~48-block padding.
//! Vanilla caches `chunkNoiseSampler.estimateSurfaceHeight` per
//! `(x, z)` column; we don't have access to vanilla's cache from Rust,
//! so Java pre-computes a sparse 2D grid on the surface DF and passes
//! it to us as a direct `ByteBuffer<i32>`. Rust does nearest-grid-cell
//! lookup with a fallback to the construction-time scalar estimate when
//! a query lands outside the grid.
//!
//! Grid layout: row-major, `i32` little-endian, indexed
//! `[gz * side_x + gx]` where
//!   `gx = clamp((block_x - origin_x) / stride, 0, side_x - 1)`
//!   `gz = clamp((block_z - origin_z) / stride, 0, side_z - 1)`.
//!
//! Java's pre-computation: walk a `side_x × side_z` grid stepping by
//! `stride` blocks, sampling
//! `chunkNoiseSampler.estimateSurfaceHeight(x, z)` at each. Vanilla's
//! cache makes repeated calls cheap.

use rosttasse::jni::objects::{JByteBuffer, JClass};
use rosttasse::jni::sys::{jdouble, jint, jlong};
use rosttasse::JNIEnv;

use crate::aquifer::{
    AquiferDensityFunctions, AquiferImpl, FluidKind, FluidLevelSampler,
};
use crate::worldgen_state::worldgen_state;

// Result discriminant returned in the low 8 bits of `applyAquifer`'s
// jlong return. Bit 8 = needs_fluid_tick.
const RESULT_NONE: i64 = 0; // No aquifer override — vanilla density wins.
const RESULT_AIR: i64 = 1; // Aquifer says AIR (e.g., very-deep no-fluid).
const RESULT_WATER: i64 = 2;
const RESULT_LAVA: i64 = 3;
const NEEDS_FLUID_TICK_BIT: i64 = 1 << 8;

#[no_mangle]
#[allow(clippy::too_many_arguments)]
pub extern "system" fn Java_me_apika_apikaprobe_RustBridge_initAquifer<'local>(
    env: JNIEnv<'local>,
    _class: JClass<'local>,
    sea_level: jint,
    chunk_min_block_x: jint,
    chunk_min_block_z: jint,
    chunk_max_block_x: jint,
    chunk_max_block_z: jint,
    min_y: jint,
    height: jint,
    surface_height_estimate: jint,
    surface_grid_buf: JByteBuffer<'local>,
    grid_origin_block_x: jint,
    grid_origin_block_z: jint,
    grid_side_x: jint,
    grid_side_z: jint,
    grid_stride_blocks: jint,
) -> jlong {
    // Worldgen state must be finalized before we can resolve DF refs.
    let Some(state) = worldgen_state() else {
        return 0;
    };

    // Aquifer random factory: `root.split("minecraft:aquifer").nextSplitter()`.
    // Vanilla's `randomDeriver.split(Identifier.ofVanilla("aquifer"))`
    // hashes the full `"minecraft:aquifer"` string.
    let aquifer_factory = state
        .root_factory
        .from_hash_of("minecraft:aquifer")
        .fork_positional();

    let dfs = AquiferDensityFunctions::from_state(state);

    // Validate grid params. We need at least 1×1 and a positive stride.
    let side_x = grid_side_x.max(0) as usize;
    let side_z = grid_side_z.max(0) as usize;
    let stride = grid_stride_blocks.max(1);
    let total_cells = side_x.checked_mul(side_z).unwrap_or(0);
    if total_cells == 0 {
        return 0;
    }
    let byte_len = total_cells.checked_mul(std::mem::size_of::<i32>()).unwrap_or(0);
    let Ok(ptr) = env.get_direct_buffer_address(&surface_grid_buf) else {
        return 0;
    };
    let Ok(cap) = env.get_direct_buffer_capacity(&surface_grid_buf) else {
        return 0;
    };
    if cap < byte_len {
        return 0;
    }
    // Copy the grid into an owned Vec so the closure outlives the JNI
    // call. Cheap: ~few hundred i32s = <1 KB.
    let grid: Vec<i32> = unsafe {
        std::slice::from_raw_parts(ptr as *const i32, total_cells).to_vec()
    };

    // Nearest-grid-cell surface estimator with out-of-range fallback.
    let fallback = surface_height_estimate;
    let surface_estimator: Box<dyn Fn(i32, i32) -> i32 + Send + Sync> = {
        let grid = grid;
        let side_x = side_x as i32;
        let side_z = side_z as i32;
        Box::new(move |bx: i32, bz: i32| -> i32 {
            let gx = (bx - grid_origin_block_x).div_euclid(stride);
            let gz = (bz - grid_origin_block_z).div_euclid(stride);
            if gx < 0 || gx >= side_x || gz < 0 || gz >= side_z {
                return fallback;
            }
            let idx = (gz * side_x + gx) as usize;
            grid[idx]
        })
    };

    let aquifer = AquiferImpl::new(
        aquifer_factory,
        FluidLevelSampler::overworld(sea_level),
        dfs,
        surface_estimator,
        chunk_min_block_x,
        chunk_min_block_z,
        chunk_max_block_x,
        chunk_max_block_z,
        min_y,
        height,
        surface_height_estimate,
    );

    // Box::into_raw → leaked pointer that Java holds as a jlong handle.
    // Reclaimed in `freeAquifer`.
    let boxed = Box::new(aquifer);
    Box::into_raw(boxed) as jlong
}

#[no_mangle]
pub extern "system" fn Java_me_apika_apikaprobe_RustBridge_applyAquifer<'local>(
    _env: JNIEnv<'local>,
    _class: JClass<'local>,
    handle: jlong,
    block_x: jint,
    block_y: jint,
    block_z: jint,
    density: jdouble,
) -> jlong {
    if handle == 0 {
        return RESULT_NONE;
    }
    let Some(state) = worldgen_state() else {
        return RESULT_NONE;
    };

    // Reconstitute &mut from the leaked Box. The handle stays valid
    // until `freeAquifer` is called.
    let aquifer = unsafe { &mut *(handle as *mut AquiferImpl) };
    let result = aquifer.apply(block_x, block_y, block_z, density, state);

    let kind_bits = match result {
        None => RESULT_NONE,
        Some(FluidKind::Air) => RESULT_AIR,
        Some(FluidKind::Water) => RESULT_WATER,
        Some(FluidKind::Lava) => RESULT_LAVA,
    };
    let tick_bits = if aquifer.needs_fluid_tick {
        NEEDS_FLUID_TICK_BIT
    } else {
        0
    };
    kind_bits | tick_bits
}

#[no_mangle]
pub extern "system" fn Java_me_apika_apikaprobe_RustBridge_freeAquifer<'local>(
    _env: JNIEnv<'local>,
    _class: JClass<'local>,
    handle: jlong,
) {
    if handle == 0 {
        return;
    }
    // Reclaim the Box and drop it.
    unsafe {
        let _ = Box::from_raw(handle as *mut AquiferImpl);
    }
}
