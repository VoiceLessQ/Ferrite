//! Mob-vs-mob cramming push — Rust implementation of the vanilla
//! LivingEntity.tickCramming() → Entity.push(Entity) inner loop.
//!
//! No world state, no snapshot. Pure position-and-AABB compute:
//! Java hands over a `CrammingInput` per pushable mob, Rust builds a
//! 2-block 2D spatial hash, iterates pairs with an array-index guard
//! (each pair visited once), applies the vanilla push formula and
//! accumulates `CrammingResult` deltas.
//!
//! Vanilla push math (preserved bit-exactly):
//!   d = b.x - a.x
//!   e = b.z - a.z
//!   f = max(|d|, |e|)        (Chebyshev, not Euclidean)
//!   if f < 0.01  → skip
//!   f = sqrt(f)
//!   d /= f; e /= f
//!   g = min(1.0, 1.0 / f)
//!   d *= g * 0.05; e *= g * 0.05
//!   apply -(d,e) to A; +(d,e) to B  (gated by pushable && !vehicle)
//!
//! No JNI surface here — this module is pure logic. cramming_jni.rs
//! is the entry point.

use std::collections::HashMap;

// ============================================================================
// Flags (must match Java PhysicsHandoff/cramming-side constants)
// ============================================================================

pub const FLAG_PUSHABLE:   u8 = 1 << 0;
pub const FLAG_VEHICLE:    u8 = 1 << 1;
pub const FLAG_PASSENGER:  u8 = 1 << 2;
pub const FLAG_NO_PHYSICS: u8 = 1 << 3;

// Below this Chebyshev distance, vanilla skips the pair.
const CONTACT_MIN: f64 = 0.01;
// Vanilla's magic scale factor inside Entity.push(Entity).
const PUSH_SCALE: f64 = 0.05;
// Spatial hash cell size in blocks.
const CELL_SIZE: f64 = 2.0;

// ============================================================================
// Request / Result structs — #[repr(C)] for direct ByteBuffer mapping
// ============================================================================

#[repr(C)]
#[derive(Copy, Clone, Debug)]
pub struct CrammingInput {
    pub entity_id:       u32,   // 0
    pub flags:           u8,    // 4
    pub _pad0:           [u8; 3], // 5 (align f64)
    pub x:               f64,   // 8
    pub z:               f64,   // 16
    pub aabb_half_width: f32,   // 24  half-extent on X (and Z — mob bbox is square in plan)
    pub aabb_min_y:      f32,   // 28
    pub aabb_max_y:      f32,   // 32
    /// Vanilla isPassengerOfSameVehicle uses this comparison (root vehicle id).
    /// -1 sentinel = not riding anything; pairs with both -1 still skip the
    /// same-vehicle check (sentinel is not equal-equal in spirit).
    pub root_vehicle_id: i32,   // 36  stride = 40
}
const _: [(); 40] = [(); std::mem::size_of::<CrammingInput>()];

#[repr(C)]
#[derive(Copy, Clone, Debug, Default)]
pub struct CrammingResult {
    pub entity_id:      u32,   // 0
    pub _pad0:          u32,   // 4  align f64
    pub accum_dx:       f64,   // 8
    pub accum_dz:       f64,   // 16
    pub neighbor_count: u32,   // 24  all overlapping pairs (debug)
    /// Overlapping pushable non-passenger pairs. Mirrors vanilla
    /// pushEntities's `count` value used for the cramming-damage gate
    /// (`if count > maxCramming - 1`). Threshold check + 1-in-4 random
    /// stays on the Java side (entity.random.nextInt(4) is per-entity).
    pub crowded_count:  u32,   // 28  stride = 32
}
const _: [(); 32] = [(); std::mem::size_of::<CrammingResult>()];

// ============================================================================
// Spatial hash
// ============================================================================

/// 2D hash of entity indices into 2-block cells on the XZ plane.
/// Cell key is (floor(x/2), floor(z/2)) packed into i64.
#[inline]
fn cell_key(x: f64, z: f64) -> (i32, i32) {
    let cx = (x / CELL_SIZE).floor() as i32;
    let cz = (z / CELL_SIZE).floor() as i32;
    (cx, cz)
}

// ============================================================================
// Core
// ============================================================================

