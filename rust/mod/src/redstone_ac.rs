//! Alternate-Current offer-based wire propagation kernel.
//!
//! Sister to `redstone.rs`'s relaxation kernel. The relaxation kernel
//! converges to the correct steady-state power values but loses the
//! flow-direction information AC's emission path needs to drive
//! deterministic block/shape updates. This kernel is the closer port
//! of AC's `powerNetwork()` loop:
//!
//!   - Each wire holds (virtualPower, flowIn, currentPower, flags)
//!   - Wires with non-zero externalPower seed a priority queue
//!   - Drain queue in descending-priority order; for each popped wire,
//!     transmit (vp-1) to each connected neighbor
//!   - Neighbor's offerPower decides: if equal, OR direction into
//!     flowIn; if greater, replace vp + reset flowIn to single direction
//!   - Bumped neighbors get re-queued at their new priority
//!   - Wires processed at most once (the higher-priority entry wins)
//!
//! Output is **in priority order** (descending virtualPower, FIFO
//! within tier). Java side iterates results and emits setBlockState +
//! queueNeighbors + updateNeighborShapes in that order, no re-sort.
//!
//! ## Limitations of v1
//!
//! All wire-to-wire connections are treated as bidirectional. Vanilla
//! AC tracks per-edge `offer`/`accept` flags on `WireConnection` to
//! handle the rare case where a wire connects through a conductor to
//! another wire one-way (e.g. an up-step into a covered well). Phase 3
//! oracle validation will surface any divergence; if a contraption
//! shape breaks, we add a bit per neighbor for the offer flag and
//! revisit.

use std::cell::RefCell;

use crate::redstone_queues::PriorityQueue;

// ============================================================================
// Layout constants
// ============================================================================

/// Max neighbors per wire node: 4 horizontal + up + down + headroom.
pub const NEIGHBOR_SLOTS: usize = 8;
/// Sentinel value in `neighbor_indices` for "no neighbor in this slot".
pub const NO_NEIGHBOR: i32 = -1;
/// Sentinel value in `neighbor_i_dir` for "this slot is unused".
pub const DIR_NONE: u8 = 0xFF;

// Direction indices, mirror WireConstants.Directions in Java.
pub const DIR_WEST:  u8 = 0;
pub const DIR_NORTH: u8 = 1;
pub const DIR_EAST:  u8 = 2;
pub const DIR_SOUTH: u8 = 3;
pub const DIR_DOWN:  u8 = 4;
pub const DIR_UP:    u8 = 5;

// Request flags (bits in RedstoneAcNode.flags)
pub const FLAG_REMOVED:      u8 = 1 << 0;
pub const FLAG_SHOULD_BREAK: u8 = 1 << 1;
pub const FLAG_ROOT:         u8 = 1 << 2;
pub const FLAG_ADDED:        u8 = 1 << 3;

// Result flags (bits in RedstoneAcResult.result_flags)
/// Wire was removed or shouldBreak. Java should NOT call
/// setBlockState(POWER, new_power); instead drop items and replace
/// with AIR. The new_power field is ignored when this bit is set.
pub const RESULT_FLAG_REMOVED: u8 = 1 << 0;

/// Mirror of `WireConstants.FLOW_IN_TO_FLOW_OUT` from the Java side.
/// Index = 4-bit incoming-flow mask (NESW); value = resolved outgoing
/// direction index, or -1 if ambiguous (Java falls back to
/// `wire.connections.iFlowDir`).
pub const FLOW_IN_TO_FLOW_OUT: [i8; 16] = [
    -1,                  // 0b0000: -                     -> ambiguous
    DIR_WEST  as i8,     // 0b0001: west                  -> west
    DIR_NORTH as i8,     // 0b0010: north                 -> north
    DIR_NORTH as i8,     // 0b0011: west|north            -> north
    DIR_EAST  as i8,     // 0b0100: east                  -> east
    -1,                  // 0b0101: west|east             -> ambiguous
    DIR_EAST  as i8,     // 0b0110: north|east            -> east
    DIR_NORTH as i8,     // 0b0111: west|north|east       -> north
    DIR_SOUTH as i8,     // 0b1000: south                 -> south
    DIR_WEST  as i8,     // 0b1001: west|south            -> west
    -1,                  // 0b1010: north|south           -> ambiguous
    DIR_WEST  as i8,     // 0b1011: west|north|south      -> west
    DIR_SOUTH as i8,     // 0b1100: east|south            -> south
    DIR_SOUTH as i8,     // 0b1101: west|east|south       -> south
    DIR_EAST  as i8,     // 0b1110: north|east|south      -> east
    -1,                  // 0b1111: west|north|east|south -> ambiguous
];

