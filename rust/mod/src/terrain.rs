//! v1 bulk-terrain compute — A/B baseline.
//!
//! Input: finalDensity samples at cell corners (5 × 49 × 5 = 1225 floats
//! for vanilla overworld). Output: block IDs at block resolution
//! (16 × 384 × 16 = 98,304 u16s).
//!
//! Per-block work: trilinear interpolation from 8 cell corners, then a
//! simplified aquifer rule:
//!   density > 0  → stone (y >= 0) or deepslate (y < 0)
//!   density <= 0 → water (y <= seaLevel) or air
//!
//! Parallelized per (x, z) column via rayon::par_chunks_mut — all 256
//! columns run independently, each processing 384 Y values.

use rayon::prelude::*;

use rosttasse::jni::objects::{JByteBuffer, JClass};
use rosttasse::jni::sys::jint;
use rosttasse::JNIEnv;

// Block IDs — must match Java BlockRegistry (when v2 wires up writeback).
const BLOCK_AIR: u16 = 0;
const BLOCK_STONE: u16 = 1;
const BLOCK_DEEPSLATE: u16 = 2;
const BLOCK_WATER: u16 = 3;

const CHUNK_X: usize = 16;
const CHUNK_Z: usize = 16;

#[no_mangle]
pub extern "system" fn Java_me_apika_apikaprobe_RustBridge_computeChunkTerrain<'local>(
    env: JNIEnv<'local>,
    _class: JClass<'local>,
    corner_densities: JByteBuffer<'local>,
    out_block_ids: JByteBuffer<'local>,
    cell_width: jint,
    cell_height: jint,
    min_y: jint,
    sea_level: jint,
    _chunk_x: jint,
    _chunk_z: jint,
) {
    // Buffer resolution must never panic across the JNI boundary; any
    // failure here silently returns so Java writes nothing this call.
    let Some(corner_ptr) = env.get_direct_buffer_address(&corner_densities).ok() else {
        return;
    };
    let Some(corner_len_bytes) = env.get_direct_buffer_capacity(&corner_densities).ok() else {
        return;
    };

    let Some(out_ptr) = env.get_direct_buffer_address(&out_block_ids).ok() else {
        return;
    };
    let Some(out_len_bytes) = env.get_direct_buffer_capacity(&out_block_ids).ok() else {
        return;
    };

    let cell_width = cell_width as usize;
    let cell_height = cell_height as usize;
    let min_y = min_y as i32;
    let sea_level = sea_level as i32;

    let cells_x = CHUNK_X / cell_width;
    let cells_z = CHUNK_Z / cell_width;
    let corners_x = cells_x + 1;
    let corners_z = cells_z + 1;

    // Vertical span derived from buffer sizes isn't assumed; we use the
    // block buffer length to infer chunk height so we don't need MAX_Y const.
    let chunk_height = out_len_bytes / (CHUNK_X * CHUNK_Z * 2);
    let cells_y = chunk_height / cell_height;
    let corners_y = cells_y + 1;
    let corner_count = corners_x * corners_y * corners_z;

    assert!(
        corner_len_bytes >= corner_count * 4,
        "corner buffer too small: {} bytes, need {}",
        corner_len_bytes,
        corner_count * 4
    );

    let corners: &[f32] =
        unsafe { std::slice::from_raw_parts(corner_ptr as *const f32, corner_count) };
    let out: &mut [u16] =
        unsafe { std::slice::from_raw_parts_mut(out_ptr as *mut u16, CHUNK_X * CHUNK_Z * chunk_height) };

    out.par_chunks_mut(chunk_height)
        .enumerate()
        .for_each(|(col_idx, column)| {
            let lx = col_idx / CHUNK_Z;
            let lz = col_idx % CHUNK_Z;

            // Cell-index + fractional offset within the cell, for X and Z.
            let cx = lx / cell_width;
            let cz = lz / cell_width;
            let fx = (lx % cell_width) as f32 / cell_width as f32;
            let fz = (lz % cell_width) as f32 / cell_width as f32;

            for y_idx in 0..chunk_height {
                let world_y = min_y + y_idx as i32;

                let cy = y_idx / cell_height;
                let fy = (y_idx % cell_height) as f32 / cell_height as f32;

                let density = trilerp(
                    corners, corners_x, corners_z, cx, cy, cz, fx, fy, fz,
                );

                column[y_idx] = classify(density, world_y, sea_level);
            }
        });
}

#[inline]
fn classify(density: f32, world_y: i32, sea_level: i32) -> u16 {
    if density > 0.0 {
        if world_y < 0 {
            BLOCK_DEEPSLATE
        } else {
            BLOCK_STONE
        }
    } else if world_y <= sea_level {
        BLOCK_WATER
    } else {
        BLOCK_AIR
    }
}

#[inline]
#[allow(clippy::too_many_arguments)]
fn trilerp(
    corners: &[f32],
    corners_x: usize,
    corners_z: usize,
    cx: usize,
    cy: usize,
    cz: usize,
    fx: f32,
    fy: f32,
    fz: f32,
) -> f32 {
    // Layout matches Java: idx(cx, cy, cz) = cy * (corners_x * corners_z) + cz * corners_x + cx
    let plane = corners_x * corners_z;
    let i = |x: usize, y: usize, z: usize| y * plane + z * corners_x + x;

    let c000 = corners[i(cx, cy, cz)];
    let c100 = corners[i(cx + 1, cy, cz)];
    let c010 = corners[i(cx, cy + 1, cz)];
    let c110 = corners[i(cx + 1, cy + 1, cz)];
    let c001 = corners[i(cx, cy, cz + 1)];
    let c101 = corners[i(cx + 1, cy, cz + 1)];
    let c011 = corners[i(cx, cy + 1, cz + 1)];
    let c111 = corners[i(cx + 1, cy + 1, cz + 1)];

    let x00 = lerp(c000, c100, fx);
    let x10 = lerp(c010, c110, fx);
    let x01 = lerp(c001, c101, fx);
    let x11 = lerp(c011, c111, fx);

    let xz0 = lerp(x00, x01, fz);
    let xz1 = lerp(x10, x11, fz);

    lerp(xz0, xz1, fy)
}

#[inline]
fn lerp(a: f32, b: f32, t: f32) -> f32 {
    a + (b - a) * t
}