/// Seeds `results` with echoed entity_ids + zeroed accumulators, then
/// walks every pair `(i, j)` with `i < j` whose AABBs overlap and
/// applies the vanilla push formula.
///
/// Complexity: O(N · k) where k is the average local density.
/// In typical scenarios k is small (< 10); in a pit at extreme
/// density k is bounded by the cell size × cramming limit.
pub fn compute_cramming(inputs: &[CrammingInput], results: &mut [CrammingResult]) {
    let n = inputs.len();
    // Mismatched slice lengths are a caller bug, but panicking here would
    // cross the JNI boundary for the in-production path. Return silently
    // and let debug builds catch the mismatch at dev time.
    debug_assert_eq!(n, results.len(), "inputs and results must have matching length");
    if n != results.len() {
        return;
    }

    // --- Seed results --------------------------------------------------------
    for i in 0..n {
        results[i] = CrammingResult {
            entity_id: inputs[i].entity_id,
            _pad0: 0,
            accum_dx: 0.0,
            accum_dz: 0.0,
            neighbor_count: 0,
            crowded_count: 0,
        };
    }

    if n < 2 {
        return;
    }

    // --- Build hash ----------------------------------------------------------
    let mut cells: HashMap<(i32, i32), Vec<u32>> = HashMap::with_capacity(n);
    for (i, input) in inputs.iter().enumerate() {
        let key = cell_key(input.x, input.z);
        cells.entry(key).or_insert_with(|| Vec::with_capacity(8)).push(i as u32);
    }

    // --- Pair iteration ------------------------------------------------------
    for i in 0..n {
        let a = inputs[i];
        if (a.flags & FLAG_NO_PHYSICS) != 0 {
            continue;
        }
        let (cx, cz) = cell_key(a.x, a.z);

        for dcx in -1..=1i32 {
            for dcz in -1..=1i32 {
                let neighbor_key = (cx + dcx, cz + dcz);
                let indices = match cells.get(&neighbor_key) {
                    Some(v) => v,
                    None => continue,
                };
                for &j_u32 in indices {
                    let j = j_u32 as usize;
                    // Array-index guard: visit each pair exactly once.
                    if j <= i {
                        continue;
                    }
                    process_pair(inputs, results, i, j, &a);
                }
            }
        }
    }
}

#[inline]
fn process_pair(
    inputs: &[CrammingInput],
    results: &mut [CrammingResult],
    i: usize,
    j: usize,
    a: &CrammingInput,
) {
    let b = inputs[j];
    if (b.flags & FLAG_NO_PHYSICS) != 0 {
        return;
    }

    // Vanilla isPassengerOfSameVehicle: skip the entire push if both
    // entities share a root vehicle. -1 sentinel means "not riding,"
    // and two -1s must NOT match (they're not in any vehicle, let alone
    // the same one).
    if a.root_vehicle_id != -1 && a.root_vehicle_id == b.root_vehicle_id {
        return;
    }

    let dx = b.x - a.x;
    let dz = b.z - a.z;

    // --- AABB overlap test (horizontal + vertical) ---------------------------
    let a_half = a.aabb_half_width as f64;
    let b_half = b.aabb_half_width as f64;
    let half_sum = a_half + b_half;
    if dx.abs() >= half_sum || dz.abs() >= half_sum {
        return;
    }
    // Strict Y overlap: touching at a plane doesn't count (matches AABB.intersects).
    if !(a.aabb_min_y < b.aabb_max_y && b.aabb_min_y < a.aabb_max_y) {
        return;
    }

    // --- Vanilla push math (exact replica) -----------------------------------
    let f = dx.abs().max(dz.abs()); // Chebyshev (Mth.absMax)
    if f < CONTACT_MIN {
        return;
    }
    let sqrt_f = f.sqrt();
    let dn = dx / sqrt_f;
    let en = dz / sqrt_f;
    let g = (1.0 / sqrt_f).min(1.0);
    let push_dx = dn * g * PUSH_SCALE;
    let push_dz = en * g * PUSH_SCALE;

    // --- Apply with split_at_mut to satisfy borrow checker -------------------
    // i < j by guarantee above, so head[i] and tail[0] disjoint.
    let (head, tail) = results.split_at_mut(j);
    let res_a = &mut head[i];
    let res_b = &mut tail[0];

    let a_eligible = (a.flags & FLAG_PUSHABLE) != 0 && (a.flags & FLAG_VEHICLE) == 0;
    let b_eligible = (b.flags & FLAG_PUSHABLE) != 0 && (b.flags & FLAG_VEHICLE) == 0;

    if a_eligible {
        res_a.accum_dx -= push_dx;
        res_a.accum_dz -= push_dz;
    }
    if b_eligible {
        res_b.accum_dx += push_dx;
        res_b.accum_dz += push_dz;
    }
    // Neighbor count: every overlapping pair visit (debug). Crowded count:
    // mirrors vanilla's pushEntities `count` — only pushable non-passenger
    // overlaps qualify, since vanilla's getPushableEntities pre-filters to
    // pushable and the inner loop excludes passengers.
    res_a.neighbor_count += 1;
    res_b.neighbor_count += 1;
    if (b.flags & FLAG_PUSHABLE) != 0 && (b.flags & FLAG_PASSENGER) == 0 {
        res_a.crowded_count += 1;
    }
    if (a.flags & FLAG_PUSHABLE) != 0 && (a.flags & FLAG_PASSENGER) == 0 {
        res_b.crowded_count += 1;
    }
}

