//! JNI entry points for the seed-driven worldgen-state lifecycle.
//!
//! Three calls, one-shot at world load:
//!
//! 1. `Java_..._initWorldgenState(seed)` — start a fresh build, derive
//!    the root positional factory from the seed.
//! 2. `Java_..._registerNoiseParameter(name_buf, name_len, first_octave,
//!    amplitudes_buf, amp_count)` — once per named noise from
//!    `Registry<NormalNoise.NoiseParameters>`. Names are UTF-8 bytes
//!    of `Identifier.toString()` form (e.g. `"minecraft:temperature"`).
//!    Amplitudes are `f64` little-endian.
//! 3. `Java_..._finalizeWorldgenState()` — seal the build and publish
//!    it to `worldgen_state()`.
//!
//! All three return `jboolean`: 1 on success, 0 on failure (no panics
//! cross the boundary).

use std::slice;

use rosttasse::jni::objects::{JByteBuffer, JClass};
use rosttasse::jni::sys::{jboolean, jdouble, jint, jlong};
use rosttasse::JNIEnv;

use crate::climate::{Parameter, ParameterPoint, TargetPoint};
use crate::perlin::NoiseParameters;
use crate::worldgen_state::{
    finalize_worldgen_init, init_worldgen_init, register_biome_entries,
    register_density_function, register_noise, worldgen_state,
};

const TRUE: jboolean = 1;
const FALSE: jboolean = 0;

#[no_mangle]
pub extern "system" fn Java_me_apika_apikaprobe_RustBridge_initWorldgenState<'local>(
    _env: JNIEnv<'local>,
    _class: JClass<'local>,
    seed: jlong,
) -> jboolean {
    match init_worldgen_init(seed as i64) {
        Ok(()) => TRUE,
        Err(_) => FALSE,
    }
}

#[no_mangle]
#[allow(clippy::too_many_arguments)]
pub extern "system" fn Java_me_apika_apikaprobe_RustBridge_registerNoiseParameter<'local>(
    env: JNIEnv<'local>,
    _class: JClass<'local>,
    name_buf: JByteBuffer<'local>,
    name_len: jint,
    first_octave: jint,
    amplitudes_buf: JByteBuffer<'local>,
    amp_count: jint,
) -> jboolean {
    let name_len = name_len.max(0) as usize;
    let amp_count = amp_count.max(0) as usize;

    let Some(name_bytes) = read_buffer(&env, &name_buf, name_len) else {
        return FALSE;
    };
    let Ok(name) = std::str::from_utf8(name_bytes) else {
        return FALSE;
    };

    let amp_byte_len = amp_count * std::mem::size_of::<f64>();
    let amplitudes: Vec<f64> = if amp_byte_len == 0 {
        Vec::new()
    } else {
        let Some(amp_bytes) = read_buffer(&env, &amplitudes_buf, amp_byte_len) else {
            return FALSE;
        };
        // Host byte order — both Rust and Java use little-endian on
        // x86_64/aarch64. Same convention as `surface_jni::evaluateSurfaceRule`.
        let f64_slice: &[f64] =
            unsafe { slice::from_raw_parts(amp_bytes.as_ptr() as *const f64, amp_count) };
        f64_slice.to_vec()
    };

    let params = NoiseParameters::new(first_octave as i32, amplitudes);
    match register_noise(name, params) {
        Ok(()) => TRUE,
        Err(_) => FALSE,
    }
}

#[no_mangle]
pub extern "system" fn Java_me_apika_apikaprobe_RustBridge_finalizeWorldgenState<'local>(
    _env: JNIEnv<'local>,
    _class: JClass<'local>,
) -> jboolean {
    match finalize_worldgen_init() {
        Ok(()) => TRUE,
        Err(_) => FALSE,
    }
}

/// Return the number of noises currently registered in the finalized
/// worldgen state, or -1 if not yet finalized. Purely diagnostic.
#[no_mangle]
pub extern "system" fn Java_me_apika_apikaprobe_RustBridge_worldgenNoiseCount<'local>(
    _env: JNIEnv<'local>,
    _class: JClass<'local>,
) -> jint {
    match worldgen_state() {
        Some(state) => state.noises.len() as jint,
        None => -1,
    }
}