/// Returns the index of the opposite direction. Mirrors
/// `WireConstants.Directions.iOpposite`: cardinals XOR 0b10, vertical
/// pair (DOWN/UP) XOR 0b01.
#[inline]
fn i_opposite(i_dir: u8) -> u8 {
    if i_dir < 4 {
        i_dir ^ 0b10
    } else {
        i_dir ^ 0b01
    }
}

// ============================================================================
// Request / Result structs (#[repr(C)] for direct ByteBuffer mapping)
// Layout MUST stay in sync with RedstoneHandoff.java's writeAcNode
// and readAcResult* accessors.
// ============================================================================

#[repr(C)]
#[derive(Copy, Clone, Debug)]
pub struct RedstoneAcNode {
    pub x:                  i32,                            // +0
    pub y:                  i32,                            // +4
    pub z:                  i32,                            // +8
    pub current_power:      u8,                             // +12
    pub external_power:     u8,                             // +13
    pub initial_flow_in:    u8,                             // +14  (4-bit NESW mask)
    pub flags:              u8,                             // +15  (FLAG_*)
    pub neighbor_indices:   [i32; NEIGHBOR_SLOTS],          // +16..+48
    pub neighbor_i_dir:     [u8;  NEIGHBOR_SLOTS],          // +48..+56  (DIR_* or DIR_NONE)
}
const _: [(); 56] = [(); std::mem::size_of::<RedstoneAcNode>()];

#[repr(C)]
#[derive(Copy, Clone, Debug, Default)]
pub struct RedstoneAcResult {
    pub x:            i32,    // +0
    pub y:            i32,    // +4
    pub z:            i32,    // +8
    pub new_power:    i32,    // +12  (0..=15)
    pub new_flow_in:  u8,     // +16  (4-bit NESW mask)
    pub result_flags: u8,     // +17  (RESULT_FLAG_*)
    pub i_flow_dir:   i8,     // +18  (DIR_*, or -1 if ambiguous)
    pub _pad:         [u8; 5],// +19..+24
}
const _: [(); 24] = [(); std::mem::size_of::<RedstoneAcResult>()];

// ============================================================================
// Reusable per-cascade scratch
// ============================================================================
//
// Same pattern as the relaxation kernel: thread_local + clear() lets
// the cascade-per-tick path reuse buffers across calls without
// reallocation. AC_QUEUE persists too — PriorityQueue::clear() resets
// state without dropping bucket VecDeques.
thread_local! {
    static AC_VIRTUAL_POWER: RefCell<Vec<i16>>          = RefCell::new(Vec::with_capacity(1024));
    static AC_FLOW_IN:       RefCell<Vec<u8>>           = RefCell::new(Vec::with_capacity(1024));
    static AC_EMITTED:       RefCell<Vec<bool>>         = RefCell::new(Vec::with_capacity(1024));
    static AC_QUEUE:         RefCell<PriorityQueue>     = RefCell::new(PriorityQueue::with_capacity(64));
}

// ============================================================================
// Kernel
// ============================================================================

