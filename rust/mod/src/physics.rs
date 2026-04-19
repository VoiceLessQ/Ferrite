//! Entity collision sweep — Rust implementation of Entity.collide(Vec3)
//! (yarn: adjustMovementForCollisions).
//!
//! Matches vanilla's per-axis sweep order (Y → bigger-horizontal → smaller-
//! horizontal) and its step-up retry. No JNI surface here — this module is
//! pure logic operating on borrowed views into direct ByteBuffers that Java
//! owns. All coordinates are world-space f64 to preserve bit-exact parity
//! with vanilla's double-precision math, except AABBs in the palette which
//! are cell-local f32 (offset to world coords at lookup time).

// ============================================================================
// Constants
// ============================================================================

/// Result flag bits (mirrored on the Java side).
pub const FLAG_HORIZONTAL_COLLISION: u8 = 1 << 0;
pub const FLAG_VERTICAL_COLLISION:   u8 = 1 << 1;
pub const FLAG_STEPPED_UP:           u8 = 1 << 2;
pub const FLAG_FALLBACK:             u8 = 1 << 3;

/// Request flag bits (bit 0=onGround, bit 1=skip-step-up).
pub const REQ_FLAG_ON_GROUND:     u8 = 1 << 0;
pub const REQ_FLAG_SKIP_STEP_UP:  u8 = 1 << 1;

/// Palette sentinel for "this cell has a neighbor-dependent or otherwise
/// unserializable shape — Java must handle this entity's sweep."
pub const PALETTE_COMPLEX: u8 = 255;

/// Vanilla-matching epsilon for axis-aligned contact (1e-7 in Shapes.collide).
const CONTACT_EPS: f64 = 1.0e-7;

// ============================================================================
// Geometry primitives
// ============================================================================

#[derive(Copy, Clone, Debug, PartialEq)]
pub struct Vec3d { pub x: f64, pub y: f64, pub z: f64 }

#[derive(Copy, Clone, Debug)]
pub enum Axis { X, Y, Z }

#[derive(Copy, Clone, Debug, PartialEq)]
pub struct Aabb {
    pub min_x: f64, pub min_y: f64, pub min_z: f64,
    pub max_x: f64, pub max_y: f64, pub max_z: f64,
}

impl Aabb {
    #[inline]
    pub fn translate(self, dx: f64, dy: f64, dz: f64) -> Self {
        Aabb {
            min_x: self.min_x + dx, min_y: self.min_y + dy, min_z: self.min_z + dz,
            max_x: self.max_x + dx, max_y: self.max_y + dy, max_z: self.max_z + dz,
        }
    }

    /// Mirrors AABB.expandTowards(dx, dy, dz): extend the bounding box in
    /// the direction of motion without shrinking it on the opposite side.
    #[inline]
    pub fn expand_toward(self, dx: f64, dy: f64, dz: f64) -> Self {
        let (min_x, max_x) = if dx < 0.0 { (self.min_x + dx, self.max_x) } else { (self.min_x, self.max_x + dx) };
        let (min_y, max_y) = if dy < 0.0 { (self.min_y + dy, self.max_y) } else { (self.min_y, self.max_y + dy) };
        let (min_z, max_z) = if dz < 0.0 { (self.min_z + dz, self.max_z) } else { (self.min_z, self.max_z + dz) };
        Aabb { min_x, min_y, min_z, max_x, max_y, max_z }
    }

    /// True if `self` and `other` overlap on the two axes perpendicular to
    /// `axis`. Used to decide whether a shape can block motion along `axis`.
    /// Note: vanilla uses strict > / < here (touching at a plane doesn't
    /// count as overlap for the perpendicular-axis test).
    #[inline]
    pub fn overlaps_perpendicular(self, other: Aabb, axis: Axis) -> bool {
        match axis {
            Axis::X => self.min_y < other.max_y && self.max_y > other.min_y
                    && self.min_z < other.max_z && self.max_z > other.min_z,
            Axis::Y => self.min_x < other.max_x && self.max_x > other.min_x
                    && self.min_z < other.max_z && self.max_z > other.min_z,
            Axis::Z => self.min_x < other.max_x && self.max_x > other.min_x
                    && self.min_y < other.max_y && self.max_y > other.min_y,
        }
    }
}

// ============================================================================
// Snapshot view (borrowed, zero-copy over a Java ByteBuffer)
// ============================================================================