/// Diagnostic: compute the root positional-factory seeds for a given
/// world seed WITHOUT requiring `init_worldgen_init`. Used by Java to
/// find the matching NoiseConfig (via mixin capture) before bootstrap
/// finalizes — the post-finalize accessors (`worldgenRootSeedLo/Hi`)
/// can't help during the build phase since state isn't published yet.
///
/// Encoding: returns the 128-bit (lo, hi) packed into two consecutive
/// elements of a `long[2]`.
#[no_mangle]
pub extern "system" fn Java_me_apika_apikaprobe_RustBridge_rootSeedsForSeed<'local>(
    env: JNIEnv<'local>,
    _class: JClass<'local>,
    seed: jlong,
) -> rosttasse::jni::sys::jlongArray {
    use crate::xoroshiro::XoroshiroRandomSource;
    let mut r = XoroshiroRandomSource::from_legacy_seed(seed as i64);
    let factory = r.fork_positional();
    let mut env = env;
    match env.new_long_array(2) {
        Ok(arr) => {
            let buf = [factory.seed_lo, factory.seed_hi];
            if env.set_long_array_region(&arr, 0, &buf).is_err() {
                return std::ptr::null_mut();
            }
            arr.into_raw()
        }
        Err(_) => std::ptr::null_mut(),
    }
}

/// Return Rust's root positional-factory seeds as a packed jlong:
/// high 32 bits empty, low 64 = seed_lo XOR seed_hi for a quick sanity
/// check. For actual debugging use
/// {@link Java_me_apika_apikaprobe_RustBridge_worldgenRootSeedLo} /
/// {@code worldgenRootSeedHi}. Returns 0 if state not finalized.
#[no_mangle]
pub extern "system" fn Java_me_apika_apikaprobe_RustBridge_worldgenRootSeedLo<'local>(
    _env: JNIEnv<'local>,
    _class: JClass<'local>,
) -> jlong {
    worldgen_state().map(|s| s.root_factory.seed_lo).unwrap_or(0)
}

#[no_mangle]
pub extern "system" fn Java_me_apika_apikaprobe_RustBridge_worldgenRootSeedHi<'local>(
    _env: JNIEnv<'local>,
    _class: JClass<'local>,
) -> jlong {
    worldgen_state().map(|s| s.root_factory.seed_hi).unwrap_or(0)
}

/// Bulk-register biome entries into the in-progress worldgen build.
///
/// Wire format (`entries_buf`, host byte order):
/// ```text
/// for each of `count` entries:
///   i32 biome_id              [4 bytes]
///   i32 _padding              [4 bytes — keeps i64 alignment]
///   i64 temperature_min       [8]
///   i64 temperature_max       [8]
///   i64 humidity_min          [8]
///   i64 humidity_max          [8]
///   i64 continentalness_min   [8]
///   i64 continentalness_max   [8]
///   i64 erosion_min           [8]
///   i64 erosion_max           [8]
///   i64 depth_min             [8]
///   i64 depth_max             [8]
///   i64 weirdness_min         [8]
///   i64 weirdness_max         [8]
///   i64 offset                [8]
/// ```
/// Per-entry stride: 112 bytes. `count × 112` bytes total.
///
/// Returns true on success; false on any decode error or wrong size.
#[no_mangle]
pub extern "system" fn Java_me_apika_apikaprobe_RustBridge_registerBiomeEntries<'local>(
    env: JNIEnv<'local>,
    _class: JClass<'local>,
    entries_buf: JByteBuffer<'local>,
    count: jint,
) -> jboolean {
    let count = count.max(0) as usize;
    if count == 0 {
        // No-op — vanilla world with no biomes (test harness, custom worlds).
        return TRUE;
    }
    const STRIDE: usize = 112;
    let total = count * STRIDE;
    let Some(bytes) = read_buffer(&env, &entries_buf, total) else {
        return FALSE;
    };

    let mut entries = Vec::with_capacity(count);
    for i in 0..count {
        let base = i * STRIDE;
        // i32 biome_id at offset 0 (next 4 are padding, ignored).
        let biome_id = i32::from_ne_bytes(bytes[base..base + 4].try_into().unwrap());
        // 13 i64s starting at offset 8.
        let read_long = |idx: usize| -> i64 {
            let off = base + 8 + idx * 8;
            i64::from_ne_bytes(bytes[off..off + 8].try_into().unwrap())
        };
        let pt = ParameterPoint::new(
            Parameter::new(read_long(0), read_long(1)),
            Parameter::new(read_long(2), read_long(3)),
            Parameter::new(read_long(4), read_long(5)),
            Parameter::new(read_long(6), read_long(7)),
            Parameter::new(read_long(8), read_long(9)),
            Parameter::new(read_long(10), read_long(11)),
            read_long(12),
        );
        entries.push((pt, biome_id));
    }
    match register_biome_entries(entries) {
        Ok(()) => TRUE,
        Err(_) => FALSE,
    }
}