// ============================================================================
// Tests
// ============================================================================

#[cfg(test)]
mod tests {
    use super::*;

    fn make_mob(id: u32, x: f64, z: f64, min_y: f32, max_y: f32) -> CrammingInput {
        CrammingInput {
            entity_id: id,
            flags: FLAG_PUSHABLE,
            _pad0: [0; 3],
            x,
            z,
            aabb_half_width: 0.3, // zombie-ish
            aabb_min_y: min_y,
            aabb_max_y: max_y,
            root_vehicle_id: -1,
        }
    }

    #[test]
    fn two_overlapping_mobs_push_apart() {
        let inputs = [
            make_mob(1, 0.0, 0.0, 64.0, 66.0),
            make_mob(2, 0.5, 0.5, 64.0, 66.0),
        ];
        let mut results = [CrammingResult::default(); 2];
        compute_cramming(&inputs, &mut results);

        // A (first) pushed toward -x/-z; B (second) toward +x/+z.
        assert!(results[0].accum_dx < 0.0, "A dx = {}", results[0].accum_dx);
        assert!(results[0].accum_dz < 0.0, "A dz = {}", results[0].accum_dz);
        assert!(results[1].accum_dx > 0.0, "B dx = {}", results[1].accum_dx);
        assert!(results[1].accum_dz > 0.0, "B dz = {}", results[1].accum_dz);
        assert_eq!(results[0].neighbor_count, 1);
        assert_eq!(results[1].neighbor_count, 1);

        // Symmetric magnitude (pair push is equal and opposite).
        assert!((results[0].accum_dx + results[1].accum_dx).abs() < 1e-12);
        assert!((results[0].accum_dz + results[1].accum_dz).abs() < 1e-12);

        // Magnitude check against hand computation:
        //   dx=0.5 dz=0.5 → f=0.5, sqrt(f)≈0.707, dn=0.707, g=1.0, push≈0.0354
        assert!((results[1].accum_dx - 0.03535534).abs() < 1e-6);
    }

    #[test]
    fn y_separated_mobs_dont_push() {
        let inputs = [
            make_mob(1, 0.0, 0.0, 64.0, 66.0),
            make_mob(2, 0.0, 0.0, 70.0, 72.0), // above; no Y overlap
        ];
        let mut results = [CrammingResult::default(); 2];
        compute_cramming(&inputs, &mut results);

        for r in &results {
            assert_eq!(r.accum_dx, 0.0);
            assert_eq!(r.accum_dz, 0.0);
            assert_eq!(r.neighbor_count, 0);
            assert_eq!(r.crowded_count, 0);
        }
    }

    #[test]
    fn three_mob_accumulation() {
        // A—B—C in a line along X, each 0.5 apart, all overlapping only their
        // immediate neighbor (half_sum = 0.6 > 0.5 but < 1.0).
        let inputs = [
            make_mob(1, 0.0, 0.0, 64.0, 66.0),
            make_mob(2, 0.5, 0.0, 64.0, 66.0),
            make_mob(3, 1.0, 0.0, 64.0, 66.0),
        ];
        let mut results = [CrammingResult::default(); 3];
        compute_cramming(&inputs, &mut results);

        // Neighbor counts: A↔B, B↔C. B is in the middle → 2 neighbors.
        assert_eq!(results[0].neighbor_count, 1);
        assert_eq!(results[1].neighbor_count, 2);
        assert_eq!(results[2].neighbor_count, 1);

        // A pushed -x (away from B); C pushed +x (away from B).
        assert!(results[0].accum_dx < 0.0);
        assert!(results[2].accum_dx > 0.0);
        // B is symmetric between A and C → its accumulated push is zero.
        assert!(results[1].accum_dx.abs() < 1e-12,
                "B accum_dx should be zero by symmetry, got {}", results[1].accum_dx);
        // Z is zero for everyone (all on same Z line).
        for r in &results {
            assert!(r.accum_dz.abs() < 1e-12);
        }
    }

