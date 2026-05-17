//! Section storage layout for the navigation cache.
//!
//! Section granularity (16×16×16 = 4096 cells per section) was the
//! invalidation-locality decision from the cache scoping doc: a single
//! block change dirties one section, not a whole chunk column. That's
//! the structural mitigation against the mob-farm thrash case where
//! redstone-driven block updates would otherwise cascade across the
//! full y range.
//!
//! AoS layout (4 bytes per cell, 16 cells per 64-byte cache line)
//! matches A*'s position-first access pattern: each node visit reads
//! all four fields for one neighbor cell, so co-locating them avoids
//! cache-line amplification. SoA was considered and rejected — see
//! the cache scoping discussion for the read pattern argument.
//!
//! Session 2 declares the storage shape. The fill path (Java snapshot
//! → JNI handoff → Rust populates `Section`) lands in session 3 along
//! with the villager evaluator that reads from it.


#[derive(Copy, Clone, Default)]
#[repr(C)]
pub struct CellData {
    pub block_kind: u8,
    pub hazard_kind: u8,
    pub movement_cost: u8,
    pub top_face_y: u8,
}

pub const SECTION_SIZE: usize = 16 * 16 * 16;

pub type Section = [CellData; SECTION_SIZE];

/// Identifies one 16×16×16 chunk section. {@code section_y} can be
/// negative on overworld worlds (-4 covers y = -64 to -49).
#[derive(Copy, Clone, PartialEq, Eq, Hash, Debug)]
pub struct SectionId {
    pub chunk_x: i32,
    pub section_y: i32,
    pub chunk_z: i32,
}

impl SectionId {
    /// Section that contains the given block position. Bit shifts handle
    /// negative coordinates correctly: `-1 >> 4 == -1` in Rust's arithmetic
    /// shift, which is the right answer (block y=-1 lives in section y=-1).
    #[inline]
    pub fn from_block_pos(x: i32, y: i32, z: i32) -> Self {
        Self {
            chunk_x: x >> 4,
            section_y: y >> 4,
            chunk_z: z >> 4,
        }
    }
}

/// Cell index within a section. Bit-packed `(y << 8) | (z << 4) | x`,
/// avoiding multiplications. All three components are masked to 4 bits
/// so callers can pass raw block coords; the section-relative position
/// falls out automatically.
#[inline]
pub fn cell_index(x: i32, y: i32, z: i32) -> usize {
    let lx = (x & 0xF) as usize;
    let ly = (y & 0xF) as usize;
    let lz = (z & 0xF) as usize;
    (ly << 8) | (lz << 4) | lx
}

// ── Runtime section storage ──────────────────────────────────────────────────

use std::collections::HashMap;
use std::sync::{Mutex, OnceLock};

fn sections() -> &'static Mutex<HashMap<SectionId, Vec<CellData>>> {
    static MAP: OnceLock<Mutex<HashMap<SectionId, Vec<CellData>>>> = OnceLock::new();
    MAP.get_or_init(|| Mutex::new(HashMap::new()))
}

/// Fill (or replace) a section from a flat 4-bytes-per-cell buffer
/// [block_kind, hazard_kind, movement_cost, top_face_y] × 4096.
pub fn fill_section(id: SectionId, raw: &[u8]) {
    let mut cells = Vec::with_capacity(SECTION_SIZE);
    for chunk in raw.chunks_exact(4).take(SECTION_SIZE) {
        cells.push(CellData {
            block_kind: chunk[0],
            hazard_kind: chunk[1],
            movement_cost: chunk[2],
            top_face_y: chunk[3],
        });
    }
    while cells.len() < SECTION_SIZE {
        cells.push(CellData::default());
    }
    sections().lock().unwrap().insert(id, cells);
}

pub fn evict_section(id: SectionId) {
    sections().lock().unwrap().remove(&id);
}

pub fn is_section_cached(id: SectionId) -> bool {
    sections().lock().unwrap().contains_key(&id)
}

pub fn get_cell(id: SectionId, cell_idx: usize) -> Option<CellData> {
    sections()
        .lock()
        .unwrap()
        .get(&id)
        .and_then(|v| v.get(cell_idx))
        .copied()
}