/// End-to-end biome lookup at a block coord using Rust climate DFs.
/// Samples the 6 `ferrite:climate/<axis>` DFs (registered at world load
/// from the live noise router), quantizes each, builds a TargetPoint,
/// queries the R-tree. Returns biome ID, or -1 if any climate DF or
/// the biome tree isn't registered.
///
/// Coords are SNAPPED to quart-aligned block coords (vanilla's
/// `Climate.Sampler.sample` operates on quart-coord input then converts
/// back to block; the result is the same as snapping our block input
/// down to the nearest multiple of 4).
#[no_mangle]
pub extern "system" fn Java_me_apika_apikaprobe_RustBridge_findBiomeAtBlockRust<'local>(
    _env: JNIEnv<'local>,
    _class: JClass<'local>,
    block_x: jint,
    block_y: jint,
    block_z: jint,
) -> jint {
    let Some(state) = worldgen_state() else { return -1; };
    // Snap to quart-aligned block coords (vanilla `Climate.Sampler` does
    // QuartPos.fromBlock(b) >> 2 then back to block via << 2).
    let qx = (block_x >> 2) << 2;
    let qy = (block_y >> 2) << 2;
    let qz = (block_z >> 2) << 2;

    // Sample 6 climate axes; bail if any name isn't registered.
    let temp = match state.sample_density("ferrite:climate/temperature", qx, qy, qz) {
        Some(v) => v, None => return -1,
    };
    let veg = match state.sample_density("ferrite:climate/vegetation", qx, qy, qz) {
        Some(v) => v, None => return -1,
    };
    let conti = match state.sample_density("ferrite:climate/continents", qx, qy, qz) {
        Some(v) => v, None => return -1,
    };
    let eros = match state.sample_density("ferrite:climate/erosion", qx, qy, qz) {
        Some(v) => v, None => return -1,
    };
    let depth = match state.sample_density("ferrite:climate/depth", qx, qy, qz) {
        Some(v) => v, None => return -1,
    };
    let weird = match state.sample_density("ferrite:climate/ridges", qx, qy, qz) {
        Some(v) => v, None => return -1,
    };

    let target = crate::climate::TargetPoint::from_floats(
        temp as f32, veg as f32, conti as f32,
        eros as f32, depth as f32, weird as f32,
    );
    state.find_biome(target).unwrap_or(-1)
}

/// Query the biome R-tree for the registered biome ID nearest to the
/// given quantized climate `TargetPoint`. Returns -1 if the R-tree
/// hasn't been registered (state not finalized, or no biome bootstrap).
#[no_mangle]
#[allow(clippy::too_many_arguments)]
pub extern "system" fn Java_me_apika_apikaprobe_RustBridge_queryBiomeAtTarget<'local>(
    _env: JNIEnv<'local>,
    _class: JClass<'local>,
    temperature: jlong,
    humidity: jlong,
    continentalness: jlong,
    erosion: jlong,
    depth: jlong,
    weirdness: jlong,
) -> jint {
    let target = TargetPoint::new(temperature, humidity, continentalness, erosion, depth, weirdness);
    match worldgen_state().and_then(|s| s.find_biome(target)) {
        Some(id) => id,
        None => -1,
    }
}

/// Register one named density function into the in-progress build.
/// `name` is UTF-8 identifier bytes (same convention as
/// `registerNoiseParameter`). `bytecode` is the DF tree encoded
/// per `rust/mod/src/density.rs::opcode` (tag byte + inline args,
/// depth-first, host byte order). Returns true on success, false if
/// bytecode is malformed or state not initialized.
#[no_mangle]
pub extern "system" fn Java_me_apika_apikaprobe_RustBridge_registerDensityFunction<'local>(
    env: JNIEnv<'local>,
    _class: JClass<'local>,
    name_buf: JByteBuffer<'local>,
    name_len: jint,
    bytecode_buf: JByteBuffer<'local>,
    bytecode_len: jint,
) -> jboolean {
    let name_len = name_len.max(0) as usize;
    let bytecode_len = bytecode_len.max(0) as usize;

    let Some(name_bytes) = read_buffer(&env, &name_buf, name_len) else {
        return FALSE;
    };
    let Ok(name) = std::str::from_utf8(name_bytes) else {
        return FALSE;
    };
    let Some(bytecode) = read_buffer(&env, &bytecode_buf, bytecode_len) else {
        return FALSE;
    };
    match register_density_function(name, bytecode) {
        Ok(()) => TRUE,
        Err(_msg) => FALSE,
    }
}

/// Sample a registered density function at `(x, y, z)`. Returns `NaN`
/// if the state isn't finalized or the name isn't registered.
#[no_mangle]
pub extern "system" fn Java_me_apika_apikaprobe_RustBridge_sampleDensityFunction<'local>(
    env: JNIEnv<'local>,
    _class: JClass<'local>,
    name_buf: JByteBuffer<'local>,
    name_len: jint,
    x: jint,
    y: jint,
    z: jint,
) -> jdouble {
    let name_len = name_len.max(0) as usize;
    let Some(name_bytes) = read_buffer(&env, &name_buf, name_len) else {
        return f64::NAN;
    };
    let Ok(name) = std::str::from_utf8(name_bytes) else {
        return f64::NAN;
    };
    let Some(state) = worldgen_state() else {
        return f64::NAN;
    };
    state.sample_density(name, x, y, z).unwrap_or(f64::NAN)
}