/// Borrowed view of the shared world snapshot. All slices must remain valid
/// for the duration of the compute call. Y-major cell ordering:
///   cells[(y * size_z + z) * size_x + x]
pub struct WorldSnapshot<'a> {
    pub origin_x: i32,
    pub origin_y: i32,
    pub origin_z: i32,
    pub size_x: u32,
    pub size_y: u32,
    pub size_z: u32,
    pub snapshot_tick_id: u32,
    pub cells: &'a [u16],           // len = size_x * size_y * size_z
    pub palette_offsets: &'a [u32], // len = palette_count
    pub palette_counts: &'a [u8],   // len = palette_count; 0=empty, 255=complex
    pub aabb_table: &'a [f32],      // len = 6 * total_aabb_count, cell-local [0,1]
}

impl<'a> WorldSnapshot<'a> {
    /// Returns the palette entry covering world block (bx, by, bz), or None
    /// if the block lies outside the snapshot bounds (caller must fallback).
    #[inline]
    fn palette_at(&self, bx: i32, by: i32, bz: i32) -> Option<u16> {
        let lx = bx - self.origin_x;
        let ly = by - self.origin_y;
        let lz = bz - self.origin_z;
        if lx < 0 || ly < 0 || lz < 0 { return None; }
        let (lx, ly, lz) = (lx as u32, ly as u32, lz as u32);
        if lx >= self.size_x || ly >= self.size_y || lz >= self.size_z { return None; }
        let idx = (ly * self.size_z + lz) * self.size_x + lx;
        Some(self.cells[idx as usize])
    }
}

// ============================================================================
// Request / Result (kept as #[repr(C)] for direct ByteBuffer mapping)
// ============================================================================

#[repr(C)]
#[derive(Copy, Clone, Debug)]
pub struct EntityRequest {
    pub entity_id: u32,          // 0
    pub _pad0: u32,              // 4  (align f64)
    pub aabb_min_x: f64,         // 8
    pub aabb_min_y: f64,         // 16
    pub aabb_min_z: f64,         // 24
    pub aabb_max_x: f64,         // 32
    pub aabb_max_y: f64,         // 40
    pub aabb_max_z: f64,         // 48
    pub motion_x: f64,           // 56
    pub motion_y: f64,           // 64
    pub motion_z: f64,           // 72
    pub max_step_up: f32,        // 80
    pub flags: u8,               // 84
    pub _pad1: [u8; 3],          // 85
    pub snapshot_tick_id: u32,   // 88
    pub _pad2: u32,              // 92 (pad to 96 for 8-byte stride alignment)
}
const _: [(); 96] = [(); std::mem::size_of::<EntityRequest>()];

#[repr(C)]
#[derive(Copy, Clone, Debug, Default)]
pub struct EntityResult {
    pub entity_id: u32,          // 0
    pub _pad0: u32,              // 4
    pub adjusted_x: f64,         // 8
    pub adjusted_y: f64,         // 16
    pub adjusted_z: f64,         // 24
    pub flags: u8,               // 32
    pub _pad1: [u8; 3],          // 33
}
const _: [(); 40] = [(); std::mem::size_of::<EntityResult>()];
// ^ #[repr(C)] aligns to 8-byte boundary because of f64 fields. Effective
//   stride is 40 bytes, not 32. Java buffer layout must match this.

// ============================================================================
// Core sweep
// ============================================================================

/// One-axis sweep: given motion `delta` along `axis`, clamp it so `aabb`
/// doesn't penetrate any shape in `shapes`. Mirrors Shapes.collide().
fn sweep_axis(axis: Axis, aabb: Aabb, shapes: &[Aabb], mut delta: f64) -> f64 {
    if delta == 0.0 || shapes.is_empty() { return delta; }

    for s in shapes {
        if !aabb.overlaps_perpendicular(*s, axis) { continue; }
        match axis {
            Axis::X => {
                if delta > 0.0 && aabb.max_x <= s.min_x + CONTACT_EPS {
                    let gap = s.min_x - aabb.max_x;
                    if gap < delta { delta = gap; }
                } else if delta < 0.0 && aabb.min_x >= s.max_x - CONTACT_EPS {
                    let gap = s.max_x - aabb.min_x;
                    if gap > delta { delta = gap; }
                }
            }
            Axis::Y => {
                if delta > 0.0 && aabb.max_y <= s.min_y + CONTACT_EPS {
                    let gap = s.min_y - aabb.max_y;
                    if gap < delta { delta = gap; }
                } else if delta < 0.0 && aabb.min_y >= s.max_y - CONTACT_EPS {
                    let gap = s.max_y - aabb.min_y;
                    if gap > delta { delta = gap; }
                }
            }
            Axis::Z => {
                if delta > 0.0 && aabb.max_z <= s.min_z + CONTACT_EPS {
                    let gap = s.min_z - aabb.max_z;
                    if gap < delta { delta = gap; }
                } else if delta < 0.0 && aabb.min_z >= s.max_z - CONTACT_EPS {
                    let gap = s.max_z - aabb.min_z;
                    if gap > delta { delta = gap; }
                }
            }
        }
    }
    delta
}

