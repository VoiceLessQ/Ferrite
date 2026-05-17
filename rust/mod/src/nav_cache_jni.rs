//! JNI surface for the navigation cache event dispatch.
//!
//! Block-change events carry encoded kind discriminators (see
//! [`crate::nav_cache`]) so the Rust side can dispatch without
//! crossing JNI again to inspect block state. Fire-and-forget,
//! single-direction; cheap enough to call on every server-side
//! setBlock.

use std::slice;

use rosttasse::jni::objects::{JByteBuffer, JClass};
use rosttasse::jni::sys::{jboolean, jbyte, jint, jlong};
use rosttasse::JNIEnv;

use crate::nav_cache;
use crate::nav_cache_storage::{self, SectionId};

#[no_mangle]
pub extern "system" fn Java_me_apika_apikaprobe_RustBridge_navOnBlockChanged<'local>(
    _env: JNIEnv<'local>,
    _class: JClass<'local>,
    x: jint,
    y: jint,
    z: jint,
    old_kind: jbyte,
    new_kind: jbyte,
    new_open: jint,
) {
    nav_cache::on_block_changed(x, y, z, old_kind as u8, new_kind as u8, new_open);
}

/// Fill one 16×16×16 section from a direct ByteBuffer of 4-byte cells.
/// Buffer layout: [block_kind, hazard_kind, movement_cost, top_face_y] × 4096.
#[no_mangle]
pub extern "system" fn Java_me_apika_apikaprobe_RustBridge_navFillSection<'local>(
    env: JNIEnv<'local>,
    _class: JClass<'local>,
    chunk_x: jint,
    section_y: jint,
    chunk_z: jint,
    cell_data: JByteBuffer<'local>,
) {
    let Some(ptr) = env.get_direct_buffer_address(&cell_data).ok() else { return };
    let Some(cap) = env.get_direct_buffer_capacity(&cell_data).ok() else { return };
    let raw = unsafe { slice::from_raw_parts(ptr, cap as usize) };
    let id = SectionId { chunk_x, section_y, chunk_z };
    nav_cache_storage::fill_section(id, raw);
}

#[no_mangle]
pub extern "system" fn Java_me_apika_apikaprobe_RustBridge_navIsSectionCached<'local>(
    _env: JNIEnv<'local>,
    _class: JClass<'local>,
    chunk_x: jint,
    section_y: jint,
    chunk_z: jint,
) -> jboolean {
    let id = SectionId { chunk_x, section_y, chunk_z };
    nav_cache_storage::is_section_cached(id) as jboolean
}

/// Returns the block_kind byte for a world position, or -1 if uncached.
#[no_mangle]
pub extern "system" fn Java_me_apika_apikaprobe_RustBridge_navGetCellKind<'local>(
    _env: JNIEnv<'local>,
    _class: JClass<'local>,
    x: jint,
    y: jint,
    z: jint,
) -> jbyte {
    let id = SectionId::from_block_pos(x, y, z);
    let idx = nav_cache_storage::cell_index(x, y, z);
    nav_cache_storage::get_cell(id, idx)
        .map(|c| c.block_kind as jbyte)
        .unwrap_or(-1)
}

#[no_mangle]
pub extern "system" fn Java_me_apika_apikaprobe_RustBridge_navUpdateDoorState<'local>(
    _env: JNIEnv<'local>,
    _class: JClass<'local>,
    section_id: jlong,
    cell_idx: jint,
    is_open: jboolean,
) {
    nav_cache::update_door_state(section_id, cell_idx, is_open != 0);
}