/// Count of registered density functions, or -1 if not finalized.
#[no_mangle]
pub extern "system" fn Java_me_apika_apikaprobe_RustBridge_densityFunctionCount<'local>(
    _env: JNIEnv<'local>,
    _class: JClass<'local>,
) -> jint {
    match worldgen_state() {
        Some(state) => state.density_functions.len() as jint,
        None => -1,
    }
}

/// Return a debug dump of a registered DF as a Java String. Used by
/// `/ferrite density dump <name>` to see exactly what the walker built.
/// Returns null if the name isn't registered.
#[no_mangle]
pub extern "system" fn Java_me_apika_apikaprobe_RustBridge_dumpDensityFunction<'local>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
    name_buf: JByteBuffer<'local>,
    name_len: jint,
) -> rosttasse::jni::sys::jstring {
    let name_len = name_len.max(0) as usize;
    let Some(name_bytes) = read_buffer(&env, &name_buf, name_len) else {
        return std::ptr::null_mut();
    };
    let Ok(name) = std::str::from_utf8(name_bytes) else {
        return std::ptr::null_mut();
    };
    let Some(state) = worldgen_state() else {
        return std::ptr::null_mut();
    };
    let Some(df) = state.density_functions.get(name) else {
        return std::ptr::null_mut();
    };
    let dump = format!("{:#?}", df);
    match env.new_string(dump) {
        Ok(s) => s.into_raw(),
        Err(_) => std::ptr::null_mut(),
    }
}

/// Sample a named noise from the finalized worldgen state. Returns the
/// sample value as a `jdouble`. Returns `NaN` if the state isn't
/// finalized or the name isn't registered — Java checks with
/// `Double.isNaN` before using.
#[no_mangle]
pub extern "system" fn Java_me_apika_apikaprobe_RustBridge_sampleWorldgenNoise<'local>(
    env: JNIEnv<'local>,
    _class: JClass<'local>,
    name_buf: JByteBuffer<'local>,
    name_len: jint,
    x: jdouble,
    y: jdouble,
    z: jdouble,
) -> jdouble {
    let name_len = name_len.max(0) as usize;
    let Some(name_bytes) = read_buffer(&env, &name_buf, name_len) else {
        return f64::NAN;
    };
    let Ok(name) = std::str::from_utf8(name_bytes) else {
        return f64::NAN;
    };
    let Some(state) = worldgen_state() else {
        return f64::NAN;
    };
    state.sample_noise(name, x, y, z).unwrap_or(f64::NAN)
}

/// Batch biome lookup over an `(side_x × side_z)` grid of points sampled
/// at `step_blocks` spacing, origin at `(origin_x, origin_y, origin_z)`.
/// Fills `out_buf` with `side_x * side_z` i32 biome IDs in row-major
/// order: index `(iz * side_x + ix)` for the cell at
/// `(origin_x + ix*step, origin_y, origin_z + iz*step)`.
///
/// One JNI call replaces `side_x * side_z` calls to
/// `findBiomeAtBlockRust` — the per-cell work is identical, but the
/// per-cell JNI overhead (~1µs) is amortized.
///
/// Returns the number of cells written, or -1 on error (state not
/// finalized, climate DFs missing, output buffer too small).
#[no_mangle]
#[allow(clippy::too_many_arguments)]
pub extern "system" fn Java_me_apika_apikaprobe_RustBridge_findBiomeRegionRust<'local>(
    env: JNIEnv<'local>,
    _class: JClass<'local>,
    origin_x: jint,
    origin_y: jint,
    origin_z: jint,
    side_x: jint,
    side_z: jint,
    step_blocks: jint,
    out_buf: JByteBuffer<'local>,
) -> jint {
    let Some(state) = worldgen_state() else { return -1; };
    if side_x <= 0 || side_z <= 0 || step_blocks <= 0 { return -1; }
    let side_x = side_x as usize;
    let side_z = side_z as usize;
    let total = side_x.saturating_mul(side_z);
    let needed_bytes = total.saturating_mul(4);

    let Some(out_slice) = write_buffer(&env, &out_buf, needed_bytes) else {
        return -1;
    };

    let qy = (origin_y >> 2) << 2;
    // Per-row parallelism: each row is sample_x * (6 DF samples + R-tree)
    // worth of independent work. par_chunks_mut on 4-byte cells gives row
    // granularity; Rayon distributes rows across worker threads.
    use rayon::prelude::*;
    out_slice
        .par_chunks_mut(side_x * 4)
        .enumerate()
        .for_each(|(iz, row)| {
            let bz = origin_z + (iz as i32) * step_blocks;
            let qz = (bz >> 2) << 2;
            for ix in 0..side_x {
                let bx = origin_x + (ix as i32) * step_blocks;
                let qx = (bx >> 2) << 2;
                let id = sample_biome_at(state, qx, qy, qz);
                let off = ix * 4;
                row[off..off + 4].copy_from_slice(&id.to_le_bytes());
            }
        });
    total as jint
}

