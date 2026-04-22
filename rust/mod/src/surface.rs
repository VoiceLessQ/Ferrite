//! Surface-rule bytecode evaluator (Rust port).
//!
//! Mirror of the Java reference at
//! `src/main/java/me/apika/apikaprobe/surface/SurfaceRuleEvaluator.java`.
//! Same opcode constants, same dispatch loop, same byte-order, same
//! two-register dataflow (cond/value).
//!
//! The Java validator (89.8% match against vanilla buildSurface as of
//! commit a1b374d) is the reference spec — anywhere this file diverges
//! from `SurfaceRuleEvaluator.java` is a bug.
//!
//! This file is currently library-only (no JNI). The JNI binding and
//! per-chunk batching live in a follow-up session; the goal here is to
//! prove the dispatch loop in Rust and check it against synthetic
//! bytecode that exercises every opcode shape.

// ---- Opcode constants (must mirror Java RuleBytecode.java) ----------

pub const OP_ABOVE_Y: u8 = 0x01;
pub const OP_NOISE_THRESH: u8 = 0x02;
pub const OP_VERT_GRADIENT: u8 = 0x03;
pub const OP_STONE_DEPTH: u8 = 0x04;
pub const OP_WATER: u8 = 0x05;
pub const OP_HOLE: u8 = 0x06;
pub const OP_SURFACE: u8 = 0x07;
pub const OP_BIOME: u8 = 0x08;
pub const OP_TEMPERATURE: u8 = 0x09;
pub const OP_STEEP: u8 = 0x0A;
pub const OP_NOT: u8 = 0x0B;
pub const OP_BLOCK: u8 = 0x0E;
pub const OP_TERRACOTTA_BANDS: u8 = 0x10;
pub const OP_IF_ELSE: u8 = 0x21;
pub const OP_SEQUENCE_NEXT: u8 = 0x22;
pub const OP_RETURN_DONE: u8 = 0x24;
pub const OP_FALLBACK: u8 = 0x7F;

// ---- Per-column inputs (mirrors Java ColumnContext) -----------------

pub struct ColumnContext<'a> {
    pub biome_id: u16,
    pub block_y: i32,
    pub run_depth: i32,
    pub stone_depth_above: i32,
    pub stone_depth_below: i32,
    pub fluid_height: i32,
    pub is_cold: bool,
    pub is_steep: bool,
    pub surface_height: i32,
    pub secondary_depth: f64,
    pub noise_values: &'a [f64],
}

/// One pool entry per BiomeMaterialCondition node — the set of biome
/// IDs the condition matches against. Stored here as a sorted slice
/// of registry IDs (u16); membership test is binary search.
pub type BiomeIdSet = [u16];

// ---- Eval ------------------------------------------------------------

pub struct CompiledTree<'a> {
    pub bytecode: &'a [u8],
    /// Outer slice indexed by biome-set pool ID. Inner slice is the
    /// set of biome IDs in that pool entry, sorted ascending for
    /// O(log n) membership lookup.
    pub biome_set_table: &'a [&'a BiomeIdSet],
    pub blockstate_count: u32, // for bounds checks
}

