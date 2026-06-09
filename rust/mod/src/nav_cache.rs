//! Navigation cache event dispatch.
//!
//! Java fires block-change events into Rust on every server-side
//! setBlock. Any kind-crossing change evicts the section. Door
//! open/close toggles arrive as DOOR -> DOOR (kind unchanged) and are
//! dropped: the cache never answers DOOR cells (kindToPathType returns
//! null on the Java side), so door churn cannot thrash the cache.
//!
//! Eviction here MUST stay symmetric with the Java-side kind cache
//! (NavigationCacheBridge.onBlockChanged evicts its slot whenever
//! oldKind != newKind). If the two stores disagree about whether a
//! section is live, the snapshot gate in PathFinderMixin stops
//! refilling and the section goes permanently cold.
//!
//! Block-kind discriminants MUST stay in sync with
//! [`NavigationCacheBridge`] on the Java side. Properties that do not
//! affect walkability (redstone POWER, WATERLOGGED on shaped blocks)
//! are excluded from kind so the kind-diff filter (session 2 step 2)
//! can drop those events.

#![allow(dead_code)] // Kind constants mirror the Java side; most aren't used in Rust directly.

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

pub fn on_block_changed(x: i32, y: i32, z: i32, old_kind: u8, new_kind: u8, _new_open: i32) {
    // Kind-diff filter: drops cosmetic transitions (redstone POWER,
    // WATERLOGGED on shaped blocks, door open/close) that don't cross
    // the kind boundary. Everything else, including door place/remove,
    // evicts the section - matching the Java-side slot eviction exactly.
    if old_kind == new_kind {
        return;
    }
    let section = SectionId::from_block_pos(x, y, z);
    crate::nav_cache_storage::evict_section(section);
}

pub fn update_door_state(_section_id: i64, _cell_idx: i32, _is_open: bool) {
    // Door-state tracking reserved for future use; section eviction on
    // door kind changes is handled via on_block_changed.
}