/// Run AC's offer-based propagation on the serialized wire network.
/// Writes one entry per wire whose new power differs from `current_power`
/// (or that is removed/shouldBreak) into `results` in priority order
/// (descending `new_power`, FIFO within tier). Returns the populated
/// count.
///
/// `results.len()` must be >= `nodes.len()`.
pub fn compute_wire_power_ac(
    nodes: &[RedstoneAcNode],
    results: &mut [RedstoneAcResult],
) -> usize {
    let n = nodes.len();
    if n == 0 {
        return 0;
    }
    debug_assert!(results.len() >= n);

    AC_VIRTUAL_POWER.with(|vp_ref| {
        AC_FLOW_IN.with(|fi_ref| {
            AC_EMITTED.with(|em_ref| {
                AC_QUEUE.with(|q_ref| {
                    let mut virtual_power = vp_ref.borrow_mut();
                    let mut flow_in       = fi_ref.borrow_mut();
                    let mut emitted       = em_ref.borrow_mut();
                    let mut queue         = q_ref.borrow_mut();

                    // Reset scratch to per-call sizes.
                    virtual_power.clear();
                    virtual_power.resize(n, -1);
                    flow_in.clear();
                    flow_in.extend(nodes.iter().map(|node| node.initial_flow_in & 0x0F));
                    emitted.clear();
                    emitted.resize(n, false);
                    queue.clear();

                    // ----- Phase 1: depower equivalent -----
                    // Wires with external power seed at that priority.
                    // Wires without external power (and not flagged
                    // removed/shouldBreak) start at vp=0 priority 0 so
                    // they emit a delta if their currentPower > 0.
                    for (i, node) in nodes.iter().enumerate() {
                        let dead = (node.flags & (FLAG_REMOVED | FLAG_SHOULD_BREAK)) != 0;
                        if dead {
                            virtual_power[i] = 0;
                            queue.offer(i as u32, 0);
                        } else if node.external_power > 0 {
                            virtual_power[i] = node.external_power as i16;
                            queue.offer(i as u32, node.external_power);
                        } else {
                            virtual_power[i] = 0;
                            queue.offer(i as u32, 0);
                        }
                    }

                    // ----- Phase 2: drain queue + emit inline -----
                    // Drain order is priority order, so writing results
                    // at the moment of pop gives Java a pre-ordered
                    // result array.
                    let mut count = 0usize;

                    while let Some(idx) = queue.poll() {
                        let i = idx as usize;
                        if i >= n || emitted[i] {
                            continue;
                        }
                        emitted[i] = true;

                        let node = &nodes[i];
                        let dead = (node.flags & (FLAG_REMOVED | FLAG_SHOULD_BREAK)) != 0;

                        // Dead wires don't transmit; they only emit.
                        if !dead {
                            let vp = virtual_power[i].max(0) as u8;
                            if vp > 0 {
                                let outgoing = vp - 1;
                                for slot in 0..NEIGHBOR_SLOTS {
                                    let nidx = node.neighbor_indices[slot];
                                    if nidx < 0 {
                                        continue;
                                    }
                                    let nidx_u = nidx as usize;
                                    if nidx_u >= n || emitted[nidx_u] {
                                        continue;
                                    }
                                    let outgoing_dir = node.neighbor_i_dir[slot];
                                    if outgoing_dir == DIR_NONE {
                                        continue;
                                    }
                                    // Mirror AC's WireNode.offerPower: a
                                    // removed/shouldBreak wire rejects any
                                    // incoming offer at the top of the
                                    // method. If we let dead wires accept,
                                    // they end up with non-zero virtual_power
                                    // and emit incorrect new_power.
                                    let neighbor_dead = (nodes[nidx_u].flags
                                        & (FLAG_REMOVED | FLAG_SHOULD_BREAK)) != 0;
                                    if neighbor_dead {
                                        continue;
                                    }
                                    // From the receiver's perspective,
                                    // power came from the opposite side.
                                    let receiver_in_dir = i_opposite(outgoing_dir);

                                    // offerPower three-branch logic:
                                    let neighbor_vp = virtual_power[nidx_u];
                                    let outgoing_i16 = outgoing as i16;
                                    if outgoing_i16 == neighbor_vp {
                                        // Equal: OR direction into flowIn.
                                        // Only horizontal directions
                                        // contribute to flowIn (matches
                                        // AC: vertical neighbors don't
                                        // affect flow-direction tracking).
                                        if receiver_in_dir < 4 {
                                            flow_in[nidx_u] |= 1 << receiver_in_dir;
                                        }
                                    } else if outgoing_i16 > neighbor_vp {
                                        // Bump: replace vp + reset flowIn
                                        // to just this direction.
                                        virtual_power[nidx_u] = outgoing_i16;
                                        flow_in[nidx_u] = if receiver_in_dir < 4 {
                                            1 << receiver_in_dir
                                        } else {
                                            0
                                        };
                                        queue.offer(nidx as u32, outgoing);
                                    }
                                    // outgoing < neighbor_vp: ignore.
                                }
                            }
                        }

                        // Emit if anything user-visible changed.
                        let new_power = virtual_power[i].max(0).min(15) as u8;
                        let result_flags = if dead { RESULT_FLAG_REMOVED } else { 0 };
                        let needs_emit = result_flags != 0 || new_power != node.current_power;
                        if needs_emit {
                            let mask = (flow_in[i] as usize) & 0x0F;
                            let i_flow_dir = FLOW_IN_TO_FLOW_OUT[mask];
                            results[count] = RedstoneAcResult {
                                x: node.x,
                                y: node.y,
                                z: node.z,
                                new_power: new_power as i32,
                                new_flow_in: flow_in[i],
                                result_flags,
                                i_flow_dir,
                                _pad: [0; 5],
                            };
                            count += 1;
                        }
                    }

                    count
                })
            })
        })
    })
}

