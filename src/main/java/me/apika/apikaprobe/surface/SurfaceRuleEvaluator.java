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

	/**
	 * Per-block PRNG sampler for OP_VERT_GRADIENT. Mirrors vanilla's
	 * {@code PositionalRandomFactory.split(x, y, z).nextFloat()} call.
	 *
	 * <p>The validator builds an instance backed by reflective calls to
	 * {@code RandomState.getOrCreateRandomDeriver(Identifier)} →
	 * {@code PositionalRandomFactory.split(x, y, z)} → {@code RandomSource.nextFloat()}.
	 * Self-tests pass {@code null}, which falls back to the midpoint
	 * approximation that the spike originally shipped with — preserves
	 * existing test expectations.
	 */
	@FunctionalInterface
	public interface VerticalGradientSampler {
		/** @return a value in [0.0, 1.0) for the given (randomNameIdx, x, y, z). */
		float roll(int randomNameIdx, int x, int y, int z);
	}

	/** Per-thread (x, z) capture used so {@link #evaluate} call sites that
	 *  don't have block coordinates (the bytecode itself only carries y)
	 *  can still feed the sampler. The validator sets these immediately
	 *  before each evaluate call; the self-tests leave them at 0.
	 *
	 *  Static-mutable is ugly but the alternative is threading (x, z)
	 *  through a 7-place call chain that already exists; defer that to
	 *  the eventual ctx redesign. */
	private static final ThreadLocal<int[]> currentXZ = ThreadLocal.withInitial(() -> new int[2]);

	/** Set the per-thread (x, z) used by the next {@link #evaluate} call's
	 *  OP_VERT_GRADIENT sampler. Validator-only; safe to ignore for
	 *  unit-test paths that pass null sampler. */
	public static void setCurrentXZ(int x, int z) {
		int[] xz = currentXZ.get();
		xz[0] = x;
		xz[1] = z;
	}

	/**
	 * Evaluates the same {@link CompiledRuleTree} via the native Rust
	 * implementation. Returns the same Object that the Java
	 * {@link #evaluate} would return for the same inputs (the entry
	 * from {@link CompiledRuleTree#blockstateTable} at the index Rust
	 * resolved, or {@code null} if no rule matched).
	 *
	 * <p>If the native library isn't loaded for this platform, returns
	 * null silently — caller should treat that as "Rust path
	 * unavailable" and fall back to the Java evaluator.
	 *
	 * <p>Allocates fresh direct ByteBuffers per call. That's wasteful
	 * but correct for the validator-replacement spike (1-in-1000
	 * sampling — the per-call allocation cost is invisible against
	 * the JNI overhead). Per-chunk batching with reused buffers is
	 * the next optimization.
	 */
	public static Object evaluateViaRust(CompiledRuleTree tree, ColumnContext ctx) {
		// Default to (0, 0) when the caller doesn't have real x/z. Synthetic
		// callers (FerriteCommand.surfaceBatchTest) hit this path; their
		// OP_VERT_GRADIENT inside-the-zone sampling will be deterministic
		// at origin which is fine for parity tests.
		return evaluateViaRust(tree, ctx, 0, 0);
	}

	public static Object evaluateViaRust(CompiledRuleTree tree, ColumnContext ctx, int blockX, int blockZ) {
		if (!me.apika.apikaprobe.RustBridge.NATIVE_AVAILABLE) return null;

		// Pack bytecode into a direct buffer.
		java.nio.ByteBuffer bcBuf = java.nio.ByteBuffer.allocateDirect(tree.bytecode().length);
		bcBuf.put(tree.bytecode());
		bcBuf.flip();

		// Pre-compute biome match bits: 1 byte per biome-set-pool entry,
		// 1 if this column's biome name is in that pool entry.
		int biomeSetCount = tree.biomeSetTable().length;
		java.nio.ByteBuffer bitsBuf = java.nio.ByteBuffer.allocateDirect(biomeSetCount);
		String currentBiome = ctx.biomeName();
		for (int i = 0; i < biomeSetCount; i++) {
			bitsBuf.put((byte) (tree.biomeSetTable()[i].contains(currentBiome) ? 1 : 0));
		}
		bitsBuf.flip();

		// Pack noise values as f64 little-endian.
		double[] noise = ctx.noiseValues();
		int noiseCount = noise == null ? 0 : noise.length;
		java.nio.ByteBuffer noiseBuf = java.nio.ByteBuffer.allocateDirect(noiseCount * 8)
				.order(java.nio.ByteOrder.LITTLE_ENDIAN);
		for (int i = 0; i < noiseCount; i++) {
			noiseBuf.putDouble(noise[i]);
		}
		noiseBuf.flip();

		// Per-tree factory seeds buffer (populated lazily by the validator
		// from cachedSplitters on first dispatch). Null = no VerticalGradient
		// rules in this tree, or splitter cache not yet built — Rust falls
		// back to midpoint when factorySeedCount == 0.
		java.nio.ByteBuffer factorySeedsBuf = SurfaceValidator.cachedFactorySeedsBuf();
		int factorySeedCount = SurfaceValidator.cachedFactorySeedCount();

		int id;
		try {
			id = me.apika.apikaprobe.RustBridge.evaluateSurfaceRule(
					bcBuf, tree.bytecode().length,
					bitsBuf, biomeSetCount,
					blockX, ctx.blockY(), blockZ, ctx.runDepth(),
					ctx.stoneDepthAbove(), ctx.stoneDepthBelow(),
					ctx.fluidHeight(), ctx.isCold(), ctx.isSteep(),
					ctx.surfaceHeight(), ctx.secondaryDepth(),
					noiseBuf, noiseCount,
					factorySeedsBuf, factorySeedCount);
		} catch (UnsatisfiedLinkError | RuntimeException e) {
			return null;
		}

		if (id < 0 || id >= tree.blockstateTable().length) return null;
		return tree.blockstateTable()[id];
	}

	/**
	 * Same dispatch as {@link #evaluate}, but appends a human-readable
	 * line to {@code trace} for each opcode executed: opcode name,
	 * operand values, condResult/valueResult after the step, and any
	 * branch decisions for IF_ELSE/SEQUENCE_NEXT.
	 *
	 * <p>Returns the same value {@code evaluate} would. Used by
	 * {@code /ferrite surface trace-next} to pinpoint exactly which
	 * condition diverged from vanilla on a mismatching position.
	 */
	public static Object evaluateWithTrace(CompiledRuleTree tree, ColumnContext ctx, java.util.List<String> trace) {
		return evaluateWithTrace(tree, ctx, trace, null);
	}

	/** Sampler-aware overload of {@link #evaluateWithTrace}. Same null
	 *  semantics as {@link #evaluate} — null sampler keeps the spike's
	 *  midpoint behavior so trace-next on self-test paths still works. */
	public static Object evaluateWithTrace(CompiledRuleTree tree, ColumnContext ctx,
			java.util.List<String> trace, VerticalGradientSampler sampler) {
		byte[] bc = tree.bytecode();
		int ip = 0;
		boolean condResult = false;
		Object valueResult = null;
		boolean negateNext = false;

		while (ip < bc.length) {
			int opIp = ip;
			byte op = bc[ip++];
			switch (op) {
				case RuleBytecode.OP_RETURN_DONE -> {
					trace.add(String.format("[%04d] OP_RETURN_DONE → return %s", opIp, fmt(valueResult)));
					return valueResult;
				}
				case RuleBytecode.OP_FALLBACK -> trace.add(String.format("[%04d] OP_FALLBACK (soft skip)", opIp));
				case RuleBytecode.OP_BLOCK -> {
					int id = readIntLE(bc, ip); ip += 4;
					Object state = (id >= 0 && id < tree.blockstateTable().length) ? tree.blockstateTable()[id] : null;
					valueResult = state;
					trace.add(String.format("[%04d] OP_BLOCK id=%d → value=%s", opIp, id, fmt(state)));
				}
				case RuleBytecode.OP_IF_ELSE -> {
					int thenOff = readIntLE(bc, ip); ip += 4;
					int elseOff = readIntLE(bc, ip); ip += 4;
					int target = condResult ? thenOff : elseOff;
					trace.add(String.format("[%04d] OP_IF_ELSE then=%d else=%d cond=%s → ip=%d", opIp, thenOff, elseOff, condResult, target));
					ip = target;
				}
				case RuleBytecode.OP_SEQUENCE_NEXT -> {
					int endOff = readIntLE(bc, ip); ip += 4;
					boolean jumped = valueResult != null;
					if (jumped) ip = endOff;
					trace.add(String.format("[%04d] OP_SEQUENCE_NEXT end=%d value=%s → %s", opIp, endOff, fmt(valueResult), jumped ? "JUMP" : "fall"));
				}
				case RuleBytecode.OP_NOT -> {
					negateNext = true;
					trace.add(String.format("[%04d] OP_NOT (next condition inverted)", opIp));
				}
				case RuleBytecode.OP_HOLE -> {
					condResult = ctx.runDepth() <= 0;
					if (negateNext) { condResult = !condResult; negateNext = false; }
					trace.add(String.format("[%04d] OP_HOLE runDepth=%d → cond=%s", opIp, ctx.runDepth(), condResult));
				}
				case RuleBytecode.OP_STEEP -> {
					condResult = ctx.isSteep();
					if (negateNext) { condResult = !condResult; negateNext = false; }
					trace.add(String.format("[%04d] OP_STEEP → cond=%s", opIp, condResult));
				}
				case RuleBytecode.OP_TEMPERATURE -> {
					condResult = ctx.isCold();
					if (negateNext) { condResult = !condResult; negateNext = false; }
					trace.add(String.format("[%04d] OP_TEMPERATURE isCold=%s → cond=%s", opIp, ctx.isCold(), condResult));
				}
				case RuleBytecode.OP_SURFACE -> {
					condResult = ctx.blockY() >= ctx.surfaceHeight();
					if (negateNext) { condResult = !condResult; negateNext = false; }
					trace.add(String.format("[%04d] OP_SURFACE blockY=%d surfaceH=%d → cond=%s", opIp, ctx.blockY(), ctx.surfaceHeight(), condResult));
				}
				case RuleBytecode.OP_BIOME -> {
					int idx = readU16LE(bc, ip); ip += 2;
					java.util.List<String> set = tree.biomeSetTable()[idx];
					condResult = set.contains(ctx.biomeName());
					if (negateNext) { condResult = !condResult; negateNext = false; }
					trace.add(String.format("[%04d] OP_BIOME idx=%d biome=%s setSize=%d → cond=%s", opIp, idx, ctx.biomeName(), set.size(), condResult));
				}
				case RuleBytecode.OP_NOISE_THRESH -> {
					int chIdx = readU16LE(bc, ip); ip += 2;
					double minT = readDoubleLE(bc, ip); ip += 8;
					double maxT = readDoubleLE(bc, ip); ip += 8;
					double v = ctx.noiseValues()[chIdx];
					condResult = v >= minT && v <= maxT;
					if (negateNext) { condResult = !condResult; negateNext = false; }
					trace.add(String.format("[%04d] OP_NOISE_THRESH ch=%d v=%.4f range=[%.4f,%.4f] → cond=%s", opIp, chIdx, v, minT, maxT, condResult));
				}
				case RuleBytecode.OP_ABOVE_Y -> {
					int anchorY = readIntLE(bc, ip); ip += 4;
					int sdMul = readIntLE(bc, ip); ip += 4;
					int addStone = bc[ip++] & 0xFF;
					int lhs = ctx.blockY() + (addStone != 0 ? ctx.stoneDepthAbove() : 0);
					int rhs = anchorY + ctx.runDepth() * sdMul;
					condResult = lhs >= rhs;
					if (negateNext) { condResult = !condResult; negateNext = false; }
					trace.add(String.format("[%04d] OP_ABOVE_Y anchor=%d sdMul=%d addStone=%d lhs=%d rhs=%d → cond=%s", opIp, anchorY, sdMul, addStone, lhs, rhs, condResult));
				}
				case RuleBytecode.OP_STONE_DEPTH -> {
					int offset = readIntLE(bc, ip); ip += 4;
					int addSurface = bc[ip++] & 0xFF;
					int sdRange = readIntLE(bc, ip); ip += 4;
					int surfType = bc[ip++] & 0xFF;
					int depth = surfType == 0 ? ctx.stoneDepthBelow() : ctx.stoneDepthAbove();
					int addS = addSurface != 0 ? ctx.runDepth() : 0;
					int secAdj = sdRange == 0 ? 0 : (int)((ctx.secondaryDepth() + 1.0) * sdRange / 2.0);
					int rhs = 1 + offset + addS + secAdj;
					condResult = depth <= rhs;
					if (negateNext) { condResult = !condResult; negateNext = false; }
					trace.add(String.format("[%04d] OP_STONE_DEPTH offset=%d addSurface=%d sdRange=%d type=%s depth=%d rhs=%d → cond=%s", opIp, offset, addSurface, sdRange, surfType == 0 ? "CEILING" : "FLOOR", depth, rhs, condResult));
				}
				case RuleBytecode.OP_WATER -> {
					int offset = readIntLE(bc, ip); ip += 4;
					int sdMul = readIntLE(bc, ip); ip += 4;
					boolean addStone = bc[ip++] != 0;
					int wh = ctx.fluidHeight();
					if (wh == Integer.MIN_VALUE) {
						condResult = true;
						trace.add(String.format("[%04d] OP_WATER waterHeight=MIN_VALUE → cond=true (no fluid)", opIp));
					} else {
						int lhs = ctx.blockY() + (addStone ? ctx.stoneDepthAbove() : 0);
						int rhs = wh + offset + ctx.runDepth() * sdMul;
						condResult = lhs >= rhs;
						trace.add(String.format("[%04d] OP_WATER offset=%d sdMul=%d addStone=%s wh=%d lhs=%d rhs=%d → cond=%s", opIp, offset, sdMul, addStone, wh, lhs, rhs, condResult));
					}
					if (negateNext) { condResult = !condResult; negateNext = false; }
				}
				case RuleBytecode.OP_VERT_GRADIENT -> {
					int nameIdx = readU16LE(bc, ip); ip += 2;
					int trueB = readIntLE(bc, ip); ip += 4;
					int falseA = readIntLE(bc, ip); ip += 4;
					int y = ctx.blockY();
					String mode;
					if (y <= trueB) { condResult = true; mode = "below-true-zone"; }
					else if (y >= falseA) { condResult = false; mode = "above-false-zone"; }
					else if (sampler != null) {
						double prob = 1.0 - ((double)(y - trueB)) / ((double)(falseA - trueB));
						int[] xz = currentXZ.get();
						float roll = sampler.roll(nameIdx, xz[0], y, xz[1]);
						condResult = roll < prob;
						mode = String.format("prng-roll=%.4f<prob=%.4f", roll, prob);
					}
					else { condResult = y <= (trueB + falseA) / 2; mode = "midpoint-approx"; }
					if (negateNext) { condResult = !condResult; negateNext = false; }
					String name = nameIdx < tree.randomNameTable().length
							? tree.randomNameTable()[nameIdx] : "?";
					trace.add(String.format("[%04d] OP_VERT_GRADIENT name='%s' trueAtBelow=%d falseAtAbove=%d y=%d %s → cond=%s",
							opIp, name, trueB, falseA, y, mode, condResult));
				}
				default -> {
					trace.add(String.format("[%04d] UNKNOWN opcode=0x%02x → return null", opIp, op & 0xFF));
					return null;
				}
			}
		}
		trace.add(String.format("[%04d] end of bytecode → return %s", ip, fmt(valueResult)));
		return valueResult;
	}

	private static String fmt(Object o) {
		if (o == null) return "null";
		try {
			Object block = o.getClass().getMethod("getBlock").invoke(o);
			if (block != null) {
				Class<?> reg = Class.forName("net.minecraft.core.registries.BuiltInRegistries");
				Object br = reg.getField("BLOCK").get(null);
				for (java.lang.reflect.Method m : br.getClass().getMethods()) {
					if (m.getName().equals("getId") && m.getParameterCount() == 1) {
						Object id = m.invoke(br, block);
						if (id != null) return id.toString();
					}
				}
			}
		} catch (ReflectiveOperationException | RuntimeException ignored) {
			// fall through
		}
		return o.toString();
	}

	public static Object evaluate(CompiledRuleTree tree, ColumnContext ctx) {
		return evaluate(tree, ctx, null);
	}

	/**
	 * Sampler-aware overload. Pass a non-null {@link VerticalGradientSampler}
	 * to get the real per-block PRNG behavior vanilla uses for bedrock floor
	 * and deepslate transition rules. Pass null to get the spike's midpoint
	 * approximation (preserved for self-tests and any caller that doesn't
	 * have access to a {@code RandomState}).
	 */
	public static Object evaluate(CompiledRuleTree tree, ColumnContext ctx, VerticalGradientSampler sampler) {
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
				case RuleBytecode.OP_VERT_GRADIENT -> {
					int nameIdx = readU16LE(bc, ip); ip += 2;
					int trueAtAndBelow = readIntLE(bc, ip); ip += 4;
					int falseAtAndAbove = readIntLE(bc, ip); ip += 4;
					int y = ctx.blockY();
					if (y <= trueAtAndBelow) {
						condResult = true;
					} else if (y >= falseAtAndAbove) {
						condResult = false;
					} else if (sampler != null) {
						// Vanilla: probability = Mth.map(y, trueAtAndBelow,
						// falseAtAndAbove, 1.0, 0.0); random.nextFloat() < prob.
						double probability = 1.0 - ((double)(y - trueAtAndBelow))
								/ ((double)(falseAtAndAbove - trueAtAndBelow));
						int[] xz = currentXZ.get();
						float roll = sampler.roll(nameIdx, xz[0], y, xz[1]);
						condResult = roll < probability;
					} else {
						// Spike fallback: midpoint cutoff (~50% wrong inside zone).
						condResult = y <= (trueAtAndBelow + falseAtAndAbove) / 2;
					}
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
					// Vanilla formula (decoded from javap):
					//   depth = surfaceType==CEILING ? stoneDepthBelow : stoneDepthAbove
					//   addSurface = addSurfaceDepth ? runDepth : 0
					//   secondaryAdjust = secondaryDepthRange == 0 ? 0
					//     : (int)Mth.map(secondaryDepth, -1.0, 1.0, 0.0, secondaryDepthRange)
					//   return depth <= 1 + offset + addSurface + secondaryAdjust
					int offset = readIntLE(bc, ip); ip += 4;
					int addSurfaceDepth = bc[ip++] & 0xFF;
					int secondaryDepthRange = readIntLE(bc, ip); ip += 4;
					// CaveSurface enum order: CEILING(0), FLOOR(1) — confirmed
					// from Mojang's unobfuscated 1.21.11 source. Vanilla's
					// StoneDepthCheck routes CEILING → stoneDepthBelow,
					// FLOOR → stoneDepthAbove. Earlier impl had this swapped.
					int surfaceType = bc[ip++] & 0xFF;
					int depth = surfaceType == 0 ? ctx.stoneDepthBelow() : ctx.stoneDepthAbove();
					int addSurface = addSurfaceDepth != 0 ? ctx.runDepth() : 0;
					int secondaryAdjust;
					if (secondaryDepthRange == 0) {
						secondaryAdjust = 0;
					} else {
						// Mth.map(v, -1, 1, 0, R) = (v + 1) * R / 2
						double mapped = (ctx.secondaryDepth() + 1.0) * secondaryDepthRange / 2.0;
						secondaryAdjust = (int) mapped;
					}
					condResult = depth <= 1 + offset + addSurface + secondaryAdjust;
					if (negateNext) { condResult = !condResult; negateNext = false; }
				}
				case RuleBytecode.OP_WATER -> {
					// Vanilla formula (Mojang's unobfuscated 1.21.11 source):
					//   return waterHeight == Integer.MIN_VALUE
					//       || blockY + (addStoneDepth ? stoneDepthAbove : 0)
					//          >= waterHeight + offset
					//             + surfaceDepth * surfaceDepthMultiplier;
					// Earlier impl had `blockY < fluidHeight + offset` — wrong
					// inequality direction, missing MIN_VALUE short-circuit,
					// and skipped addStoneDepth + surfaceDepthMultiplier terms.
					int offset = readIntLE(bc, ip); ip += 4;
					int surfaceDepthMul = readIntLE(bc, ip); ip += 4;
					boolean addStoneDepth = bc[ip++] != 0;
					int waterHeight = ctx.fluidHeight();
					if (waterHeight == Integer.MIN_VALUE) {
						condResult = true;
					} else {
						int lhs = ctx.blockY() + (addStoneDepth ? ctx.stoneDepthAbove() : 0);
						int rhs = waterHeight + offset + ctx.runDepth() * surfaceDepthMul;
						condResult = lhs >= rhs;
					}
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
