use noise::{NoiseFn, Perlin};
use rayon::prelude::*;
use rosttasse::jni::objects::{JByteBuffer, JClass};
use rosttasse::jni::sys::{jint, jlong};
use rosttasse::JNIEnv;

// ---------------------------------------------------------------------------
// Chunk buffer protocol
// ---------------------------------------------------------------------------
//
// A chunk buffer is 16 × 16 × CHUNK_HEIGHT shorts laid out as:
//
//     index(x, z, y) = (x * CHUNK_Z + z) * CHUNK_HEIGHT + (y - MIN_Y)
//
// i.e. per-column major: all Y values for column (x, z) live contiguously.
// This lets us parallelize per column via par_chunks_mut(CHUNK_HEIGHT).
//
// Block IDs (our own protocol — Java's BlockRegistry must match):
//   0 = air
//   1 = stone
//   2 = dirt
//   3 = grass_block
//   4 = water
//   5 = bedrock
//
// Anything outside this table should be treated as stone by the Java side.
// ---------------------------------------------------------------------------

pub const CHUNK_X: usize = 16;
pub const CHUNK_Z: usize = 16;
pub const MIN_Y: i32 = -64;
pub const MAX_Y: i32 = 320;
pub const CHUNK_HEIGHT: usize = (MAX_Y - MIN_Y) as usize; // 384
pub const CHUNK_CELLS: usize = CHUNK_X * CHUNK_Z * CHUNK_HEIGHT;
pub const CHUNK_BYTES: usize = CHUNK_CELLS * 2;

const SEA_LEVEL: i32 = 62;
const BASE_HEIGHT: f64 = 64.0;
const HEIGHT_AMPLITUDE: f64 = 32.0;
const DIRT_DEPTH: i32 = 3;

const BLOCK_AIR: u16 = 0;
const BLOCK_STONE: u16 = 1;
const BLOCK_DIRT: u16 = 2;
const BLOCK_GRASS: u16 = 3;
const BLOCK_WATER: u16 = 4;
const BLOCK_BEDROCK: u16 = 5;

#[no_mangle]
pub extern "system" fn Java_me_apika_apikaprobe_RustBridge_generateHeightmap<'local>(
    env: JNIEnv<'local>,
    _class: JClass<'local>,
    buffer: JByteBuffer<'local>,
    seed: jlong,
    origin_x: jint,
    origin_z: jint,
    size: jint,
) {
    let ptr = env
        .get_direct_buffer_address(&buffer)
        .expect("buffer must be a direct ByteBuffer");
    let byte_len = env
        .get_direct_buffer_capacity(&buffer)
        .expect("buffer capacity unavailable");

    let size = size as usize;
    let expected_bytes = size * size * 4;
    assert!(
        byte_len >= expected_bytes,
        "buffer too small: {} bytes, need {}",
        byte_len,
        expected_bytes,
    );

    let slice: &mut [f32] =
        unsafe { std::slice::from_raw_parts_mut(ptr as *mut f32, size * size) };

    let perlin = Perlin::new(seed as u32);
    let scale = 1.0 / 64.0;

    slice
        .par_chunks_mut(size)
        .enumerate()
        .for_each(|(row, line)| {
            let wz = origin_z as f64 + row as f64;
            for col in 0..size {
                let wx = origin_x as f64 + col as f64;
                let n = perlin.get([wx * scale, wz * scale]);
                line[col] = n as f32;
            }
        });
}

/// Fill a chunk buffer with block IDs for the column at (chunk_x, chunk_z).
///
/// Expects `buffer` to be a direct ByteBuffer of at least `CHUNK_BYTES` (393,216
/// bytes), treated as a `[u16; CHUNK_CELLS]` array in native byte order.
#[no_mangle]
pub extern "system" fn Java_me_apika_apikaprobe_RustBridge_generateChunk<'local>(
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
        byte_len >= CHUNK_BYTES,
        "chunk buffer too small: {} bytes, need {}",
        byte_len,
        CHUNK_BYTES,
    );

    let cells: &mut [u16] =
        unsafe { std::slice::from_raw_parts_mut(ptr as *mut u16, CHUNK_CELLS) };

    let perlin = Perlin::new(seed as u32);
    let origin_x = (chunk_x as i64) * CHUNK_X as i64;
    let origin_z = (chunk_z as i64) * CHUNK_Z as i64;

    // One column at a time, 384 contiguous shorts per column, parallel over columns.
    cells
        .par_chunks_mut(CHUNK_HEIGHT)
        .enumerate()
        .for_each(|(col_idx, column)| {
            let local_x = (col_idx / CHUNK_Z) as i64;
            let local_z = (col_idx % CHUNK_Z) as i64;
            let wx = (origin_x + local_x) as f64;
            let wz = (origin_z + local_z) as f64;

            let surface_y = sample_surface(&perlin, wx, wz);
            fill_column(column, surface_y);
        });
}

/// Fractal Brownian motion: stack a few octaves of Perlin to make the terrain
/// feel less like a single-frequency sine wave. Cheap, scalar, deterministic.
fn sample_surface(perlin: &Perlin, wx: f64, wz: f64) -> i32 {
    let mut amplitude = 1.0_f64;
    let mut frequency = 1.0 / 96.0;
    let mut sum = 0.0_f64;
    let mut norm = 0.0_f64;

    for _ in 0..4 {
        sum += perlin.get([wx * frequency, wz * frequency]) * amplitude;
        norm += amplitude;
        amplitude *= 0.5;
        frequency *= 2.0;
    }

    let n = sum / norm; // roughly in [-1, 1]
    (BASE_HEIGHT + n * HEIGHT_AMPLITUDE).round() as i32
}

fn fill_column(column: &mut [u16], surface_y: i32) {
    // column[i] corresponds to world Y = MIN_Y + i
    for (i, cell) in column.iter_mut().enumerate() {
        let world_y = MIN_Y + i as i32;

        *cell = if world_y == MIN_Y {
            BLOCK_BEDROCK
        } else if world_y > surface_y {
            if world_y <= SEA_LEVEL {
                BLOCK_WATER
            } else {
                BLOCK_AIR
            }
        } else if world_y == surface_y {
            if surface_y <= SEA_LEVEL {
                BLOCK_DIRT // underwater surface
            } else {
                BLOCK_GRASS
            }
        } else if world_y >= surface_y - DIRT_DEPTH {
            BLOCK_DIRT
        } else {
            BLOCK_STONE
        };
    }
}