/// Mirror of Entity.collideWithShapes(Vec3, AABB, List<VoxelShape>).
///
/// Axis order is Y → bigger-horizontal → smaller-horizontal. After each
/// axis sweep, the AABB is translated by the consumed delta so the next
/// axis sees the post-movement position (vanilla's exact pattern).
pub fn collide_with_shapes(motion: Vec3d, aabb: Aabb, shapes: &[Aabb]) -> Vec3d {
    if shapes.is_empty() { return motion; }

    let mut x = motion.x;
    let mut y = motion.y;
    let mut z = motion.z;
    let mut aabb = aabb;

    if y != 0.0 {
        y = sweep_axis(Axis::Y, aabb, shapes, y);
        if y != 0.0 { aabb = aabb.translate(0.0, y, 0.0); }
    }

    // bl = |x| < |z|, meaning Z is the bigger horizontal axis → test Z first.
    // Vanilla: abs-compare is strict-less-than, ties go to the X-first branch.
    let z_bigger = x.abs() < z.abs();

    if z_bigger && z != 0.0 {
        z = sweep_axis(Axis::Z, aabb, shapes, z);
        if z != 0.0 { aabb = aabb.translate(0.0, 0.0, z); }
    }

    if x != 0.0 {
        x = sweep_axis(Axis::X, aabb, shapes, x);
        if !z_bigger && x != 0.0 { aabb = aabb.translate(x, 0.0, 0.0); }
    }

    if !z_bigger && z != 0.0 {
        z = sweep_axis(Axis::Z, aabb, shapes, z);
    }

    Vec3d { x, y, z }
}

// ============================================================================
// Snapshot collider gathering
// ============================================================================

/// Walks every block position whose unit cube overlaps `query`, reads the
/// cell's palette entry, and appends world-space AABBs to `out`.
///
/// Returns `false` (and partial out) if:
///   - the query range extends outside the snapshot bounds, OR
///   - any overlapping cell has palette count = PALETTE_COMPLEX.
/// In either case the caller must mark the entity as FALLBACK.
pub fn collect_colliders_from_snapshot(
    snap: &WorldSnapshot,
    query: Aabb,
    out: &mut Vec<Aabb>,
) -> bool {
    // Vanilla iterates block positions whose [bp, bp+1] cube intersects the
    // query AABB. Using floor for both bounds is a safe superset (extra
    // empty cells are harmless; missing cells would be incorrect).
    let min_bx = query.min_x.floor() as i32;
    let min_by = query.min_y.floor() as i32;
    let min_bz = query.min_z.floor() as i32;
    let max_bx = query.max_x.floor() as i32;
    let max_by = query.max_y.floor() as i32;
    let max_bz = query.max_z.floor() as i32;

    for by in min_by..=max_by {
        for bz in min_bz..=max_bz {
            for bx in min_bx..=max_bx {
                let pidx = match snap.palette_at(bx, by, bz) {
                    Some(p) => p as usize,
                    None => return false, // outside snapshot → fallback
                };
                let count = snap.palette_counts[pidx];
                if count == 0 { continue; }                 // air / empty
                if count == PALETTE_COMPLEX { return false; } // neighbor-dep → fallback

                let start = snap.palette_offsets[pidx] as usize;
                let end = start + count as usize;
                let (ox, oy, oz) = (bx as f64, by as f64, bz as f64);
                for i in start..end {
                    let base = i * 6;
                    let aabb = Aabb {
                        min_x: ox + snap.aabb_table[base    ] as f64,
                        min_y: oy + snap.aabb_table[base + 1] as f64,
                        min_z: oz + snap.aabb_table[base + 2] as f64,
                        max_x: ox + snap.aabb_table[base + 3] as f64,
                        max_y: oy + snap.aabb_table[base + 4] as f64,
                        max_z: oz + snap.aabb_table[base + 5] as f64,
                    };
                    out.push(aabb);
                }
            }
        }
    }
    true
}

// ============================================================================
// Step-up candidate heights
// ============================================================================

