//! Redstone wire-power BFS — Rust port of the algorithm validated
//! against vanilla by [RedstoneOracle] (Java BFS using vanilla's own
//! calculateWirePowerAt).
//!
//! Scope is DELIBERATELY narrow: given a serialized wire network with
//! known connectivity and known source-power contributions, compute
//! each wire's new power level. We do NOT:
//!   - read the world
//!   - resolve connectivity (Java figures that out from block states)
//!   - handle gate scheduling, torch burnout, or anything stateful
//!
//! All world-semantics work stays in Java; Rust is pure graph compute.
//!
//! Algorithm (iterative relaxation, matches vanilla's steady state):
//!   for iter in 0..16:
//!     changed = false
//!     for each node:
//!       new_power = max(source_power, max over neighbors of (neighbor_power - 1))
//!       if new_power != current: current[node] = new_power; changed = true
//!     if not changed: break
//!
//! 16 iterations upper bound because wire power decays by 1 per hop
//! and maxes at 15 — anything further than 15 hops from a source is 0.
//! On realistic networks (typically <100 nodes) this converges in
//! 2-5 iterations; the 16-iter cap is a correctness fence.
//!
//! No JNI surface here — redstone_jni.rs is the entry point.

use std::cmp::max;

// ============================================================================
// Request / Result — #[repr(C)] for direct ByteBuffer mapping.
// Layout must stay in sync with RedstoneHandoff.java.
// ============================================================================

/// Flags bits packed into RedstoneNode.flags (u16).
pub const FLAG_IS_WIRE:       u16 = 1 << 0;
/// Reserved for future: non-wire source nodes (torches, redstone blocks,
/// repeater outputs) that feed power into the network but don't
/// propagate. For v1 we bake source contributions into source_power
/// directly, so this flag isn't read yet.
pub const FLAG_IS_SOURCE_ONLY: u16 = 1 << 1;

/// Sentinel for "no neighbor in this slot."
pub const NO_NEIGHBOR: i32 = -1;

/// Max neighbors per wire node: 4 horizontal + 4 step-neighbors
/// (each horizontal contributes at most one up-step OR down-step).
pub const NEIGHBOR_SLOTS: usize = 8;

#[repr(C)]
#[derive(Copy, Clone, Debug)]
pub struct RedstoneNode {
    pub x:                  i32,                        // +0
    pub y:                  i32,                        // +4
    pub z:                  i32,                        // +8
    pub current_power:      u8,                         // +12
    pub source_power:       u8,                         // +13
    pub flags:              u16,                        // +14
    pub neighbor_indices:   [i32; NEIGHBOR_SLOTS],      // +16..+48
}
const _: [(); 48] = [(); std::mem::size_of::<RedstoneNode>()];

#[repr(C)]
#[derive(Copy, Clone, Debug, Default)]
pub struct RedstoneResult {
    pub x:          i32,    // +0
    pub y:          i32,    // +4
    pub z:          i32,    // +8
    pub new_power:  i32,    // +12  (i32 rather than u8 so the field
                            //       naturally aligns; valid range 0..=15)
}
const _: [(); 16] = [(); std::mem::size_of::<RedstoneResult>()];

// ============================================================================
// BFS / relaxation
// ============================================================================

/// Runs the relaxation loop on `nodes`. Writes an entry to `results` for
/// every node whose new power differs from its current power. Returns
/// the count of changed nodes (how much of `results` was populated).
///
/// `results.len()` must be >= `nodes.len()`.
pub fn compute_wire_power(nodes: &[RedstoneNode], results: &mut [RedstoneResult]) -> usize {
    let n = nodes.len();
    if n == 0 {
        return 0;
    }
    debug_assert!(results.len() >= n);

    // Work vector: current power level per node. Seed with source power
    // (non-wire contributions) so the first relaxation step can max
    // against neighbors.
    let mut power: Vec<u8> = nodes.iter().map(|n| n.source_power).collect();

    // Max 16 iterations — power decays by 1 per hop, and 15 is the max
    // level, so after 16 passes any stable network has converged.
    for _ in 0..16 {
        let mut changed = false;
        for (i, node) in nodes.iter().enumerate() {
            let mut new_power = node.source_power;
            for &ni in node.neighbor_indices.iter() {
                if ni == NO_NEIGHBOR {
                    continue;
                }
                let ni_u = ni as usize;
                if ni_u >= n {
                    // Malformed input; ignore rather than panic.
                    continue;
                }
                let np = power[ni_u];
                if np > 0 {
                    new_power = max(new_power, np - 1);
                }
            }
            if new_power != power[i] {
                power[i] = new_power;
                changed = true;
            }
        }
        if !changed {
            break;
        }
    }

    // Emit deltas only. Java applies them as setBlockState calls.
    let mut count = 0usize;
    for (i, node) in nodes.iter().enumerate() {
        if power[i] != node.current_power {
            results[count] = RedstoneResult {
                x: node.x,
                y: node.y,
                z: node.z,
                new_power: power[i] as i32,
            };
            count += 1;
        }
    }
    count
}