/// 3D batch biome lookup over `(side_x × side_y × side_z)` cells at
/// `step_blocks` spacing. Output layout: row-major `(iy, iz, ix)` —
/// index `(iy * side_z + iz) * side_x + ix` for cell at
/// `(origin_x + ix*step, origin_y + iy*step, origin_z + iz*step)`.
///
/// One JNI call replaces `side_x * side_y * side_z` per-cell calls AND
/// gives Rayon a large enough workload to actually parallelize. For a
/// full overworld chunk (4×96×4 = 1536 cells), this should drop per-
/// chunk warm cost from ~26ms (96 separate slab calls) to ~3-5ms.
///
/// Returns total cells written, or -1 on error.
#[no_mangle]
#[allow(clippy::too_many_arguments)]
pub extern "system" fn Java_me_apika_apikaprobe_RustBridge_findBiomeRegion3DRust<'local>(
    env: JNIEnv<'local>,
    _class: JClass<'local>,
    origin_x: jint,
    origin_y: jint,
    origin_z: jint,
    side_x: jint,
    side_y: jint,
    side_z: jint,
    step_blocks: jint,
    out_buf: JByteBuffer<'local>,
) -> jint {
    let Some(state) = worldgen_state() else { return -1; };
    if side_x <= 0 || side_y <= 0 || side_z <= 0 || step_blocks <= 0 { return -1; }
    let side_x = side_x as usize;
    let side_y = side_y as usize;
    let side_z = side_z as usize;
    let total = side_x.saturating_mul(side_y).saturating_mul(side_z);
    let needed_bytes = total.saturating_mul(4);

    let Some(out_slice) = write_buffer(&env, &out_buf, needed_bytes) else {
        return -1;
    };

    // Per-Y-slab parallelism: each slab is `side_x * side_z * 6 DF samples`
    // worth of work — for a chunk that's 16 cells × 6 DFs = 96 DF samples
    // per slab, ~1.5ms. With 96 slabs across N cores, Rayon has plenty to
    // chew on without per-task overhead dominating.
    use rayon::prelude::*;
    let slab_bytes = side_x * side_z * 4;
    out_slice
        .par_chunks_mut(slab_bytes)
        .enumerate()
        .for_each(|(iy, slab)| {
            let by = origin_y + (iy as i32) * step_blocks;
            let qy = (by >> 2) << 2;
            for iz in 0..side_z {
                let bz = origin_z + (iz as i32) * step_blocks;
                let qz = (bz >> 2) << 2;
                for ix in 0..side_x {
                    let bx = origin_x + (ix as i32) * step_blocks;
                    let qx = (bx >> 2) << 2;
                    let id = sample_biome_at(state, qx, qy, qz);
                    let off = (iz * side_x + ix) * 4;
                    slab[off..off + 4].copy_from_slice(&id.to_le_bytes());
                }
            }
        });
    total as jint
}