// ============================================================================
// Tests
// ============================================================================

#[cfg(test)]
mod tests {
    use super::*;

    /// Build a node where slot[k] points at neighbor at index `nbrs[k]`
    /// in the given outgoing direction `dirs[k]`. Trailing slots get
    /// (NO_NEIGHBOR, DIR_NONE).
    fn node_with_flow(
        x: i32,
        external: u8,
        current: u8,
        nbrs: &[(i32, u8)],
    ) -> RedstoneAcNode {
        let mut neighbor_indices = [NO_NEIGHBOR; NEIGHBOR_SLOTS];
        let mut neighbor_i_dir = [DIR_NONE; NEIGHBOR_SLOTS];
        for (slot, &(nidx, ndir)) in nbrs.iter().enumerate() {
            neighbor_indices[slot] = nidx;
            neighbor_i_dir[slot] = ndir;
        }
        RedstoneAcNode {
            x,
            y: 0,
            z: 0,
            current_power: current,
            external_power: external,
            initial_flow_in: 0,
            flags: 0,
            neighbor_indices,
            neighbor_i_dir,
        }
    }

    /// Convenience: simple linear chain with bidirectional connections
    /// where each wire connects to its immediate neighbors via WEST/EAST.
    fn linear_chain(externals: &[u8]) -> Vec<RedstoneAcNode> {
        let n = externals.len();
        externals.iter().enumerate().map(|(i, &ext)| {
            let mut nbrs: Vec<(i32, u8)> = Vec::new();
            // Connect to previous (we are EAST of them, so neighbor is WEST of us:
            // outgoing direction from us to them is WEST).
            if i > 0 {
                nbrs.push(((i - 1) as i32, DIR_WEST));
            }
            if i + 1 < n {
                nbrs.push(((i + 1) as i32, DIR_EAST));
            }
            node_with_flow(i as i32, ext, 0, &nbrs)
        }).collect()
    }

    #[test]
    fn struct_layouts_match_planned_strides() {
        assert_eq!(std::mem::size_of::<RedstoneAcNode>(), 56);
        assert_eq!(std::mem::size_of::<RedstoneAcResult>(), 24);
    }

    #[test]
    fn flow_in_to_flow_out_table_matches_java() {
        // Spot-check a few values that mirror WireConstants.FLOW_IN_TO_FLOW_OUT.
        // Java's table verbatim:
        let expected: [i8; 16] = [
            -1, 0, 1, 1, 2, -1, 2, 1,
            3, 0, -1, 0, 3, 3, 2, -1,
        ];
        assert_eq!(FLOW_IN_TO_FLOW_OUT, expected);
    }

    #[test]
    fn i_opposite_matches_java_table() {
        // Cardinals: WEST<->EAST, NORTH<->SOUTH
        assert_eq!(i_opposite(DIR_WEST),  DIR_EAST);
        assert_eq!(i_opposite(DIR_EAST),  DIR_WEST);
        assert_eq!(i_opposite(DIR_NORTH), DIR_SOUTH);
        assert_eq!(i_opposite(DIR_SOUTH), DIR_NORTH);
        // Vertical: DOWN<->UP
        assert_eq!(i_opposite(DIR_DOWN), DIR_UP);
        assert_eq!(i_opposite(DIR_UP),   DIR_DOWN);
    }

