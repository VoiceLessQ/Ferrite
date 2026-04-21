package me.apika.apikaprobe.surface;

/**
 * Walks a {@link CompiledRuleTree} for one column-Y position and
 * returns the matching blockstate (as the compiler-time table entry
 * Object) or {@code null} if no rule matched.
 *
 * Two registers:
 * <ul>
 *   <li>{@code condResult} — boolean, set by every condition opcode</li>
 *   <li>{@code valueResult} — Object, set by {@code OP_BLOCK}; null = "no rule fired yet"</li>
 * </ul>
 *
 * Control flow is forward-only (jumps target absolute byte positions
 * in the bytecode array). {@code OP_RETURN_DONE} is the single
 * trailing terminator; {@code OP_FALLBACK} returns null and signals
 * the dispatcher to route this chunk to vanilla.
 *
 * <h3>Spike scope</h3>
 * Condition semantics are the simplest interpretation that exercises
 * the bytecode shape end-to-end:
 * <ul>
 *   <li>{@code OP_HOLE} — runDepth ≤ 0</li>
 *   <li>{@code OP_STEEP} — ctx.isSteep</li>
 *   <li>{@code OP_TEMPERATURE} — ctx.isCold</li>
 *   <li>{@code OP_SURFACE} — blockY ≥ surfaceHeight</li>
 *   <li>{@code OP_BIOME} — biome name in pool entry</li>
 *   <li>{@code OP_NOISE_THRESH} — min ≤ noise[ch] ≤ max</li>
 *   <li>{@code OP_ABOVE_Y} — blockY ≥ surfaceDepthMultiplier (operand)</li>
 *   <li>{@code OP_STONE_DEPTH} — stoneDepthAbove ≤ offset (operand)</li>
 *   <li>{@code OP_WATER} — blockY &lt; fluidHeight + offset (operand)</li>
 *   <li>{@code OP_NOT} — invert next condition's result</li>
 * </ul>
 *
 * Refining these against vanilla's exact formulas is the validator's
 * job — write the validator first, then iterate semantics until
 * outputs diff to zero.
 */
public final class SurfaceRuleEvaluator {

	private SurfaceRuleEvaluator() {}