    #[test]
    fn non_pushable_does_not_receive_push_but_pushes_neighbor() {
        // A is pushable, B is not pushable (vehicle-like). Vanilla still
        // increments neighbor_count and still pushes A, but skips push on B.
        let mut a = make_mob(1, 0.0, 0.0, 64.0, 66.0);
        let mut b = make_mob(2, 0.5, 0.0, 64.0, 66.0);
        b.flags = 0; // not pushable
        let inputs = [a, b];
        let mut results = [CrammingResult::default(); 2];
        // silence unused warning from mut above (kept for symmetry with test prep)
        a.flags |= FLAG_PUSHABLE;
        let _ = a;
        compute_cramming(&inputs, &mut results);

        assert!(results[0].accum_dx < 0.0, "A should still be pushed");
        assert_eq!(results[1].accum_dx, 0.0, "B is not pushable → no accum");
        assert_eq!(results[0].neighbor_count, 1);
        assert_eq!(results[1].neighbor_count, 1);
    }

    #[test]
    fn same_root_vehicle_skips_pair() {
        // Two mobs riding the same vehicle (root_vehicle_id == 100). Vanilla
        // isPassengerOfSameVehicle skips the entire push. Two -1s must NOT
        // count as same vehicle (they're not riding anything).
        let mut a = make_mob(1, 0.0, 0.0, 64.0, 66.0);
        let mut b = make_mob(2, 0.5, 0.0, 64.0, 66.0);
        a.root_vehicle_id = 100;
        b.root_vehicle_id = 100;
        let inputs = [a, b];
        let mut results = [CrammingResult::default(); 2];
        compute_cramming(&inputs, &mut results);

        assert_eq!(results[0].accum_dx, 0.0, "A should not be pushed");
        assert_eq!(results[1].accum_dx, 0.0, "B should not be pushed");
        assert_eq!(results[0].neighbor_count, 0);
        assert_eq!(results[1].neighbor_count, 0);
    }

    #[test]
    fn different_vehicle_or_no_vehicle_pushes_normally() {
        // Different root vehicles → push normally.
        let mut a = make_mob(1, 0.0, 0.0, 64.0, 66.0);
        let mut b = make_mob(2, 0.5, 0.0, 64.0, 66.0);
        a.root_vehicle_id = 100;
        b.root_vehicle_id = 200;
        let inputs = [a, b];
        let mut results = [CrammingResult::default(); 2];
        compute_cramming(&inputs, &mut results);
        assert!(results[0].accum_dx < 0.0);
        assert!(results[1].accum_dx > 0.0);

        // Both -1 (no vehicle) → push normally.
        let inputs2 = [make_mob(1, 0.0, 0.0, 64.0, 66.0), make_mob(2, 0.5, 0.0, 64.0, 66.0)];
        let mut results2 = [CrammingResult::default(); 2];
        compute_cramming(&inputs2, &mut results2);
        assert!(results2[0].accum_dx < 0.0);
    }

    #[test]
    fn crowded_count_only_counts_pushable_non_passenger() {
        // A overlaps B (pushable, passenger) and C (non-pushable, not
        // passenger). Vanilla pushEntities counts only pushable entities
        // and then only non-passenger ones — so A's crowded_count is 0.
        let a = make_mob(1, 0.0, 0.0, 64.0, 66.0);
        let mut b = make_mob(2, 0.4, 0.0, 64.0, 66.0);
        b.flags |= FLAG_PASSENGER;
        let mut c = make_mob(3, 0.0, 0.4, 64.0, 66.0);
        c.flags = 0; // not pushable, not passenger
        let inputs = [a, b, c];
        let mut results = [CrammingResult::default(); 3];
        compute_cramming(&inputs, &mut results);

        assert_eq!(results[0].neighbor_count, 2, "A overlaps both B and C");
        assert_eq!(results[0].crowded_count, 0,
                   "A's crowded_count excludes passengers (B) and non-pushables (C)");
    }

    #[test]
    fn crowded_count_counts_normal_pushable_neighbor() {
        // Two normal mobs overlapping → each sees crowded_count = 1.
        let inputs = [
            make_mob(1, 0.0, 0.0, 64.0, 66.0),
            make_mob(2, 0.5, 0.0, 64.0, 66.0),
        ];
        let mut results = [CrammingResult::default(); 2];
        compute_cramming(&inputs, &mut results);
        assert_eq!(results[0].crowded_count, 1);
        assert_eq!(results[1].crowded_count, 1);
    }

    #[test]
    fn no_physics_skips_pair_entirely() {
        let mut a = make_mob(1, 0.0, 0.0, 64.0, 66.0);
        a.flags |= FLAG_NO_PHYSICS;
        let b = make_mob(2, 0.5, 0.0, 64.0, 66.0);
        let inputs = [a, b];
        let mut results = [CrammingResult::default(); 2];
        compute_cramming(&inputs, &mut results);

        for r in &results {
            assert_eq!(r.accum_dx, 0.0);
            assert_eq!(r.neighbor_count, 0);
        }
    }
}
