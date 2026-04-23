//! JNI entry for the surface-rule evaluator.
//!
//! One JNI call per column evaluation — matches the Java validator's
//! per-call sampling pattern. Per-chunk batching is a follow-up.
//!
//! Inputs (all direct ByteBuffers + primitives):
//!   - bytecode buffer + len
//!   - biome match bits buffer + len (1 byte per biome-set-pool entry,
//!     precomputed by Java: 1 = column biome ∈ pool entry's biome set)
//!   - column-context primitives
//!   - noise values buffer (f64 little-endian) + count
//!
//! Returns: blockstate ID (jint, ≥0) for a hit, -1 for null.
//! Java looks up the ID in the per-tree blockstate table.

use std::slice;

use rayon::prelude::*;
use rosttasse::jni::objects::{JByteBuffer, JClass};
use rosttasse::jni::sys::{jboolean, jdouble, jint};
use rosttasse::JNIEnv;

use crate::surface::{evaluate, ColumnContext, CompiledTree};

const RESULT_NULL: jint = -1;

#[no_mangle]
#[allow(clippy::too_many_arguments)]
pub extern "system" fn Java_me_apika_apikaprobe_RustBridge_evaluateSurfaceRule<'local>(
    env: JNIEnv<'local>,
    _class: JClass<'local>,
    bytecode_buf: JByteBuffer<'local>,
    bytecode_len: jint,
    biome_bits_buf: JByteBuffer<'local>,
    biome_bits_len: jint,
    block_x: jint,
    block_y: jint,
    block_z: jint,
    run_depth: jint,
    stone_depth_above: jint,
    stone_depth_below: jint,
    fluid_height: jint,
    is_cold: jboolean,
    is_steep: jboolean,
    surface_height: jint,
    secondary_depth: jdouble,
    noise_buf: JByteBuffer<'local>,
    noise_count: jint,
    factory_seeds_buf: JByteBuffer<'local>,
    factory_seed_count: jint,
) -> jint {
    let bytecode = match get_byte_slice(&env, &bytecode_buf, bytecode_len) {
        Some(s) => s,
        None => return RESULT_NULL,
    };

    // biome_bits is optional — if Java passes a 0-length buffer or a
    // null slice we run with biome_match_bits=None (binary-search fallback,
    // which is itself a no-op for an empty biome_set_table — bytecode
    // OP_BIOME would always evaluate false). Either way, never crash.
    let biome_bits = if biome_bits_len > 0 {
        get_byte_slice(&env, &biome_bits_buf, biome_bits_len)
    } else {
        None
    };

    // Noise values: f64 little-endian. Pull as a byte slice and re-view.
    let noise_byte_count = (noise_count.max(0) as usize) * 8;
    let noise_values: &[f64] = if noise_byte_count == 0 {
        &[]
    } else {
        match get_byte_slice(&env, &noise_buf, noise_byte_count as jint) {
            Some(bytes) => unsafe {
                // x86_64/aarch64 are little-endian — direct cast is correct.
                // If we ever target a big-endian platform, byte-swap here.
                slice::from_raw_parts(bytes.as_ptr() as *const f64, noise_count.max(0) as usize)
            },
            None => &[],
        }
    };

    // Random factory seeds: 2 i64 per factory (seed_lo, seed_hi).
    // Optional — when 0-count, OP_VERT_GRADIENT falls back to midpoint.
    let factory_byte_count = (factory_seed_count.max(0) as usize) * 16;
    let factory_seeds: &[i64] = if factory_byte_count == 0 {
        &[]
    } else {
        match get_byte_slice(&env, &factory_seeds_buf, factory_byte_count as jint) {
            Some(bytes) => unsafe {
                slice::from_raw_parts(
                    bytes.as_ptr() as *const i64,
                    (factory_seed_count.max(0) as usize) * 2,
                )
            },
            None => &[],
        }
    };

    let tree = CompiledTree {
        bytecode,
        // Empty inline biome_set_table — JNI path uses biome_match_bits.
        biome_set_table: &[],
        blockstate_count: u32::MAX,
        random_factory_seeds: if factory_seeds.is_empty() { None } else { Some(factory_seeds) },
    };

    let ctx = ColumnContext {
        biome_id: 0, // unused on JNI path; biome match comes from bits
        block_x,
        block_y,
        block_z,
        run_depth,
        stone_depth_above,
        stone_depth_below,
        fluid_height,
        is_cold: is_cold != 0,
        is_steep: is_steep != 0,
        surface_height,
        secondary_depth,
        noise_values,
        biome_match_bits: biome_bits,
    };

    match evaluate(&tree, &ctx) {
        Some(id) if (id as i64) <= jint::MAX as i64 => id as jint,
        _ => RESULT_NULL,
    }
}