/// Mirror of collectCandidateStepUpHeights. For AABB shapes, a shape's
/// Y-coordinates are just [min_y, max_y]. We collect each coord's offset
/// relative to aabb.min_y, filtered to 0 <= h <= max_step and h != already_y,
/// deduped and sorted ascending.
fn collect_candidate_step_heights(
    aabb: Aabb,
    shapes: &[Aabb],
    max_step: f32,
    already_y: f32,
) -> Vec<f32> {
    let mut heights: Vec<f32> = Vec::with_capacity(8);
    for s in shapes {
        for &coord in &[s.min_y, s.max_y] {
            let h = (coord - aabb.min_y) as f32;
            if h < 0.0 || h == already_y { continue; }
            // Vanilla's `break` on h > max_step only works because coords
            // arrive sorted within a shape; here we just filter.
            if h > max_step { continue; }
            if !heights.contains(&h) { heights.push(h); }
        }
    }
    heights.sort_by(|a, b| a.partial_cmp(b).unwrap_or(std::cmp::Ordering::Equal));
    heights
}

// ============================================================================
// Top-level: collide one entity against the snapshot
// ============================================================================

/// Compute the collision-adjusted motion for a single entity against the
/// shared snapshot. Mirrors Entity.collide(Vec3) including the step-up
/// retry. Writes the result into `out`.
///
/// Returns false if this entity must fall back to Java (stale snapshot,
/// out-of-bounds query, or a complex cell in the query region).
pub fn collide_entity(
    req: &EntityRequest,
    snap: &WorldSnapshot,
    out: &mut EntityResult,
) -> bool {
    out.entity_id = req.entity_id;

    if req.snapshot_tick_id != snap.snapshot_tick_id {
        out.flags = FLAG_FALLBACK;
        return false;
    }

    let aabb = Aabb {
        min_x: req.aabb_min_x, min_y: req.aabb_min_y, min_z: req.aabb_min_z,
        max_x: req.aabb_max_x, max_y: req.aabb_max_y, max_z: req.aabb_max_z,
    };
    let motion = Vec3d { x: req.motion_x, y: req.motion_y, z: req.motion_z };

    // --- Primary sweep ------------------------------------------------------
    let query = aabb.expand_toward(motion.x, motion.y, motion.z);
    let mut colliders: Vec<Aabb> = Vec::with_capacity(64);
    if !collect_colliders_from_snapshot(snap, query, &mut colliders) {
        out.flags = FLAG_FALLBACK;
        return false;
    }
    let adjusted = collide_with_shapes(motion, aabb, &colliders);

    // --- Step-up retry ------------------------------------------------------
    // Vanilla: if (maxUpStep > 0) && (bl4 || onGround) && (bl_x || bl_z)
    //   bl_x = motion.x != adjusted.x
    //   bl_y = motion.y != adjusted.y
    //   bl_z = motion.z != adjusted.z
    //   bl4  = bl_y && motion.y < 0.0
    let bl_x = motion.x != adjusted.x;
    let bl_y = motion.y != adjusted.y;
    let bl_z = motion.z != adjusted.z;
    let bl4  = bl_y && motion.y < 0.0;

    let max_step = req.max_step_up;
    let on_ground = (req.flags & REQ_FLAG_ON_GROUND) != 0;
    let skip_step = (req.flags & REQ_FLAG_SKIP_STEP_UP) != 0;

    let (final_motion, stepped_up) =
        if !skip_step && max_step > 0.0 && (bl4 || on_ground) && (bl_x || bl_z) {
            let aabb2 = if bl4 { aabb.translate(0.0, adjusted.y, 0.0) } else { aabb };
            let mut aabb3 = aabb2.expand_toward(motion.x, max_step as f64, motion.z);
            if !bl4 { aabb3 = aabb3.expand_toward(0.0, -1.0e-5, 0.0); }

            let mut step_colliders: Vec<Aabb> = Vec::with_capacity(64);
            if !collect_colliders_from_snapshot(snap, aabb3, &mut step_colliders) {
                out.flags = FLAG_FALLBACK;
                return false;
            }

            let heights = collect_candidate_step_heights(aabb2, &step_colliders, max_step, adjusted.y as f32);
            let mut chosen: Option<Vec3d> = None;
            let orig_horiz_sq = adjusted.x * adjusted.x + adjusted.z * adjusted.z;
            for g in heights {
                let cand = collide_with_shapes(
                    Vec3d { x: motion.x, y: g as f64, z: motion.z },
                    aabb2,
                    &step_colliders,
                );
                let cand_horiz_sq = cand.x * cand.x + cand.z * cand.z;
                if cand_horiz_sq > orig_horiz_sq {
                    // Vanilla: return cand.add(0, -(aabb.min_y - aabb2.min_y), 0)
                    let d = aabb.min_y - aabb2.min_y;
                    chosen = Some(Vec3d { x: cand.x, y: cand.y - d, z: cand.z });
                    break;
                }
            }
            match chosen {
                Some(m) => (m, true),
                None    => (adjusted, false),
            }
        } else {
            (adjusted, false)
        };

    // --- Flags --------------------------------------------------------------
    let mut flags = 0u8;
    if motion.x != final_motion.x || motion.z != final_motion.z {
        flags |= FLAG_HORIZONTAL_COLLISION;
    }
    if motion.y != final_motion.y {
        flags |= FLAG_VERTICAL_COLLISION;
    }
    if stepped_up {
        flags |= FLAG_STEPPED_UP;
    }

    out.adjusted_x = final_motion.x;
    out.adjusted_y = final_motion.y;
    out.adjusted_z = final_motion.z;
    out.flags = flags;
    true
}

