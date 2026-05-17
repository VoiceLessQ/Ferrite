//! Navigation cache event dispatch.
//!
//! Java fires block-change events into Rust on every server-side
//! setBlock. The 4-branch dispatch on (old_kind == DOOR, new_kind == DOOR)
//! routes door state through the door_state map and other changes
//! through the section invalidation path.
//!
//! Block-kind discriminants MUST stay in sync with
//! [`NavigationCacheBridge`] on the Java side. Properties that do not
//! affect walkability (redstone POWER, WATERLOGGED on shaped blocks)
//! are excluded from kind so the kind-diff filter (session 2 step 2)
//! can drop those events.

#![allow(dead_code)] // Some kinds aren't read until session 3's evaluator lands.

use crate::nav_cache_storage::SectionId;

pub const KIND_AIR: u8 = 0;
pub const KIND_OPAQUE_FULL: u8 = 1;
pub const KIND_DOOR: u8 = 2;
pub const KIND_SLAB_BOTTOM: u8 = 3;
pub const KIND_SLAB_TOP: u8 = 4;
pub const KIND_STAIRS: u8 = 5;
pub const KIND_FENCE: u8 = 6;
pub const KIND_FENCE_GATE: u8 = 7;
pub const KIND_WALL: u8 = 8;
pub const KIND_TRAPDOOR_OPEN: u8 = 9;
pub const KIND_TRAPDOOR_CLOSED: u8 = 10;
pub const KIND_LADDER: u8 = 11;
pub const KIND_WATER: u8 = 12;
pub const KIND_LAVA: u8 = 13;
pub const KIND_LEAVES: u8 = 14;
pub const KIND_CARPET: u8 = 15;
pub const KIND_SCAFFOLDING: u8 = 16;
pub const KIND_OTHER: u8 = 17;

pub fn on_block_changed(x: i32, y: i32, z: i32, old_kind: u8, new_kind: u8, new_open: i32) {
    let was_door = old_kind == KIND_DOOR;
    let is_door = new_kind == KIND_DOOR;

    match (was_door, is_door) {
        (false, false) => {
            // Kind-diff filter: drops cosmetic transitions (redstone POWER,
            // WATERLOGGED on shaped blocks, etc.) that don't cross the kind
            // boundary. Real cache-relevant changes (AIR <-> solid, water
            // flow into walkable space, slab type changes) do cross it.
            if old_kind == new_kind {
                return;
            }
            let section = SectionId::from_block_pos(x, y, z);
            crate::nav_cache_storage::evict_section(section);
            eprintln!(
                "[ferrite][nav-cache] invalidate section=({},{},{}) at ({}, {}, {}) old_kind={} new_kind={}",
                section.chunk_x, section.section_y, section.chunk_z,
                x, y, z, old_kind, new_kind
            );
        }
        (false, true) => {
            eprintln!(
                "[ferrite][nav-cache] door placed at ({}, {}, {}) open={}",
                x, y, z, new_open
            );
        }
        (true, false) => {
            eprintln!(
                "[ferrite][nav-cache] door removed at ({}, {}, {})",
                x, y, z
            );
        }
        (true, true) => {
            eprintln!(
                "[ferrite][nav-cache] door state change at ({}, {}, {}) open={}",
                x, y, z, new_open
            );
        }
    }
}

pub fn update_door_state(section_id: i64, cell_idx: i32, is_open: bool) {
    eprintln!(
        "[ferrite][nav-cache] update_door_state section={} cell={} open={}",
        section_id, cell_idx, is_open
    );
}