	public static Object evaluate(CompiledRuleTree tree, ColumnContext ctx) {
		// Don't short-circuit on tree.hasFallback() — that flag is for the
		// dispatcher (tells it "result may be wrong, route to vanilla").
		// The validator wants to walk the bytecode and only bail when it
		// actually executes an OP_FALLBACK opcode mid-stream.
		byte[] bc = tree.bytecode();
		int ip = 0;
		boolean condResult = false;
		Object valueResult = null;
		boolean negateNext = false;

		while (ip < bc.length) {
			byte op = bc[ip++];
			switch (op) {
				case RuleBytecode.OP_RETURN_DONE -> {
					return valueResult;
				}
				case RuleBytecode.OP_FALLBACK -> {
					// Soft skip — this alternative came from a node the
					// compiler couldn't emit operands for (TerracottaBands,
					// VertGradient). Don't set valueResult; the enclosing
					// Sequence/Condition fall-through handles this as
					// "branch produced nothing." Returning null here would
					// abort the whole eval, masking every other branch.
				}
				case RuleBytecode.OP_BLOCK -> {
					int id = readIntLE(bc, ip); ip += 4;
					valueResult = tree.blockstateTable()[id];
				}
				case RuleBytecode.OP_IF_ELSE -> {
					int thenOff = readIntLE(bc, ip); ip += 4;
					int elseOff = readIntLE(bc, ip); ip += 4;
					ip = condResult ? thenOff : elseOff;
				}
				case RuleBytecode.OP_SEQUENCE_NEXT -> {
					int endOff = readIntLE(bc, ip); ip += 4;
					if (valueResult != null) ip = endOff;
				}
				case RuleBytecode.OP_NOT -> {
					negateNext = true;
				}

				// Conditions ------------------------------------------------
				case RuleBytecode.OP_HOLE -> {
					condResult = ctx.runDepth() <= 0;
					if (negateNext) { condResult = !condResult; negateNext = false; }
				}
				case RuleBytecode.OP_STEEP -> {
					condResult = ctx.isSteep();
					if (negateNext) { condResult = !condResult; negateNext = false; }
				}
				case RuleBytecode.OP_TEMPERATURE -> {
					condResult = ctx.isCold();
					if (negateNext) { condResult = !condResult; negateNext = false; }
				}
				case RuleBytecode.OP_SURFACE -> {
					condResult = ctx.blockY() >= ctx.surfaceHeight();
					if (negateNext) { condResult = !condResult; negateNext = false; }
				}
				case RuleBytecode.OP_BIOME -> {
					int idx = readU16LE(bc, ip); ip += 2;
					java.util.List<String> set = tree.biomeSetTable()[idx];
					condResult = set.contains(ctx.biomeName());
					if (negateNext) { condResult = !condResult; negateNext = false; }
				}
				case RuleBytecode.OP_NOISE_THRESH -> {
					int chIdx = readU16LE(bc, ip); ip += 2;
					double minT = readDoubleLE(bc, ip); ip += 8;
					double maxT = readDoubleLE(bc, ip); ip += 8;
					double v = ctx.noiseValues()[chIdx];
					condResult = v >= minT && v <= maxT;
					if (negateNext) { condResult = !condResult; negateNext = false; }
				}
				case RuleBytecode.OP_ABOVE_Y -> {
					int anchorY = readIntLE(bc, ip); ip += 4;
					int surfaceDepthMultiplier = readIntLE(bc, ip); ip += 4;
					int addStoneDepth = bc[ip++] & 0xFF;
					// Vanilla formula:
					//   (blockY + (addStoneDepth ? stoneDepthAbove : 0))
					//     >= (anchorY + runDepth * surfaceDepthMultiplier)
					int lhs = ctx.blockY() + (addStoneDepth != 0 ? ctx.stoneDepthAbove() : 0);
					int rhs = anchorY + ctx.runDepth() * surfaceDepthMultiplier;
					condResult = lhs >= rhs;
					if (negateNext) { condResult = !condResult; negateNext = false; }
				}
				case RuleBytecode.OP_STONE_DEPTH -> {
					int offset = readIntLE(bc, ip); ip += 4;
					ip += 1; // skip addSurfaceDepth (semantic TBD)
					ip += 4; // skip secondaryDepthRange
					int surfaceType = bc[ip++] & 0xFF;
					int depth = surfaceType == 0 ? ctx.stoneDepthAbove() : ctx.stoneDepthBelow();
					condResult = depth <= offset;
					if (negateNext) { condResult = !condResult; negateNext = false; }
				}
				case RuleBytecode.OP_WATER -> {
					int offset = readIntLE(bc, ip); ip += 4;
					ip += 4; // skip surfaceDepthMultiplier
					ip += 1; // skip addStoneDepthBelow
					condResult = ctx.blockY() < ctx.fluidHeight() + offset;
					if (negateNext) { condResult = !condResult; negateNext = false; }
				}

				default -> {
					// Unknown opcode — bail to vanilla
					return null;
				}
			}
		}
		return valueResult;
	}

	private static int readIntLE(byte[] b, int off) {
		return (b[off] & 0xFF)
			| ((b[off + 1] & 0xFF) << 8)
			| ((b[off + 2] & 0xFF) << 16)
			| ((b[off + 3] & 0xFF) << 24);
	}

	private static int readU16LE(byte[] b, int off) {
		return (b[off] & 0xFF) | ((b[off + 1] & 0xFF) << 8);
	}

	private static double readDoubleLE(byte[] b, int off) {
		long bits = 0;
		for (int i = 0; i < 8; i++) {
			bits |= ((long)(b[off + i] & 0xFF)) << (i * 8);
		}
		return Double.longBitsToDouble(bits);
	}
}