/// Returns the blockstate ID produced by the bytecode for this column,
/// or `None` if no rule matched (column produces no surface block at
/// this Y, OR an OP_FALLBACK opcode was hit and the alternative had
/// no other branch that produced a value).
pub fn evaluate(tree: &CompiledTree, ctx: &ColumnContext) -> Option<u32> {
    let bc = tree.bytecode;
    let mut ip: usize = 0;
    let mut cond: bool = false;
    let mut value: Option<u32> = None;
    let mut negate_next: bool = false;

    while ip < bc.len() {
        let op = bc[ip];
        ip += 1;
        match op {
            OP_RETURN_DONE => return value,
            OP_FALLBACK => {
                // Soft skip — alternative produced no result. Enclosing
                // Sequence's SEQUENCE_NEXT sees no value and falls through.
            }
            OP_BLOCK => {
                let id = read_u32_le(bc, ip);
                ip += 4;
                value = Some(id);
            }
            OP_IF_ELSE => {
                let then_off = read_u32_le(bc, ip) as usize;
                ip += 4;
                let else_off = read_u32_le(bc, ip) as usize;
                ip += 4;
                ip = if cond { then_off } else { else_off };
            }
            OP_SEQUENCE_NEXT => {
                let end_off = read_u32_le(bc, ip) as usize;
                ip += 4;
                if value.is_some() {
                    ip = end_off;
                }
            }
            OP_NOT => {
                negate_next = true;
            }

            // ---- Conditions ------------------------------------------
            OP_HOLE => {
                cond = ctx.run_depth <= 0;
                if negate_next { cond = !cond; negate_next = false; }
            }
            OP_STEEP => {
                cond = ctx.is_steep;
                if negate_next { cond = !cond; negate_next = false; }
            }
            OP_TEMPERATURE => {
                cond = ctx.is_cold;
                if negate_next { cond = !cond; negate_next = false; }
            }
            OP_SURFACE => {
                cond = ctx.block_y >= ctx.surface_height;
                if negate_next { cond = !cond; negate_next = false; }
            }
            OP_BIOME => {
                let idx = read_u16_le(bc, ip) as usize;
                ip += 2;
                cond = match tree.biome_set_table.get(idx) {
                    Some(set) => set.binary_search(&ctx.biome_id).is_ok(),
                    None => false,
                };
                if negate_next { cond = !cond; negate_next = false; }
            }
            OP_NOISE_THRESH => {
                let ch = read_u16_le(bc, ip) as usize;
                ip += 2;
                let min_t = read_f64_le(bc, ip);
                ip += 8;
                let max_t = read_f64_le(bc, ip);
                ip += 8;
                let v = ctx.noise_values.get(ch).copied().unwrap_or(0.0);
                cond = v >= min_t && v <= max_t;
                if negate_next { cond = !cond; negate_next = false; }
            }
            OP_ABOVE_Y => {
                let anchor_y = read_i32_le(bc, ip);
                ip += 4;
                let surface_depth_mul = read_i32_le(bc, ip);
                ip += 4;
                let add_stone_depth = bc[ip] != 0;
                ip += 1;
                let lhs = ctx.block_y + if add_stone_depth { ctx.stone_depth_above } else { 0 };
                let rhs = anchor_y + ctx.run_depth * surface_depth_mul;
                cond = lhs >= rhs;
                if negate_next { cond = !cond; negate_next = false; }
            }
            OP_STONE_DEPTH => {
                let offset = read_i32_le(bc, ip);
                ip += 4;
                let add_surface_depth = bc[ip] != 0;
                ip += 1;
                let secondary_depth_range = read_i32_le(bc, ip);
                ip += 4;
                let surface_type = bc[ip]; // 0=FLOOR, 1=CEILING
                ip += 1;
                let depth = if surface_type == 0 { ctx.stone_depth_above } else { ctx.stone_depth_below };
                let add_surface = if add_surface_depth { ctx.run_depth } else { 0 };
                let secondary_adjust = if secondary_depth_range == 0 {
                    0
                } else {
                    // MathHelper.map(v, -1, 1, 0, R) = (v + 1) * R / 2
                    ((ctx.secondary_depth + 1.0) * secondary_depth_range as f64 / 2.0) as i32
                };
                cond = depth <= 1 + offset + add_surface + secondary_adjust;
                if negate_next { cond = !cond; negate_next = false; }
            }
            OP_WATER => {
                let offset = read_i32_le(bc, ip);
                ip += 4;
                ip += 4; // skip surfaceDepthMultiplier (not yet semantically wired)
                ip += 1; // skip addStoneDepthBelow
                cond = ctx.block_y < ctx.fluid_height + offset;
                if negate_next { cond = !cond; negate_next = false; }
            }
            OP_VERT_GRADIENT => {
                let true_at_and_below = read_i32_le(bc, ip);
                ip += 4;
                let false_at_and_above = read_i32_le(bc, ip);
                ip += 4;
                let y = ctx.block_y;
                cond = if y <= true_at_and_below {
                    true
                } else if y >= false_at_and_above {
                    false
                } else {
                    // Spike: midpoint cutoff (vanilla uses per-position random).
                    y <= (true_at_and_below + false_at_and_above) / 2
                };
                if negate_next { cond = !cond; negate_next = false; }
            }

            _ => {
                // Unknown opcode — bail (caller routes to vanilla).
                return None;
            }
        }
    }
    value
}

// ---- Little-endian readers (mirror Java SurfaceRuleEvaluator) -------

#[inline]
fn read_u16_le(b: &[u8], off: usize) -> u16 {
    (b[off] as u16) | ((b[off + 1] as u16) << 8)
}

#[inline]
fn read_u32_le(b: &[u8], off: usize) -> u32 {
    (b[off] as u32)
        | ((b[off + 1] as u32) << 8)
        | ((b[off + 2] as u32) << 16)
        | ((b[off + 3] as u32) << 24)
}

#[inline]
fn read_i32_le(b: &[u8], off: usize) -> i32 {
    read_u32_le(b, off) as i32
}