// ============================================================================
// Tests
// ============================================================================

#[cfg(test)]
mod tests {
    use super::*;

    fn unit_cube(x: f64, y: f64, z: f64) -> Aabb {
        Aabb { min_x: x, min_y: y, min_z: z, max_x: x + 1.0, max_y: y + 1.0, max_z: z + 1.0 }
    }

    fn entity_aabb() -> Aabb {
        // Zombie-ish: 0.6 × 1.95 × 0.6, standing on y=64, centered at x=0.5,z=0.5.
        Aabb { min_x: 0.2, min_y: 64.0, min_z: 0.2, max_x: 0.8, max_y: 65.95, max_z: 0.8 }
    }

    #[test]
    fn empty_shapes_returns_motion_unchanged() {
        let m = Vec3d { x: 0.1, y: -0.08, z: 0.0 };
        let r = collide_with_shapes(m, entity_aabb(), &[]);
        assert_eq!(r, m);
    }

    #[test]
    fn ground_stops_downward_motion() {
        let floor = unit_cube(0.0, 63.0, 0.0); // block directly under entity
        let m = Vec3d { x: 0.0, y: -0.5, z: 0.0 };
        let r = collide_with_shapes(m, entity_aabb(), &[floor]);
        // Entity min_y = 64.0, floor max_y = 64.0 → gap = 0 → y clamps to 0.
        assert_eq!(r.y, 0.0);
        assert_eq!(r.x, 0.0);
        assert_eq!(r.z, 0.0);
    }

    #[test]
    fn wall_stops_horizontal_motion() {
        // Wall at x=1.0..2.0, covering the entity's y-range.
        let wall = Aabb { min_x: 1.0, min_y: 63.0, min_z: 0.0, max_x: 2.0, max_y: 66.0, max_z: 1.0 };
        let m = Vec3d { x: 0.5, y: 0.0, z: 0.0 };
        let r = collide_with_shapes(m, entity_aabb(), &[wall]);
        // entity max_x = 0.8, wall min_x = 1.0 → gap = 0.2.
        assert!((r.x - 0.2).abs() < 1e-9, "x = {}", r.x);
    }

    #[test]
    fn axis_sweep_independent_of_non_overlapping_perpendicular() {
        // A shape far away on Z should not clamp X motion.
        let far = Aabb { min_x: 1.0, min_y: 63.0, min_z: 10.0, max_x: 2.0, max_y: 66.0, max_z: 11.0 };
        let m = Vec3d { x: 0.5, y: 0.0, z: 0.0 };
        let r = collide_with_shapes(m, entity_aabb(), &[far]);
        assert_eq!(r.x, 0.5);
    }

    #[test]
    fn step_height_collection_respects_bounds() {
        let s = Aabb { min_x: 0.0, min_y: 64.0, min_z: 0.0, max_x: 1.0, max_y: 64.5, max_z: 1.0 };
        let aabb = entity_aabb();
        let heights = collect_candidate_step_heights(aabb, &[s], 0.6, 0.0);
        // s.max_y - aabb.min_y = 0.5. s.min_y - aabb.min_y = 0.0 → filtered by h != already_y (0.0).
        // Expect just [0.5]. (max_step=0.6 allows 0.5.)
        assert_eq!(heights, vec![0.5_f32]);
    }
}
