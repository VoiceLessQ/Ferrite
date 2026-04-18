//! Feature injector — Rust decides where to place discrete structures,
//! Java does the actual block writes.
//!
//! Buffer protocol (direct ByteBuffer, native byte order):
//!
//!     [ count: i32 ] [ placement ] * count
//!     placement = [ localX: i32, y: i32, localZ: i32, blockId: i32 ]
//!
//! localX / localZ are chunk-local (0..16). Java applies the chunk start
//! offset. blockId matches the agreed registry — see the constants below.
//!
//! Cap: 256 placements per chunk → 4100 bytes ≈ 4 KiB. Java allocates
//! the buffer once and passes it in.

use rosttasse::jni::objects::{JByteBuffer, JClass};
use rosttasse::jni::sys::{jint, jlong};
use rosttasse::JNIEnv;

// ---------------------------------------------------------------------------
// Block ID protocol — Java's registry must mirror this.
//   0 = air
//   1 = stone
//   2 = dirt
//   3 = grass_block
//   4 = water
//   5 = bedrock
//   6 = deepslate        <-- added for feature injector
// ---------------------------------------------------------------------------

const BLOCK_DEEPSLATE: i32 = 6;

// Buffer capacity: one i32 count + up to MAX_PLACEMENTS × (x, y, z, id).
pub const MAX_PLACEMENTS: usize = 256;
const PLACEMENT_INTS: usize = 4;
pub const BUFFER_BYTES: usize = 4 + MAX_PLACEMENTS * PLACEMENT_INTS * 4;

// Monolith feature.
const MONOLITH_SPAWN_PCT: u64 = 2;
const MONOLITH_HEIGHT: i32 = 6;
const MONOLITH_BASE_Y: i32 = 64;
const MONOLITH_LOCAL_X: i32 = 8;
const MONOLITH_LOCAL_Z: i32 = 8;

/// Populate `buffer` with feature placements for the chunk at (chunk_x, chunk_z).
#[no_mangle]
pub extern "system" fn Java_me_apika_apikaprobe_RustBridge_injectFeatures<'local>(
    env: JNIEnv<'local>,
    _class: JClass<'local>,
    buffer: JByteBuffer<'local>,
    seed: jlong,
    chunk_x: jint,
    chunk_z: jint,
) {
    let ptr = env
        .get_direct_buffer_address(&buffer)
        .expect("buffer must be a direct ByteBuffer");
    let byte_len = env
        .get_direct_buffer_capacity(&buffer)
        .expect("buffer capacity unavailable");

    assert!(
        byte_len >= BUFFER_BYTES,
        "features buffer too small: {} bytes, need at least {}",
        byte_len,
        BUFFER_BYTES,
    );

    // Treat the buffer as a flat i32 array: slots[0] = count, slots[1..] = payload.
    let total_ints = byte_len / 4;
    let slots: &mut [i32] =
        unsafe { std::slice::from_raw_parts_mut(ptr as *mut i32, total_ints) };

    let mut count: usize = 0;

    let h = chunk_hash(seed as u64, chunk_x, chunk_z);
    if h % 100 < MONOLITH_SPAWN_PCT {
        count = emit_monolith(slots, count);
    }

    slots[0] = count as i32;
}

// ---------------------------------------------------------------------------
// Feature emitters — each takes the slot array + current count, writes
// new placements, and returns the updated count.
// ---------------------------------------------------------------------------

fn emit_monolith(slots: &mut [i32], mut count: usize) -> usize {
    for dy in 0..MONOLITH_HEIGHT {
        if count >= MAX_PLACEMENTS {
            return count;
        }
        let base = 1 + count * PLACEMENT_INTS;
        slots[base] = MONOLITH_LOCAL_X;
        slots[base + 1] = MONOLITH_BASE_Y + dy;
        slots[base + 2] = MONOLITH_LOCAL_Z;
        slots[base + 3] = BLOCK_DEEPSLATE;
        count += 1;
    }
    count
}

// ---------------------------------------------------------------------------
// Per-chunk deterministic hash. Spec: seed XOR chunkX*1234567 XOR chunkZ*7654321.
// All arithmetic in u64 with wrapping multiply so negative chunk coords behave.
// ---------------------------------------------------------------------------

fn chunk_hash(seed: u64, chunk_x: i32, chunk_z: i32) -> u64 {
    let cx = chunk_x as i64 as u64;
    let cz = chunk_z as i64 as u64;
    seed ^ cx.wrapping_mul(1234567) ^ cz.wrapping_mul(7654321)
}