// ============================================================================
// Tests
// ============================================================================

#[cfg(test)]
mod tests {
    use super::*;

    fn node(x: i32, source: u8, current: u8, neighbors: &[i32]) -> RedstoneNode {
        let mut ni = [NO_NEIGHBOR; NEIGHBOR_SLOTS];
        for (slot, &n) in ni.iter_mut().zip(neighbors) {
            *slot = n;
        }
        RedstoneNode {
            x, y: 0, z: 0,
            current_power: current,
            source_power: source,
            flags: FLAG_IS_WIRE,
            neighbor_indices: ni,
        }
    }

    #[test]
    fn single_source_decays_along_chain() {
        // source 15 → 4 wires in a line; expect 15, 14, 13, 12, 11
        // (first node is itself the source, so current_power starts 0 everywhere
        //  except source_power on node 0)
        let nodes = vec![
            node(0, 15, 0, &[1]),
            node(1, 0,  0, &[0, 2]),
            node(2, 0,  0, &[1, 3]),
            node(3, 0,  0, &[2, 4]),
            node(4, 0,  0, &[3]),
        ];
        let mut results = vec![RedstoneResult::default(); nodes.len()];
        let count = compute_wire_power(&nodes, &mut results);
        assert_eq!(count, 5);
        let by_x: std::collections::HashMap<_, _> =
            results[..count].iter().map(|r| (r.x, r.new_power)).collect();
        assert_eq!(by_x[&0], 15);
        assert_eq!(by_x[&1], 14);
        assert_eq!(by_x[&2], 13);
        assert_eq!(by_x[&3], 12);
        assert_eq!(by_x[&4], 11);
    }

    #[test]
    fn two_sources_take_max() {
        // 10 at left, 8 at right, 3 wires between → middle wire should be 9
        let nodes = vec![
            node(0, 10, 0, &[1]),
            node(1, 0,  0, &[0, 2]),
            node(2, 0,  0, &[1, 3]),
            node(3, 0,  0, &[2, 4]),
            node(4, 8,  0, &[3]),
        ];
        let mut results = vec![RedstoneResult::default(); nodes.len()];
        let count = compute_wire_power(&nodes, &mut results);
        let by_x: std::collections::HashMap<_, _> =
            results[..count].iter().map(|r| (r.x, r.new_power)).collect();
        assert_eq!(by_x[&0], 10);
        assert_eq!(by_x[&1], 9);
        assert_eq!(by_x[&2], 8);  // max(10-2, 8-2) = max(8, 6) = 8
        assert_eq!(by_x[&3], 8);
        assert_eq!(by_x[&4], 8);
    }

    #[test]
    fn no_source_stays_zero() {
        let nodes = vec![
            node(0, 0, 0, &[1]),
            node(1, 0, 0, &[0]),
        ];
        let mut results = vec![RedstoneResult::default(); nodes.len()];
        let count = compute_wire_power(&nodes, &mut results);
        // Nothing changes (0 → 0), so no deltas emitted.
        assert_eq!(count, 0);
    }

    #[test]
    fn already_at_target_emits_no_delta() {
        // Chain pre-seeded to steady state; BFS computes the same answer,
        // no deltas should be emitted.
        let nodes = vec![
            node(0, 15, 15, &[1]),
            node(1, 0,  14, &[0, 2]),
            node(2, 0,  13, &[1]),
        ];
        let mut results = vec![RedstoneResult::default(); nodes.len()];
        let count = compute_wire_power(&nodes, &mut results);
        assert_eq!(count, 0);
    }
}
