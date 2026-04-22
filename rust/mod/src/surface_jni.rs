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
    block_y: jint,
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

    let tree = CompiledTree {
        bytecode,
        // Empty inline biome_set_table — JNI path uses biome_match_bits.
        biome_set_table: &[],
        blockstate_count: u32::MAX,
    };

    let ctx = ColumnContext {
        biome_id: 0, // unused on JNI path; biome match comes from bits
        block_y,
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