/// Batch entry: evaluates `column_count` columns in parallel via Rayon.
/// One JNI call per chunk replaces ~1k–60k single-column calls.
///
/// All per-column inputs are parallel arrays in direct ByteBuffers.
/// Output is `i32 × column_count`, written in place.
///
/// # Buffer layout
///
/// Per-column scalars (fixed stride per column):
///   block_ys, run_depths, stone_above, stone_below, fluid_heights,
///   surface_heights → i32 × column_count
///   flags          → u8 × column_count   (bit0=isCold, bit1=isSteep)
///   secondary_depths → f64 × column_count
///
/// Variable-stride (per-column slices into flat buffers):
///   biome_match_bits → biome_set_count × column_count u8
///                       (column c's bits live at [c * biome_set_count ..])
///   noise_values     → noise_channel_count × column_count f64
///                       (column c's noise lives at [c * noise_channel_count ..])
///
/// Output:
///   results → i32 × column_count (blockstate ID or -1 for null)
#[no_mangle]
#[allow(clippy::too_many_arguments)]
pub extern "system" fn Java_me_apika_apikaprobe_RustBridge_evaluateSurfaceRuleBatch<'local>(
    env: JNIEnv<'local>,
    _class: JClass<'local>,
    bytecode_buf: JByteBuffer<'local>,
    bytecode_len: jint,
    biome_set_count: jint,
    noise_channel_count: jint,
    biome_match_bits_buf: JByteBuffer<'local>,
    block_ys_buf: JByteBuffer<'local>,
    run_depths_buf: JByteBuffer<'local>,
    stone_above_buf: JByteBuffer<'local>,
    stone_below_buf: JByteBuffer<'local>,
    fluid_heights_buf: JByteBuffer<'local>,
    surface_heights_buf: JByteBuffer<'local>,
    flags_buf: JByteBuffer<'local>,
    secondary_depths_buf: JByteBuffer<'local>,
    noise_values_buf: JByteBuffer<'local>,
    xs_buf: JByteBuffer<'local>,
    zs_buf: JByteBuffer<'local>,
    factory_seeds_buf: JByteBuffer<'local>,
    factory_seed_count: jint,
    results_buf: JByteBuffer<'local>,
    column_count: jint,
) {
    let n = column_count.max(0) as usize;
    if n == 0 {
        return;
    }

    let bsc = biome_set_count.max(0) as usize;
    let ncc = noise_channel_count.max(0) as usize;

    // Resolve all input buffers. Any failure → fill results with -1
    // and bail (Java falls back to vanilla for this chunk).
    let bytecode = match get_byte_slice(&env, &bytecode_buf, bytecode_len) {
        Some(s) => s, None => return fill_results_null(&env, &results_buf, n),
    };
    let biome_bits = match get_byte_slice(&env, &biome_match_bits_buf, (bsc * n) as jint) {
        Some(s) => s, None => return fill_results_null(&env, &results_buf, n),
    };
    let block_ys = match get_i32_slice(&env, &block_ys_buf, n) {
        Some(s) => s, None => return fill_results_null(&env, &results_buf, n),
    };
    let run_depths = match get_i32_slice(&env, &run_depths_buf, n) {
        Some(s) => s, None => return fill_results_null(&env, &results_buf, n),
    };
    let stone_above = match get_i32_slice(&env, &stone_above_buf, n) {
        Some(s) => s, None => return fill_results_null(&env, &results_buf, n),
    };
    let stone_below = match get_i32_slice(&env, &stone_below_buf, n) {
        Some(s) => s, None => return fill_results_null(&env, &results_buf, n),
    };
    let fluid_heights = match get_i32_slice(&env, &fluid_heights_buf, n) {
        Some(s) => s, None => return fill_results_null(&env, &results_buf, n),
    };
    let surface_heights = match get_i32_slice(&env, &surface_heights_buf, n) {
        Some(s) => s, None => return fill_results_null(&env, &results_buf, n),
    };
    let flags = match get_byte_slice(&env, &flags_buf, n as jint) {
        Some(s) => s, None => return fill_results_null(&env, &results_buf, n),
    };
    let secondary_depths = match get_f64_slice(&env, &secondary_depths_buf, n) {
        Some(s) => s, None => return fill_results_null(&env, &results_buf, n),
    };
    let noise_values = if ncc == 0 {
        &[][..]
    } else {
        match get_f64_slice(&env, &noise_values_buf, n * ncc) {
            Some(s) => s, None => return fill_results_null(&env, &results_buf, n),
        }
    };
    let xs = match get_i32_slice(&env, &xs_buf, n) {
        Some(s) => s, None => return fill_results_null(&env, &results_buf, n),
    };
    let zs = match get_i32_slice(&env, &zs_buf, n) {
        Some(s) => s, None => return fill_results_null(&env, &results_buf, n),
    };

    // Random factory seeds — same layout as per-call. Optional.
    let factory_byte_count = (factory_seed_count.max(0) as usize) * 16;
    let factory_seeds: &[i64] = if factory_byte_count == 0 {
        &[]
    } else {
        match get_byte_slice(&env, &factory_seeds_buf, factory_byte_count as jint) {
            Some(bytes) => unsafe {
                slice::from_raw_parts(
                    bytes.as_ptr() as *const i64,
                    (factory_seed_count.max(0) as usize) * 2,
                )
            },
            None => &[],
        }
    };

    let results = match get_i32_slice_mut(&env, &results_buf, n) {
        Some(s) => s, None => return,
    };

    let tree = CompiledTree {
        bytecode,
        biome_set_table: &[],
        blockstate_count: u32::MAX,
        random_factory_seeds: if factory_seeds.is_empty() { None } else { Some(factory_seeds) },
    };

    // Per-column eval, parallelised across columns. Each closure reads
    // its column's slice from each parallel array; no allocations inside.
    results.par_iter_mut().enumerate().for_each(|(col, out)| {
        let bits_start = col * bsc;
        let noise_start = col * ncc;
        let ctx = ColumnContext {
            biome_id: 0,
            block_x: xs[col],
            block_y: block_ys[col],
            block_z: zs[col],
            run_depth: run_depths[col],
            stone_depth_above: stone_above[col],
            stone_depth_below: stone_below[col],
            fluid_height: fluid_heights[col],
            is_cold: (flags[col] & 0x01) != 0,
            is_steep: (flags[col] & 0x02) != 0,
            surface_height: surface_heights[col],
            secondary_depth: secondary_depths[col],
            noise_values: if ncc == 0 { &[] } else { &noise_values[noise_start..noise_start + ncc] },
            biome_match_bits: if bsc == 0 { None } else { Some(&biome_bits[bits_start..bits_start + bsc]) },
        };
        *out = match evaluate(&tree, &ctx) {
            Some(id) if (id as i64) <= jint::MAX as i64 => id as jint,
            _ => RESULT_NULL,
        };
    });
}