    #[test]
    fn single_source_decays_along_chain() {
        // Source 15 at index 0; chain of 5 wires.
        // Expected powers: 15, 14, 13, 12, 11.
        let nodes = linear_chain(&[15, 0, 0, 0, 0]);
        let mut results = vec![RedstoneAcResult::default(); nodes.len()];
        let count = compute_wire_power_ac(&nodes, &mut results);
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
    fn results_are_in_descending_priority_order() {
        // Same chain. The natural drain order is power 15 first, then
        // 14, 13, 12, 11. Verify results array is non-increasing in
        // new_power.
        let nodes = linear_chain(&[15, 0, 0, 0, 0]);
        let mut results = vec![RedstoneAcResult::default(); nodes.len()];
        let count = compute_wire_power_ac(&nodes, &mut results);
        assert_eq!(count, 5);
        for i in 1..count {
            assert!(
                results[i - 1].new_power >= results[i].new_power,
                "results not in descending priority: [{}]={} but [{}]={}",
                i - 1, results[i - 1].new_power, i, results[i].new_power
            );
        }
    }

    #[test]
    fn two_sources_take_max() {
        // 10 at left, 8 at right, chain 5 wires.
        // Expected: 10, 9, 8, 7, 8.
        let nodes = linear_chain(&[10, 0, 0, 0, 8]);
        let mut results = vec![RedstoneAcResult::default(); nodes.len()];
        let count = compute_wire_power_ac(&nodes, &mut results);
        let by_x: std::collections::HashMap<_, _> =
            results[..count].iter().map(|r| (r.x, r.new_power)).collect();
        assert_eq!(by_x[&0], 10);
        assert_eq!(by_x[&1], 9);
        assert_eq!(by_x[&2], 8);
        assert_eq!(by_x[&3], 7);
        assert_eq!(by_x[&4], 8);
    }

    #[test]
    fn no_source_emits_no_delta_when_currents_already_zero() {
        // Two wires, no source, currentPower = 0 on both. Expect
        // 0 deltas (no change).
        let nodes = linear_chain(&[0, 0]);
        let mut results = vec![RedstoneAcResult::default(); nodes.len()];
        let count = compute_wire_power_ac(&nodes, &mut results);
        assert_eq!(count, 0);
    }

    #[test]
    fn lost_source_drains_chain() {
        // Chain previously powered (currentPower=15,14,13) but source
        // is gone (externalPower=0). All three should drain to 0 and
        // emit deltas.
        let n = 3;
        let mut nodes: Vec<RedstoneAcNode> = (0..n).map(|i| {
            let mut nbrs: Vec<(i32, u8)> = Vec::new();
            if i > 0 { nbrs.push(((i - 1) as i32, DIR_WEST)); }
            if i + 1 < n { nbrs.push(((i + 1) as i32, DIR_EAST)); }
            node_with_flow(i as i32, 0, (15 - i) as u8, &nbrs)
        }).collect();
        let mut results = vec![RedstoneAcResult::default(); nodes.len()];
        let count = compute_wire_power_ac(&nodes, &mut results);
        assert_eq!(count, 3);
        for r in &results[..count] {
            assert_eq!(r.new_power, 0);
        }

        // Avoid "unused mut" lint.
        nodes[0].current_power = 15;
    }

    #[test]
    fn already_at_target_emits_no_delta() {
        // Pre-seeded steady state: source 15, decaying through 14, 13.
        // currentPower already matches the new powers AC will compute.
        let nodes = vec![
            node_with_flow(0, 15, 15, &[(1, DIR_EAST)]),
            node_with_flow(1, 0,  14, &[(0, DIR_WEST), (2, DIR_EAST)]),
            node_with_flow(2, 0,  13, &[(1, DIR_WEST)]),
        ];
        let mut results = vec![RedstoneAcResult::default(); nodes.len()];
        let count = compute_wire_power_ac(&nodes, &mut results);
        assert_eq!(count, 0);
    }

    #[test]
    fn flow_direction_resolves_for_single_source() {
        // Source 15 west of wire-1; wire-1 east of wire-0.
        // Wire-1 receives power from WEST, so flow_in_mask = 0b0001,
        // FLOW_IN_TO_FLOW_OUT[1] = WEST (0).
        let nodes = linear_chain(&[15, 0, 0]);
        let mut results = vec![RedstoneAcResult::default(); nodes.len()];
        let count = compute_wire_power_ac(&nodes, &mut results);
        // Find wire at x=1
        let r1 = results[..count].iter().find(|r| r.x == 1).expect("wire 1 missing");
        assert_eq!(r1.new_flow_in, 1 << DIR_WEST);
        assert_eq!(r1.i_flow_dir, DIR_WEST as i8);
    }

    #[test]
    fn equal_offers_or_into_flow_in() {
        // Three wires in a row: w0 (source 10), w1 (middle), w2 (source 10).
        // w1 receives 9 from both sides. Both offers equal -> flow_in
        // gets WEST and EAST bits set (mask = 0b0101). FLOW_IN_TO_FLOW_OUT[5]
        // = -1 (ambiguous). Java would fall back to connections.iFlowDir.
        let nodes = vec![
            node_with_flow(0, 10, 0, &[(1, DIR_EAST)]),
            node_with_flow(1, 0,  0, &[(0, DIR_WEST), (2, DIR_EAST)]),
            node_with_flow(2, 10, 0, &[(1, DIR_WEST)]),
        ];
        let mut results = vec![RedstoneAcResult::default(); nodes.len()];
        let count = compute_wire_power_ac(&nodes, &mut results);
        let r1 = results[..count].iter().find(|r| r.x == 1).expect("wire 1 missing");
        assert_eq!(r1.new_power, 9);
        assert_eq!(r1.new_flow_in, (1 << DIR_WEST) | (1 << DIR_EAST));
        assert_eq!(r1.i_flow_dir, -1, "ambiguous mask should yield -1");
    }

    #[test]
    fn removed_wire_emits_with_flag() {
        // Wire flagged removed; should emit RESULT_FLAG_REMOVED with
        // new_power = 0 regardless of any power that would otherwise
        // reach it.
        let mut nodes = linear_chain(&[15, 0]);
        nodes[1].flags |= FLAG_REMOVED;
        nodes[1].current_power = 14; // pretend it was previously powered
        let mut results = vec![RedstoneAcResult::default(); nodes.len()];
        let count = compute_wire_power_ac(&nodes, &mut results);
        let r1 = results[..count].iter().find(|r| r.x == 1).expect("removed wire missing");
        assert_eq!(r1.new_power, 0);
        assert_ne!(r1.result_flags & RESULT_FLAG_REMOVED, 0);
    }

    #[test]
    fn should_break_wire_emits_with_flag() {
        let mut nodes = linear_chain(&[15, 0]);
        nodes[1].flags |= FLAG_SHOULD_BREAK;
        nodes[1].current_power = 14;
        let mut results = vec![RedstoneAcResult::default(); nodes.len()];
        let count = compute_wire_power_ac(&nodes, &mut results);
        let r1 = results[..count].iter().find(|r| r.x == 1).expect("shouldBreak wire missing");
        assert_eq!(r1.new_power, 0);
        assert_ne!(r1.result_flags & RESULT_FLAG_REMOVED, 0);
    }

    #[test]
    fn priority_queue_handles_late_higher_offer() {
        // Wire chain where a far-source's late offer beats an early
        // close-source's offer. Verifies the queue's re-queue path.
        // w0 (source 5), w1 -- w2 (source 12 reached after w0's offer).
        // w1 first gets 4 from w0, then 11 from w2 via w1->w2 backflow...
        // actually with linear chain and bidirectional, w2 source 12
        // dominates: w1 ends at 11, w0 ends at 10 (10 from w2 vs own
        // 5 source — Wait, w0 has source=5. Power offered to w0 is
        // 11-1=10 from w1. 10 > 5, so w0 takes 10. Final powers:
        // w2=12, w1=11, w0=10.
        let nodes = linear_chain(&[5, 0, 12]);
        let mut results = vec![RedstoneAcResult::default(); nodes.len()];
        let count = compute_wire_power_ac(&nodes, &mut results);
        let by_x: std::collections::HashMap<_, _> =
            results[..count].iter().map(|r| (r.x, r.new_power)).collect();
        assert_eq!(by_x[&0], 10);
        assert_eq!(by_x[&1], 11);
        assert_eq!(by_x[&2], 12);
    }
}