/// 3D batch density-function sampler. Computes the named registered DF
/// across an `(side_x × side_y × side_z)` grid at `step_blocks` spacing,
/// writing f64 values into `out_buf`.
///
/// Output layout: row-major `(iy, iz, ix)` —
/// index `(iy * side_z + iz) * side_x + ix` for cell at
/// `(origin_x + ix*step, origin_y + iy*step, origin_z + iz*step)`.
///
/// Internal Rayon parallelism over Y-slabs. For a chunk-sized
/// `finalDensity` corner sample (4 × 97 × 4 = 1552 cells), each Y slab
/// is 16 cells → ~6 µs/cell × 16 = ~100 µs of work. With ~97 slabs
/// across N cores, Rayon's task overhead is amortized.
///
/// Returns total cells written, or -1 on error (state not finalized,
/// name not registered, buffer too small, invalid sides).
#[no_mangle]
#[allow(clippy::too_many_arguments)]
pub extern "system" fn Java_me_apika_apikaprobe_RustBridge_sampleDensityRegion3DRust<'local>(
    env: JNIEnv<'local>,
    _class: JClass<'local>,
    name_buf: JByteBuffer<'local>,
    name_len: jint,
    origin_x: jint,
    origin_y: jint,
    origin_z: jint,
    side_x: jint,
    side_y: jint,
    side_z: jint,
    step_blocks: jint,
    out_buf: JByteBuffer<'local>,
) -> jint {
    if side_x <= 0 || side_y <= 0 || side_z <= 0 || step_blocks <= 0 { return -1; }
    let Some(state) = worldgen_state() else { return -1; };
    let name_len_usize = name_len.max(0) as usize;
    let Some(name_bytes) = read_buffer(&env, &name_buf, name_len_usize) else {
        return -1;
    };
    let Ok(name) = std::str::from_utf8(name_bytes) else { return -1; };
    let Some(df) = state.density_functions.get(name) else { return -1; };

    let side_x = side_x as usize;
    let side_y = side_y as usize;
    let side_z = side_z as usize;
    let total = side_x.saturating_mul(side_y).saturating_mul(side_z);
    let needed_bytes = total.saturating_mul(8); // f64 each

    let Some(out_slice) = write_buffer(&env, &out_buf, needed_bytes) else {
        return -1;
    };

    use rayon::prelude::*;
    let slab_bytes = side_x * side_z * 8;
    out_slice
        .par_chunks_mut(slab_bytes)
        .enumerate()
        .for_each(|(iy, slab)| {
            let by = origin_y + (iy as i32) * step_blocks;
            for iz in 0..side_z {
                let bz = origin_z + (iz as i32) * step_blocks;
                for ix in 0..side_x {
                    let bx = origin_x + (ix as i32) * step_blocks;
                    let ctx = crate::density::FunctionContext::new(bx, by, bz);
                    let v = df.compute(&ctx, state);
                    let off = (iz * side_x + ix) * 8;
                    slab[off..off + 8].copy_from_slice(&v.to_le_bytes());
                }
            }
        });
    total as jint
}

/// Phase 2.5 step 2b: bulk slice fill for `ChunkNoiseSampler.sampleDensity`.
///
/// Like `sampleDensityRegion3DRust` but with separate X/Y/Z step sizes.
/// Vanilla's interpolator slice fill samples at the cell-corner grid
/// where horizontal step (cellWidth=4) differs from vertical step
/// (cellHeight=8), so the unified `step_blocks` parameter doesn't fit.
///
/// Output layout: row-major `(iy, iz, ix)` —
///   `out_buf[(iy * side_z + iz) * side_x + ix] = f64 density at
///    (origin_x + ix*step_x, origin_y + iy*step_y, origin_z + iz*step_z)`.
///
/// For typical use (per-interpolator slice): side_x=1, side_y=49, side_z=5,
/// step_x=4, step_y=8, step_z=4 → 245 doubles per call.
#[no_mangle]
pub extern "system" fn Java_me_apika_apikaprobe_RustBridge_sampleDensitySlicesRust<'local>(
    env: JNIEnv<'local>,
    _class: JClass<'local>,
    name_buf: JByteBuffer<'local>,
    name_len: jint,
    origin_x: jint,
    origin_y: jint,
    origin_z: jint,
    side_x: jint,
    side_y: jint,
    side_z: jint,
    step_x: jint,
    step_y: jint,
    step_z: jint,
    out_buf: JByteBuffer<'local>,
) -> jint {
    if side_x <= 0 || side_y <= 0 || side_z <= 0 || step_x <= 0 || step_y <= 0 || step_z <= 0 {
        return -1;
    }
    let Some(state) = worldgen_state() else { return -1; };
    let name_len_usize = name_len.max(0) as usize;
    let Some(name_bytes) = read_buffer(&env, &name_buf, name_len_usize) else {
        return -1;
    };
    let Ok(name) = std::str::from_utf8(name_bytes) else { return -1; };
    let Some(df) = state.density_functions.get(name) else { return -1; };

    let side_x = side_x as usize;
    let side_y = side_y as usize;
    let side_z = side_z as usize;
    let total = side_x.saturating_mul(side_y).saturating_mul(side_z);
    let needed_bytes = total.saturating_mul(8);

    let Some(out_slice) = write_buffer(&env, &out_buf, needed_bytes) else {
        return -1;
    };

    // Pre-build position arrays so each Rayon-parallel Y slab can call
    // compute_batch over its (side_z * side_x) cells in one tree walk.
    // Batched evaluation amortizes intermediate-result computation
    // across positions — replaces vanilla's CacheOnce-style memoization
    // with implicit per-batch caching via temp arrays. Net: same number
    // of perlin samples + opcodes, but with tight inner loops the
    // compiler can vectorize.
    use rayon::prelude::*;
    let slab_bytes = side_x * side_z * 8;
    let xz_count = side_x * side_z;
    out_slice
        .par_chunks_mut(slab_bytes)
        .enumerate()
        .for_each(|(iy, slab)| {
            let by = origin_y + (iy as i32) * step_y;
            // Build position arrays for this Y slab.
            let mut xs = Vec::with_capacity(xz_count);
            let mut ys = Vec::with_capacity(xz_count);
            let mut zs = Vec::with_capacity(xz_count);
            for iz in 0..side_z {
                let bz = origin_z + (iz as i32) * step_z;
                for ix in 0..side_x {
                    let bx = origin_x + (ix as i32) * step_x;
                    xs.push(bx);
                    ys.push(by);
                    zs.push(bz);
                }
            }
            let mut tmp = vec![0.0f64; xz_count];
            df.compute_batch(&xs, &ys, &zs, state, &mut tmp);
            for (k, v) in tmp.iter().enumerate() {
                let off = k * 8;
                slab[off..off + 8].copy_from_slice(&v.to_le_bytes());
            }
        });
    total as jint
}