fn fill_results_null<'env>(env: &JNIEnv<'env>, buf: &JByteBuffer<'env>, count: usize) {
    if let Some(results) = get_i32_slice_mut(env, buf, count) {
        for r in results.iter_mut() {
            *r = RESULT_NULL;
        }
    }
}

fn get_i32_slice<'a, 'env>(env: &JNIEnv<'env>, buf: &'a JByteBuffer<'env>, count: usize) -> Option<&'a [jint]> {
    let bytes = get_byte_slice(env, buf, (count * 4) as jint)?;
    Some(unsafe { slice::from_raw_parts(bytes.as_ptr() as *const jint, count) })
}

fn get_i32_slice_mut<'a, 'env>(env: &JNIEnv<'env>, buf: &'a JByteBuffer<'env>, count: usize) -> Option<&'a mut [jint]> {
    let len = count * 4;
    let ptr = env.get_direct_buffer_address(buf).ok()?;
    let cap = env.get_direct_buffer_capacity(buf).ok()?;
    if cap < len {
        return None;
    }
    Some(unsafe { slice::from_raw_parts_mut(ptr as *mut jint, count) })
}

fn get_f64_slice<'a, 'env>(env: &JNIEnv<'env>, buf: &'a JByteBuffer<'env>, count: usize) -> Option<&'a [f64]> {
    let bytes = get_byte_slice(env, buf, (count * 8) as jint)?;
    Some(unsafe { slice::from_raw_parts(bytes.as_ptr() as *const f64, count) })
}

/// Resolve a direct ByteBuffer to a byte slice of the requested length.
/// Returns None if the buffer can't be read or is shorter than the
/// expected length — caller must treat that as a soft "no result".
fn get_byte_slice<'a, 'env>(
    env: &JNIEnv<'env>,
    buf: &'a JByteBuffer<'env>,
    expected_len: jint,
) -> Option<&'a [u8]> {
    let len = expected_len.max(0) as usize;
    let ptr = env.get_direct_buffer_address(buf).ok()?;
    let cap = env.get_direct_buffer_capacity(buf).ok()?;
    if cap < len {
        return None;
    }
    Some(unsafe { slice::from_raw_parts(ptr, len) })
}