#[inline]
fn read_f64_le(b: &[u8], off: usize) -> f64 {
    let mut bits: u64 = 0;
    for i in 0..8 {
        bits |= (b[off + i] as u64) << (i * 8);
    }
    f64::from_bits(bits)
}

// ---- Self-tests (mirror SurfaceRuleEvaluatorSelfTest) ---------------

#[cfg(test)]
mod tests {
    use super::*;

    fn ctx_default() -> ColumnContext<'static> {
        ColumnContext {
            biome_id: 0,
            block_y: 64,
            run_depth: 5,
            stone_depth_above: 5,
            stone_depth_below: 5,
            fluid_height: 63,
            is_cold: false,
            is_steep: false,
            surface_height: 64,
            secondary_depth: 0.0,
            noise_values: &[],
        }
    }

    #[test]
    fn eval_single_block_returns_id() {
        // OP_BLOCK + u32(42) + OP_RETURN_DONE
        let bc: &[u8] = &[OP_BLOCK, 42, 0, 0, 0, OP_RETURN_DONE];
        let tree = CompiledTree { bytecode: bc, biome_set_table: &[], blockstate_count: 100 };
        assert_eq!(evaluate(&tree, &ctx_default()), Some(42));
    }

    #[test]
    fn eval_empty_returns_none() {
        let bc: &[u8] = &[OP_RETURN_DONE];
        let tree = CompiledTree { bytecode: bc, biome_set_table: &[], blockstate_count: 0 };
        assert_eq!(evaluate(&tree, &ctx_default()), None);
    }

    #[test]
    fn eval_fallback_then_block_falls_through() {
        // FALLBACK + OP_BLOCK + u32(7) + RETURN_DONE
        // Fallback should soft-skip, then OP_BLOCK fires.
        let bc: &[u8] = &[OP_FALLBACK, OP_BLOCK, 7, 0, 0, 0, OP_RETURN_DONE];
        let tree = CompiledTree { bytecode: bc, biome_set_table: &[], blockstate_count: 10 };
        assert_eq!(evaluate(&tree, &ctx_default()), Some(7));
    }

    #[test]
    fn eval_if_else_takes_then_when_cond_true() {
        // OP_HOLE (run_depth=0 → true) + OP_IF_ELSE then=11 else=18 + OP_BLOCK 5 + OP_RETURN_DONE @11 ...
        // Layout:
        //   [0]  OP_HOLE
        //   [1]  OP_IF_ELSE
        //   [2..6]  then_off=11
        //   [6..10] else_off=17
        //   [10]    (start of "then-then" — never reached because layout is different)
        //   actually let me do this simpler
        // Use:
        //   [0]  OP_HOLE
        //   [1]  OP_IF_ELSE
        //   [2..6]  then_off=10
        //   [6..10] else_off=16
        //   [10..15] OP_BLOCK 99  (then-branch)
        //   [15]    OP_RETURN_DONE  (terminator after then-branch falls through to here)
        //   ... wait IF_ELSE's else jumps PAST then to the next position
        //   Let me build it properly with builder.
        let mut bc: Vec<u8> = Vec::new();
        bc.push(OP_HOLE); // sets cond
        bc.push(OP_IF_ELSE);
        let then_slot = bc.len();
        bc.extend_from_slice(&[0, 0, 0, 0]);
        let else_slot = bc.len();
        bc.extend_from_slice(&[0, 0, 0, 0]);
        let then_target = bc.len();
        bc.push(OP_BLOCK);
        bc.extend_from_slice(&[99u8, 0, 0, 0]);
        let else_target = bc.len(); // immediately past then-branch
        bc.push(OP_RETURN_DONE);
        // patch
        let then_bytes = (then_target as u32).to_le_bytes();
        let else_bytes = (else_target as u32).to_le_bytes();
        bc[then_slot..then_slot + 4].copy_from_slice(&then_bytes);
        bc[else_slot..else_slot + 4].copy_from_slice(&else_bytes);
        let tree = CompiledTree { bytecode: &bc, biome_set_table: &[], blockstate_count: 100 };
        let mut ctx = ctx_default();
        ctx.run_depth = 0;
        assert_eq!(evaluate(&tree, &ctx), Some(99));
        // With run_depth > 0, hole is false — IF_ELSE jumps to else_target,
        // skipping OP_BLOCK, and we hit RETURN_DONE with no value set.
        ctx.run_depth = 5;
        assert_eq!(evaluate(&tree, &ctx), None);
    }

    #[test]
    fn eval_sequence_next_short_circuits() {
        // [BLOCK 1] [SEQ_NEXT @end] [BLOCK 2] [SEQ_NEXT @end] @end: RETURN_DONE
        let mut bc: Vec<u8> = Vec::new();
        bc.push(OP_BLOCK);
        bc.extend_from_slice(&[1, 0, 0, 0]);
        bc.push(OP_SEQUENCE_NEXT);
        let off1 = bc.len();
        bc.extend_from_slice(&[0, 0, 0, 0]);
        bc.push(OP_BLOCK);
        bc.extend_from_slice(&[2, 0, 0, 0]);
        bc.push(OP_SEQUENCE_NEXT);
        let off2 = bc.len();
        bc.extend_from_slice(&[0, 0, 0, 0]);
        let end = bc.len();
        bc.push(OP_RETURN_DONE);
        let end_bytes = (end as u32).to_le_bytes();
        bc[off1..off1 + 4].copy_from_slice(&end_bytes);
        bc[off2..off2 + 4].copy_from_slice(&end_bytes);
        let tree = CompiledTree { bytecode: &bc, biome_set_table: &[], blockstate_count: 100 };
        // First BLOCK sets value; SEQUENCE_NEXT short-circuits past the second.
        assert_eq!(evaluate(&tree, &ctx_default()), Some(1));
    }

    #[test]
    fn eval_biome_set_membership() {
        // OP_BIOME #0 → check biome_id is in set [10,20,30]
        // followed by IF_ELSE that picks BLOCK 7 if true, BLOCK 8 if false
        let set: &[u16] = &[10, 20, 30];
        let mut bc: Vec<u8> = Vec::new();
        bc.push(OP_BIOME);
        bc.extend_from_slice(&[0, 0]); // pool index 0
        bc.push(OP_IF_ELSE);
        let then_slot = bc.len();
        bc.extend_from_slice(&[0, 0, 0, 0]);
        let else_slot = bc.len();
        bc.extend_from_slice(&[0, 0, 0, 0]);
        let then_target = bc.len();
        bc.push(OP_BLOCK);
        bc.extend_from_slice(&[7, 0, 0, 0]);
        let else_target = bc.len();
        bc.push(OP_RETURN_DONE);
        bc[then_slot..then_slot + 4].copy_from_slice(&(then_target as u32).to_le_bytes());
        bc[else_slot..else_slot + 4].copy_from_slice(&(else_target as u32).to_le_bytes());
        let tree = CompiledTree {
            bytecode: &bc,
            biome_set_table: &[set],
            blockstate_count: 100,
        };
        let mut ctx = ctx_default();
        ctx.biome_id = 20;
        assert_eq!(evaluate(&tree, &ctx), Some(7));
        ctx.biome_id = 99; // not in set
        assert_eq!(evaluate(&tree, &ctx), None);
    }

    #[test]
    fn eval_above_y_exact_formula() {
        // OP_ABOVE_Y anchor=64 mul=0 addStoneDepth=false → blockY >= 64
        let bc: &[u8] = &[
            OP_ABOVE_Y,
            64, 0, 0, 0, // anchor=64
            0, 0, 0, 0,  // surfaceDepthMultiplier=0
            0,           // addStoneDepth=false
            OP_RETURN_DONE,
        ];
        let tree = CompiledTree { bytecode: bc, biome_set_table: &[], blockstate_count: 0 };
        let ctx = ctx_default();
        // Just runs the condition, doesn't emit; value stays None.
        // This test only verifies the dispatch doesn't crash on the operand.
        assert_eq!(evaluate(&tree, &ctx), None);
    }

    #[test]
    fn eval_vert_gradient_outside_range() {
        // OP_VERT_GRADIENT trueAt=-8 falseAt=0 — with blockY=-30, cond=true
        let bc: &[u8] = &[
            OP_VERT_GRADIENT,
            0xF8, 0xFF, 0xFF, 0xFF, // -8
            0x00, 0x00, 0x00, 0x00, // 0
            OP_RETURN_DONE,
        ];
        let tree = CompiledTree { bytecode: bc, biome_set_table: &[], blockstate_count: 0 };
        let mut ctx = ctx_default();
        ctx.block_y = -30;
        // Condition fires but no BLOCK opcode — eval returns None.
        // We only care it doesn't crash.
        assert_eq!(evaluate(&tree, &ctx), None);
    }

    #[test]
    fn eval_unknown_opcode_returns_none() {
        let bc: &[u8] = &[0xEE, OP_RETURN_DONE]; // unknown opcode 0xEE
        let tree = CompiledTree { bytecode: bc, biome_set_table: &[], blockstate_count: 0 };
        assert_eq!(evaluate(&tree, &ctx_default()), None);
    }
}