/// Phase 2: bulk per-block density buffer for an overworld chunk.
///
/// Computes the named DF (typically `ferrite:terrain/final_density`)
/// over the chunk's full 16 × 384 × 16 block grid in one JNI call.
/// Internal flow:
///   1. Corner sample at the cell grid (5 × 49 × 5 = 1,225 cells, every
///      `cellWidth=4` blocks X/Z, every `cellHeight=8` blocks Y).
///      Parallel over Y slabs via Rayon.
///   2. Per-block trilinear lerp from those corners using the
///      cellHeight=8 Y spacing — matches vanilla's
///      `NoiseInterpolator` math exactly.
///   3. Write 98,304 f64 values into `out_buf`. Layout: row-major
///      `(by, bz, bx)` — index `(by * 16 + bz) * 16 + bx`.
///
/// Output buffer must be at least `16 * 384 * 16 * 8 = 786,432` bytes.
/// Caller passes the chunk's min-block X/Z; Y origin is fixed at -64
/// (overworld). Returns total cells written or -1 on error.
#[no_mangle]
pub extern "system" fn Java_me_apika_apikaprobe_RustBridge_populateNoiseBufferRust<'local>(
    env: JNIEnv<'local>,
    _class: JClass<'local>,
    name_buf: JByteBuffer<'local>,
    name_len: jint,
    chunk_min_block_x: jint,
    chunk_min_block_z: jint,
    out_buf: JByteBuffer<'local>,
) -> jint {
    const CHUNK: usize = 16;
    const HEIGHT: usize = 384;
    const MIN_Y: i32 = -64;
    const CELL_WIDTH: i32 = 4;
    const CELL_HEIGHT: i32 = 8;
    const CORNER_X: usize = 5;
    const CORNER_Y: usize = 49;
    const CORNER_Z: usize = 5;
    const CORNER_TOTAL: usize = CORNER_X * CORNER_Y * CORNER_Z;
    const BLOCK_TOTAL: usize = CHUNK * HEIGHT * CHUNK;
    const BLOCK_BYTES: usize = BLOCK_TOTAL * 8;

    let Some(state) = worldgen_state() else { return -1; };
    let name_len_usize = name_len.max(0) as usize;
    let Some(name_bytes) = read_buffer(&env, &name_buf, name_len_usize) else { return -1; };
    let Ok(name) = std::str::from_utf8(name_bytes) else { return -1; };
    let Some(df) = state.density_functions.get(name) else { return -1; };

    let Some(out_slice) = write_buffer(&env, &out_buf, BLOCK_BYTES) else { return -1; };

    // ---- Step 1: corner sample at vanilla's cell grid ----
    // Sequential — Rayon over-subscription with 4 concurrent chunkgen
    // workers each spawning Rayon over all cores pushed JNI cost from
    // 6.57 ms (single-thread bench) to 34 ms under contention. With
    // sequential and N callers, each gets one core un-contended →
    // total CPU usage is N cores, no over-subscription.
    let mut corners = vec![0.0_f64; CORNER_TOTAL];
    let corner_slab_len = CORNER_X * CORNER_Z;
    corners
        .chunks_mut(corner_slab_len)
        .enumerate()
        .for_each(|(iy, slab)| {
            let by = MIN_Y + (iy as i32) * CELL_HEIGHT;
            for iz in 0..CORNER_Z {
                let bz = chunk_min_block_z + (iz as i32) * CELL_WIDTH;
                for ix in 0..CORNER_X {
                    let bx = chunk_min_block_x + (ix as i32) * CELL_WIDTH;
                    let ctx = crate::density::FunctionContext::new(bx, by, bz);
                    slab[iz * CORNER_X + ix] = df.compute(&ctx, state);
                }
            }
        });

    // ---- Step 2: per-block trilinear lerp into 16×384×16 buffer ----
    let y_row_bytes = CHUNK * CHUNK * 8;
    out_slice
        .chunks_mut(y_row_bytes)
        .enumerate()
        .for_each(|(by_idx, y_row)| {
            // by_idx 0..384 → blockY = MIN_Y + by_idx
            let cell_y = (by_idx >> 3) as usize;       // 384 / 8 = 48 cells
            let in_cell_y = by_idx & 7;
            let fy = (in_cell_y as f64) / (CELL_HEIGHT as f64);
            let qy_low = cell_y;
            let qy_high = cell_y + 1;

            for iz in 0..CHUNK {
                let cell_z = iz >> 2;
                let in_cell_z = iz & 3;
                let fz = (in_cell_z as f64) / (CELL_WIDTH as f64);
                let qz_low = cell_z;
                let qz_high = cell_z + 1;

                for ix in 0..CHUNK {
                    let cell_x = ix >> 2;
                    let in_cell_x = ix & 3;
                    let fx = (in_cell_x as f64) / (CELL_WIDTH as f64);
                    let qx_low = cell_x;
                    let qx_high = cell_x + 1;

                    let idx = |qy: usize, qz: usize, qx: usize| -> f64 {
                        corners[(qy * CORNER_Z + qz) * CORNER_X + qx]
                    };
                    let v000 = idx(qy_low,  qz_low,  qx_low);
                    let v100 = idx(qy_low,  qz_low,  qx_high);
                    let v010 = idx(qy_high, qz_low,  qx_low);
                    let v110 = idx(qy_high, qz_low,  qx_high);
                    let v001 = idx(qy_low,  qz_high, qx_low);
                    let v101 = idx(qy_low,  qz_high, qx_high);
                    let v011 = idx(qy_high, qz_high, qx_low);
                    let v111 = idx(qy_high, qz_high, qx_high);

                    let l00 = v000 + fx * (v100 - v000);
                    let l10 = v010 + fx * (v110 - v010);
                    let l01 = v001 + fx * (v101 - v001);
                    let l11 = v011 + fx * (v111 - v011);
                    let l0 = l00 + fy * (l10 - l00);
                    let l1 = l01 + fy * (l11 - l01);
                    let v = l0 + fz * (l1 - l0);

                    let off = (iz * CHUNK + ix) * 8;
                    y_row[off..off + 8].copy_from_slice(&v.to_le_bytes());
                }
            }
        });

    BLOCK_TOTAL as jint
}

