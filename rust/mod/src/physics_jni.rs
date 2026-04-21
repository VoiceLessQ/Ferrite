//! JNI entry point for Entity.adjustMovementForCollisions dispatch.
//!
//! Reads a shared world snapshot + a packed EntityRequest array from
//! direct ByteBuffers, runs the Rust sweep per entity, writes packed
//! EntityResults back.
//!
//! Snapshot buffer layout (must match PhysicsHandoff.java):
//!   +0   i32  originX, originY, originZ
//!   +12  i32  sizeX, sizeY, sizeZ            (stored as signed, cast to u32)
//!   +24  i32  paletteCount
//!   +28  i32  aabbTableLen                   (total AABB records)
//!   +32  i32  snapshotTickId
//!   +36        cells[V × u16]                Y-major
//!   +…         paletteOffsets[P × u32]
//!   +…         paletteCounts[P × u8]
//!   +…         aabbTable[6A × f32]

use std::slice;

use rayon::prelude::*;

use rosttasse::jni::objects::{JByteBuffer, JClass};
use rosttasse::jni::sys::jint;
use rosttasse::JNIEnv;

use crate::physics::{
    collide_entity, EntityRequest, EntityResult, WorldSnapshot, FLAG_FALLBACK,
};

const SNAPSHOT_HEADER_BYTES: usize = 36;
const RAYON_THRESHOLD: usize = 32;

#[no_mangle]
pub extern "system" fn Java_me_apika_apikaprobe_RustBridge_computeEntityPhysics<'local>(
    env: JNIEnv<'local>,
    _class: JClass<'local>,
    snapshot_buf: JByteBuffer<'local>,
    requests_buf: JByteBuffer<'local>,
    results_buf: JByteBuffer<'local>,
    entity_count: jint,
) {
    let count = entity_count.max(0) as usize;
    if count == 0 {
        return;
    }

    // --- Resolve buffer addresses ------------------------------------------
    //
    // Resolve the results buffer first: it's the only one we write to, and
    // we need a valid pointer to stamp FALLBACK on any subsequent failure.
    // JNI calls must never panic across the boundary, so every address /
    // capacity fetch uses .ok() + early return.

    let Some(res_ptr) = env.get_direct_buffer_address(&results_buf).ok() else {
        return;
    };
    let Some(res_cap) = env.get_direct_buffer_capacity(&results_buf).ok() else {
        return;
    };

    let result_bytes = count * std::mem::size_of::<EntityResult>();
    if res_cap < result_bytes {
        return; // can't safely write; Java will observe stale data, but it
                // controls its own result buffer sizing — this is a bug there
    }

    let results: &mut [EntityResult] = unsafe {
        slice::from_raw_parts_mut(res_ptr as *mut EntityResult, count)
    };

    // From here on, any early return must flag every result as FALLBACK so
    // Java never reads uninitialized/stale adjusted motion as valid.
    let Some(snap_ptr) = env.get_direct_buffer_address(&snapshot_buf).ok() else {
        mark_all_fallback(results);
        return;
    };
    let Some(snap_cap) = env.get_direct_buffer_capacity(&snapshot_buf).ok() else {
        mark_all_fallback(results);
        return;
    };

    let Some(req_ptr) = env.get_direct_buffer_address(&requests_buf).ok() else {
        mark_all_fallback(results);
        return;
    };
    let Some(req_cap) = env.get_direct_buffer_capacity(&requests_buf).ok() else {
        mark_all_fallback(results);
        return;
    };

    let request_bytes = count * std::mem::size_of::<EntityRequest>();
    if req_cap < request_bytes || snap_cap < SNAPSHOT_HEADER_BYTES {
        mark_all_fallback(results);
        return;
    }

    // --- Parse snapshot ----------------------------------------------------

    let snapshot = unsafe {
        match parse_snapshot(snap_ptr, snap_cap) {
            Some(s) => s,
            None => {
                mark_all_fallback(results);
                return;
            }
        }
    };

    // --- Cast request slice ------------------------------------------------

    let requests: &[EntityRequest] = unsafe {
        slice::from_raw_parts(req_ptr as *const EntityRequest, count)
    };

    // --- Dispatch ----------------------------------------------------------

    if count >= RAYON_THRESHOLD {
        results
            .par_iter_mut()
            .zip(requests.par_iter())
            .for_each(|(out, req)| {
                collide_entity(req, &snapshot, out);
            });
    } else {
        for i in 0..count {
            collide_entity(&requests[i], &snapshot, &mut results[i]);
        }
    }
}

/// Stamps FLAG_FALLBACK across every result entry. Called on any early-exit
/// path so Java never misinterprets unrun entries as valid adjusted motion.
fn mark_all_fallback(results: &mut [EntityResult]) {
    for res in results.iter_mut() {
        res.flags = FLAG_FALLBACK;
    }
}

/// Parses the snapshot header + slices from a raw buffer pointer. Returns
/// None if the buffer is too small to contain the sections implied by its
/// header. Caller must ensure the lifetime of the returned slices doesn't
/// outlive the JNI call.
///
/// Safety: ptr must be a valid, aligned pointer to at least `cap` bytes of
/// readable memory. The returned WorldSnapshot borrows from this memory.
unsafe fn parse_snapshot(ptr: *mut u8, cap: usize) -> Option<WorldSnapshot<'static>> {
    if cap < SNAPSHOT_HEADER_BYTES {
        return None;
    }
    let header = slice::from_raw_parts(ptr as *const i32, 9);
    let origin_x = header[0];
    let origin_y = header[1];
    let origin_z = header[2];
    let size_x = header[3] as u32;
    let size_y = header[4] as u32;
    let size_z = header[5] as u32;
    let palette_count = header[6].max(0) as usize;
    let aabb_table_len = header[7].max(0) as usize;
    let snapshot_tick_id = header[8] as u32;

    let cell_count = (size_x as usize)
        .checked_mul(size_y as usize)?
        .checked_mul(size_z as usize)?;

    // Compute section offsets and bounds-check against cap.
    let cells_off = SNAPSHOT_HEADER_BYTES;
    let cells_bytes = cell_count.checked_mul(2)?;
    let pofs_off = cells_off.checked_add(cells_bytes)?;
    let pofs_bytes = palette_count.checked_mul(4)?;
    let pcnt_off = pofs_off.checked_add(pofs_bytes)?;
    let pcnt_bytes = palette_count;
    let aabb_off = pcnt_off.checked_add(pcnt_bytes)?;
    let aabb_bytes = aabb_table_len.checked_mul(6)?.checked_mul(4)?;
    let end = aabb_off.checked_add(aabb_bytes)?;
    if end > cap {
        return None;
    }

    let cells = slice::from_raw_parts(ptr.add(cells_off) as *const u16, cell_count);
    let palette_offsets =
        slice::from_raw_parts(ptr.add(pofs_off) as *const u32, palette_count);
    let palette_counts = slice::from_raw_parts(ptr.add(pcnt_off), palette_count);
    let aabb_table =
        slice::from_raw_parts(ptr.add(aabb_off) as *const f32, aabb_table_len * 6);

    Some(WorldSnapshot {
        origin_x,
        origin_y,
        origin_z,
        size_x,
        size_y,
        size_z,
        snapshot_tick_id,
        cells,
        palette_offsets,
        palette_counts,
        aabb_table,
    })
}