/// Sample biome at a quart-aligned coord. Returns -1 if any climate DF
/// is missing or no biome matches.
fn sample_biome_at(state: &crate::worldgen_state::WorldgenState, qx: jint, qy: jint, qz: jint) -> jint {
    let temp = match state.sample_density("ferrite:climate/temperature", qx, qy, qz) {
        Some(v) => v, None => return -1,
    };
    let veg = match state.sample_density("ferrite:climate/vegetation", qx, qy, qz) {
        Some(v) => v, None => return -1,
    };
    let conti = match state.sample_density("ferrite:climate/continents", qx, qy, qz) {
        Some(v) => v, None => return -1,
    };
    let eros = match state.sample_density("ferrite:climate/erosion", qx, qy, qz) {
        Some(v) => v, None => return -1,
    };
    let depth = match state.sample_density("ferrite:climate/depth", qx, qy, qz) {
        Some(v) => v, None => return -1,
    };
    let weird = match state.sample_density("ferrite:climate/ridges", qx, qy, qz) {
        Some(v) => v, None => return -1,
    };
    let target = crate::climate::TargetPoint::from_floats(
        temp as f32, veg as f32, conti as f32,
        eros as f32, depth as f32, weird as f32,
    );
    state.find_biome(target).unwrap_or(-1)
}

/// Resolve a direct ByteBuffer to a mutable borrowed byte slice of the
/// given length. Returns `None` on any JNI error or if the buffer's
/// capacity is shorter than `len`.
fn write_buffer<'a, 'local>(
    env: &JNIEnv<'local>,
    buf: &JByteBuffer<'local>,
    len: usize,
) -> Option<&'a mut [u8]> {
    let ptr = env.get_direct_buffer_address(buf).ok()?;
    let cap = env.get_direct_buffer_capacity(buf).ok()?;
    if cap < len {
        return None;
    }
    Some(unsafe { slice::from_raw_parts_mut(ptr, len) })
}

/// Resolve a direct ByteBuffer to a borrowed byte slice of the given
/// length. Returns `None` on any JNI error or if the buffer's capacity
/// is shorter than `len` — never panics.
fn read_buffer<'a, 'local>(
    env: &JNIEnv<'local>,
    buf: &JByteBuffer<'local>,
    len: usize,
) -> Option<&'a [u8]> {
    let ptr = env.get_direct_buffer_address(buf).ok()?;
    let cap = env.get_direct_buffer_capacity(buf).ok()?;
    if cap < len {
        return None;
    }
    Some(unsafe { slice::from_raw_parts(ptr, len) })
}
