package me.apika.apikaprobe.surface;

import java.io.ByteArrayOutputStream;

/**
 * Walks a vanilla {@code MaterialRules} tree (rooted at a
 * {@code MaterialRule} or {@code MaterialCondition}) and emits a flat
 * opcode stream defined by {@link RuleBytecode}.
 *
 * Spec: docs/SURFACE_RULE_BUFFER_SPEC.md
 *
 * <h3>Why reflection</h3>
 * Vanilla's surface-rule node classes ({@code BlockMaterialRule},
 * {@code BiomeMaterialCondition}, etc.) are package-private. We don't
 * want an access widener for what is fundamentally a one-time per-world
 * compile step, so we dispatch on
 * {@code node.getClass().getSimpleName()} and pull operands via
 * reflection. The shape stays small because this only runs once at
 * world load — runtime cost lives in the evaluator, not here.
 *
 * <h3>Spike scope (this commit)</h3>
 * Operand extraction is intentionally stubbed. This pass establishes
 * the visit/emit/fallback skeleton and the simple-name dispatch table.
 * Each opcode currently emits the opcode byte only; immediates are a
 * follow-up. The unit test target is just:
 *   - default overworld tree compiles without {@link CompiledRuleTree#hasFallback hasFallback}
 *   - opcodeCount > 0
 *   - bytecode.length stays in a sane bound
 * That validates the dispatch table covers every node type the default
 * tree contains, which is the actual unknown.
 */
public final class SurfaceRuleCompiler {

	private final ByteArrayOutputStream out = new ByteArrayOutputStream(1024);
	private final PerWorldBlockStateTable.Builder tableBuilder = PerWorldBlockStateTable.builder();
	private final BiomeSetPool.Builder biomePoolBuilder = BiomeSetPool.builder();
	private final NoiseChannelPool.Builder noisePoolBuilder = NoiseChannelPool.builder();
	private final java.util.List<Integer> blockIdOffsets = new java.util.ArrayList<>();
	private final java.util.List<Integer> biomeIdOffsets = new java.util.ArrayList<>();
	private final java.util.List<Integer> noiseIdOffsets = new java.util.ArrayList<>();
	/** [offset_in_bytecode, target_position] pairs for forward jumps. */
	private final java.util.List<int[]> controlFlowPatches = new java.util.ArrayList<>();
	private boolean hasFallback = false;
	private int opcodeCount = 0;

	private SurfaceRuleCompiler() {}

	public static CompiledRuleTree compile(Object rootRule) {
		if (rootRule == null) {
			return CompiledRuleTree.fallbackOnly();
		}
		SurfaceRuleCompiler c = new SurfaceRuleCompiler();
		c.visitRule(rootRule);
		c.emit(RuleBytecode.OP_RETURN_DONE); // single trailing terminator
		PerWorldBlockStateTable table = c.tableBuilder.freeze();
		BiomeSetPool biomePool = c.biomePoolBuilder.freeze();
		NoiseChannelPool noisePool = c.noisePoolBuilder.freeze();
		byte[] bytecode = c.out.toByteArray();
		// Patch every recorded blockstate-ID slot from insertion-order
		// to final (sorted) ID so the bytecode is deterministic across
		// recompiles of the same tree.
		for (int off : c.blockIdOffsets) {
			int insertionId = readIntLE(bytecode, off);
			int finalId = table.finalIdFor(insertionId);
			writeIntLE(bytecode, off, finalId);
		}
		// Same patch pass for biome set pool IDs (u16-wide).
		for (int off : c.biomeIdOffsets) {
			int insertionId = readU16LE(bytecode, off);
			int finalId = biomePool.finalIdFor(insertionId);
			writeU16LE(bytecode, off, finalId);
		}
		// And for noise channel pool IDs (u16-wide).
		for (int off : c.noiseIdOffsets) {
			int insertionId = readU16LE(bytecode, off);
			int finalId = noisePool.finalIdFor(insertionId);
			writeU16LE(bytecode, off, finalId);
		}
		// Control-flow forward jumps: write resolved target into placeholder slot.
		for (int[] patch : c.controlFlowPatches) {
			writeIntLE(bytecode, patch[0], patch[1]);
		}
		return new CompiledRuleTree(
				bytecode, c.hasFallback, c.opcodeCount,
				table.idToState(), biomePool.idToSet(), noisePool.idToChannel());
	}

	private static int readIntLE(byte[] b, int off) {
		return (b[off] & 0xFF)
			| ((b[off + 1] & 0xFF) << 8)
			| ((b[off + 2] & 0xFF) << 16)
			| ((b[off + 3] & 0xFF) << 24);
	}

	private static void writeIntLE(byte[] b, int off, int v) {
		b[off]     = (byte)(v        & 0xFF);
		b[off + 1] = (byte)((v >>> 8)  & 0xFF);
		b[off + 2] = (byte)((v >>> 16) & 0xFF);
		b[off + 3] = (byte)((v >>> 24) & 0xFF);
	}

	private static int readU16LE(byte[] b, int off) {
		return (b[off] & 0xFF) | ((b[off + 1] & 0xFF) << 8);
	}

	private static void writeU16LE(byte[] b, int off, int v) {
		b[off]     = (byte)(v       & 0xFF);
		b[off + 1] = (byte)((v >>> 8) & 0xFF);
	}

	/**
	 * Walks the rule tree counting node types by simple-name without
	 * emitting bytecode. Answers spec open-item #1 (which conditions
	 * appear in the default tree, and how often).
	 *
	 * Returns a map of simple-name → count. Sorted alphabetically for
	 * stable output. An "_UNKNOWN" entry, if present, indicates nodes
	 * the dispatch table doesn't recognise — the same nodes that would
	 * trip {@link CompiledRuleTree#hasFallback}.
	 */
	public static java.util.Map<String, Integer> collectStats(Object rootRule) {
		java.util.TreeMap<String, Integer> out = new java.util.TreeMap<>();
		if (rootRule == null) return out;
		walkForStats(rootRule, out);
		return out;
	}

	private static final java.util.Set<String> KNOWN_NODES = java.util.Set.of(
		"BlockMaterialRule", "SimpleBlockStateRule",
		"SequenceMaterialRule", "SequenceBlockStateRule",
		"ConditionMaterialRule", "ConditionalBlockStateRule",
		"TerracottaBandsMaterialRule",
		"AboveYMaterialCondition", "NoiseThresholdMaterialCondition",
		"VerticalGradientMaterialCondition", "StoneDepthMaterialCondition",
		"WaterMaterialCondition", "HoleMaterialCondition",
		"SurfaceMaterialCondition", "BiomeMaterialCondition",
		"TemperatureMaterialCondition", "SteepMaterialCondition",
		"NotMaterialCondition"
	);

	/**
	 * Gate for both the compile- and stats-path recursion. Skips lambda
	 * implementations and other synthetic/anonymous classes that the
	 * field walk reaches when descending into a node's predicates.
	 *
	 * Bug it fixes: BiomeMaterialCondition holds its biome predicate as
	 * a field; without this gate the walker descended into the lambda
	 * class, classified it as _UNKNOWN, and double-counted (39 lambdas
	 * for 39 biome conditions). The gate is shared between compile and
	 * stats so the two views agree on which children count.
	 */
	private static boolean isRuleNodeCandidate(Object v) {
		Class<?> c = v.getClass();
		if (c.isSynthetic() || c.isAnonymousClass() || c.isLocalClass()) return false;
		String pkg = c.getPackageName();
		return pkg.contains("surface");
	}

	private static void walkForStats(Object node, java.util.Map<String, Integer> counts) {
		String name = node.getClass().getSimpleName();
		String key = KNOWN_NODES.contains(name) ? name : ("_UNKNOWN:" + name);
		counts.merge(key, 1, Integer::sum);

		Class<?> c = node.getClass();
		while (c != null && c != Object.class) {
			for (java.lang.reflect.Field f : c.getDeclaredFields()) {
				if (java.lang.reflect.Modifier.isStatic(f.getModifiers())) continue;
				try {
					f.setAccessible(true);
					Object v = f.get(node);
					if (v == null) continue;
					recurseStatsIfRuleNode(v, counts);
				} catch (ReflectiveOperationException | RuntimeException ignored) {
					// skip
				}
			}
			c = c.getSuperclass();
		}
	}

	private static void recurseStatsIfRuleNode(Object v, java.util.Map<String, Integer> counts) {
		if (v instanceof java.util.List<?> list) {
			for (Object item : list) recurseStatsIfRuleNode(item, counts);
			return;
		}
		if (isRuleNodeCandidate(v)) {
			walkForStats(v, counts);
		}
	}

	private void emit(byte opcode) {
		out.write(opcode & 0xFF);
		opcodeCount++;
	}

	private void emitInt(int v) {
		out.write(v        & 0xFF);
		out.write((v >>> 8)  & 0xFF);
		out.write((v >>> 16) & 0xFF);
		out.write((v >>> 24) & 0xFF);
	}

	private void emitU8(boolean b) {
		out.write(b ? 1 : 0);
	}

	private void emitU16(int v) {
		out.write(v        & 0xFF);
		out.write((v >>> 8) & 0xFF);
	}

	private void emitDouble(double d) {
		long bits = Double.doubleToRawLongBits(d);
		for (int i = 0; i < 8; i++) {
			out.write((int)(bits >>> (i * 8)) & 0xFF);
		}
	}

	/**
	 * Read an int field by name from a node, with a default if missing.
	 * Used for operand extraction across vanilla and synthetic test nodes.
	 */
	private static int readIntField(Object node, String name, int dflt) {
		try {
			java.lang.reflect.Field f = findField(node.getClass(), name);
			if (f == null) return dflt;
			f.setAccessible(true);
			return f.getInt(node);
		} catch (ReflectiveOperationException e) {
			return dflt;
		}
	}

	/**
	 * Read an enum-valued field and return its ordinal as a byte
	 * (defaults to 0). Used for StoneDepth's surfaceType
	 * (VerticalSurfaceType.FLOOR=0, CEILING=1).
	 */
	private static boolean readEnumOrdinalField(Object node, String name) {
		try {
			java.lang.reflect.Field f = findField(node.getClass(), name);
			if (f == null) return false;
			f.setAccessible(true);
			Object v = f.get(node);
			if (v instanceof Enum<?> e) {
				return e.ordinal() != 0;
			}
			return false;
		} catch (ReflectiveOperationException e) {
			return false;
		}
	}

	private static double readDoubleField(Object node, String name, double dflt) {
		try {
			java.lang.reflect.Field f = findField(node.getClass(), name);
			if (f == null) return dflt;
			f.setAccessible(true);
			return f.getDouble(node);
		} catch (ReflectiveOperationException e) {
			return dflt;
		}
	}

	private static boolean readBoolField(Object node, String name, boolean dflt) {
		try {
			java.lang.reflect.Field f = findField(node.getClass(), name);
			if (f == null) return dflt;
			f.setAccessible(true);
			return f.getBoolean(node);
		} catch (ReflectiveOperationException e) {
			return dflt;
		}
	}

	/**
	 * Extract the BlockState (or Block / state-like object) from a
	 * leaf rule. Vanilla yarn uses {@code state} on
	 * {@code SimpleBlockStateRule} and {@code resultState} or
	 * {@code state} on {@code BlockMaterialRule} depending on version.
	 * Try the common names; fall back to "any non-null, non-collection,
	 * non-rule-package field".
	 */
	private static Object readStateField(Object node) {
		for (String n : new String[]{"state", "resultState", "block", "blockState"}) {
			java.lang.reflect.Field f = findField(node.getClass(), n);
			if (f == null) continue;
			try {
				f.setAccessible(true);
				Object v = f.get(node);
				if (v != null) return v;
			} catch (ReflectiveOperationException e) {
				// keep trying other names
			}
		}
		// Fallback walk: first non-null field whose package isn't ours.
		Class<?> c = node.getClass();
		while (c != null && c != Object.class) {
			for (java.lang.reflect.Field f : c.getDeclaredFields()) {
				if (java.lang.reflect.Modifier.isStatic(f.getModifiers())) continue;
				try {
					f.setAccessible(true);
					Object v = f.get(node);
					if (v == null) continue;
					if (v instanceof java.util.Collection<?>) continue;
					String pkg = v.getClass().getPackageName();
					if (pkg.contains("surface")) continue;
					return v;
				} catch (ReflectiveOperationException ignored) {
					// skip
				}
			}
			c = c.getSuperclass();
		}
		return null;
	}

	/**
	 * Extract the noise reference (RegistryKey or similar) from a
	 * {@code NoiseThresholdMaterialCondition}. Vanilla yarn typically
	 * names this field {@code noise}.
	 */
	private static Object readNoiseField(Object node) {
		for (String n : new String[]{"noise", "noiseKey", "noiseId"}) {
			java.lang.reflect.Field f = findField(node.getClass(), n);
			if (f == null) continue;
			try {
				f.setAccessible(true);
				Object v = f.get(node);
				if (v != null) return v;
			} catch (ReflectiveOperationException ignored) {
				// keep trying
			}
		}
		return null;
	}

	/**
	 * Extract the biome collection from a {@code BiomeMaterialCondition}.
	 * Vanilla yarn typically names this {@code biomes} (a
	 * {@code RegistryEntryList<Biome>} or {@code Set<RegistryKey<Biome>>}).
	 * The caller hands the result to {@link BiomeSetPool#canonicalKey},
	 * which handles whatever shape it actually has.
	 */
	private static Object readBiomesField(Object node) {
		for (String n : new String[]{"biomes", "biomeKeys", "biomeSet"}) {
			java.lang.reflect.Field f = findField(node.getClass(), n);
			if (f == null) continue;
			try {
				f.setAccessible(true);
				Object v = f.get(node);
				if (v != null) return v;
			} catch (ReflectiveOperationException ignored) {
				// keep trying
			}
		}
		// Fallback: first non-null Iterable / non-collection-but-stream-able field.
		Class<?> c = node.getClass();
		while (c != null && c != Object.class) {
			for (java.lang.reflect.Field f : c.getDeclaredFields()) {
				if (java.lang.reflect.Modifier.isStatic(f.getModifiers())) continue;
				try {
					f.setAccessible(true);
					Object v = f.get(node);
					if (v == null) continue;
					if (v instanceof Iterable<?>) return v;
					try {
						v.getClass().getMethod("stream");
						return v;
					} catch (NoSuchMethodException ignored) {
						// not stream-able, skip
					}
				} catch (ReflectiveOperationException ignored) {
					// skip
				}
			}
			c = c.getSuperclass();
		}
		return null;
	}

	private static java.lang.reflect.Field findField(Class<?> c, String name) {
		while (c != null && c != Object.class) {
			for (java.lang.reflect.Field f : c.getDeclaredFields()) {
				if (f.getName().equals(name)) return f;
			}
			c = c.getSuperclass();
		}
		return null;
	}

	private void visitRule(Object node) {
		String name = node.getClass().getSimpleName();
		switch (name) {
			case "BlockMaterialRule",
				 "SimpleBlockStateRule" -> {
				emit(RuleBytecode.OP_BLOCK);
				Object state = readStateField(node);
				int insertionId = tableBuilder.intern(state);
				blockIdOffsets.add(out.size()); // record patch site BEFORE writing
				emitInt(insertionId);
			}
			case "TerracottaBandsMaterialRule" -> {
				// Recognised vanilla node, but operand layout (random
				// splitter seed + band palette) isn't ported yet. Emit
				// fallback so the chunk routes back to vanilla.
				emit(RuleBytecode.OP_FALLBACK);
				hasFallback = true;
			}
			case "SequenceMaterialRule",
				 "SequenceBlockStateRule" -> emitSequence(node);
			case "ConditionMaterialRule",
				 "ConditionalBlockStateRule" -> emitCondition(node);
			default -> {
				// Could be a Condition node delivered as the root of a
				// sub-tree, or an unknown rule type. Try the condition
				// dispatch before giving up.
				if (!tryVisitCondition(node)) {
					emit(RuleBytecode.OP_FALLBACK);
					hasFallback = true;
				}
			}
		}
	}

	/**
	 * Condition shape:
	 *   [cond opcodes]
	 *   IF_ELSE @then @else
	 *   @then: [child opcodes]
	 *   @else: (immediately after — implicit fall-through with empty acc)
	 *
	 * The else-target equals "position right after the then-branch" because
	 * ConditionMaterialRule has no explicit else clause. If cond is false,
	 * IF_ELSE jumps past the then-branch with the value accumulator unset.
	 * If cond is true, IF_ELSE jumps to the then-branch which executes and
	 * naturally falls through to the same position.
	 */
	private void emitCondition(Object node) {
		java.util.List<Object> kids = collectSurfaceTypedFields(node);
		if (kids.size() < 2) {
			emit(RuleBytecode.OP_FALLBACK);
			hasFallback = true;
			return;
		}
		Object cond = kids.get(0);
		Object child = kids.get(1);
		visitRule(cond);
		emit(RuleBytecode.OP_IF_ELSE);
		int thenSlot = out.size(); emitInt(0); // placeholder
		int elseSlot = out.size(); emitInt(0); // placeholder
		int thenTarget = out.size();
		visitRule(child);
		int elseTarget = out.size(); // immediately past then-branch
		controlFlowPatches.add(new int[]{thenSlot, thenTarget});
		controlFlowPatches.add(new int[]{elseSlot, elseTarget});
	}

	/**
	 * Sequence shape:
	 *   [c1 opcodes] SEQUENCE_NEXT @end
	 *   [c2 opcodes] SEQUENCE_NEXT @end
	 *   ...
	 *   [cN opcodes] SEQUENCE_NEXT @end
	 *   @end: (immediately after the last SEQUENCE_NEXT)
	 *
	 * Each SEQUENCE_NEXT means: "if value accumulator is non-empty, jump to
	 * @end (skipping remaining alternatives); else fall through to next
	 * child." All SEQUENCE_NEXT in a given sequence target the same @end
	 * position, which is "immediately after the last SEQUENCE_NEXT in this
	 * sequence" — i.e. where the enclosing scope continues.
	 */
	private void emitSequence(Object node) {
		java.util.List<?> children = readSequenceChildren(node);
		if (children == null) {
			emit(RuleBytecode.OP_FALLBACK);
			hasFallback = true;
			return;
		}
		int numChildren = children.size();
		int[] endSlots = new int[numChildren];
		for (int i = 0; i < numChildren; i++) {
			Object child = children.get(i);
			visitRule(child);
			emit(RuleBytecode.OP_SEQUENCE_NEXT);
			endSlots[i] = out.size();
			emitInt(0); // placeholder for end_offset
		}
		int endTarget = out.size();
		for (int slot : endSlots) {
			controlFlowPatches.add(new int[]{slot, endTarget});
		}
	}

	private static java.util.List<Object> collectSurfaceTypedFields(Object node) {
		java.util.List<Object> result = new java.util.ArrayList<>();
		Class<?> c = node.getClass();
		while (c != null && c != Object.class) {
			for (java.lang.reflect.Field f : c.getDeclaredFields()) {
				if (java.lang.reflect.Modifier.isStatic(f.getModifiers())) continue;
				try {
					f.setAccessible(true);
					Object v = f.get(node);
					if (v == null) continue;
					String pkg = v.getClass().getPackageName();
					if (pkg.contains("surface")) result.add(v);
				} catch (ReflectiveOperationException ignored) {
					// skip
				}
			}
			c = c.getSuperclass();
		}
		return result;
	}

	private static java.util.List<?> readSequenceChildren(Object node) {
		Class<?> c = node.getClass();
		while (c != null && c != Object.class) {
			for (java.lang.reflect.Field f : c.getDeclaredFields()) {
				if (java.lang.reflect.Modifier.isStatic(f.getModifiers())) continue;
				try {
					f.setAccessible(true);
					Object v = f.get(node);
					if (v instanceof java.util.List<?> list) return list;
				} catch (ReflectiveOperationException ignored) {
					// skip
				}
			}
			c = c.getSuperclass();
		}
		return null;
	}

	private boolean tryVisitCondition(Object node) {
		String name = node.getClass().getSimpleName();
		switch (name) {
			case "AboveYMaterialCondition" -> {
				// Vanilla: AboveY(YOffset anchor, int surfaceDepthMultiplier,
				// boolean addStoneDepthBelow). YOffset is resolved to an
				// absolute Y at compile time via the height context (deferred
				// to the live-tree pass — synthetic test nodes carry the
				// already-resolved field directly as `surfaceDepthMultiplier`).
				emit(RuleBytecode.OP_ABOVE_Y);
				emitInt(readIntField(node, "surfaceDepthMultiplier", 0));
				emitU8(readBoolField(node, "addStoneDepthBelow", false));
			}
			case "NoiseThresholdMaterialCondition" -> {
				emit(RuleBytecode.OP_NOISE_THRESH);
				Object noiseRef = readNoiseField(node);
				String channelName = NoiseChannelPool.resolveName(noiseRef);
				int insertionId = noisePoolBuilder.intern(channelName);
				noiseIdOffsets.add(out.size()); // patch site BEFORE writing
				emitU16(insertionId);
				emitDouble(readDoubleField(node, "minThreshold", 0.0));
				emitDouble(readDoubleField(node, "maxThreshold", 0.0));
			}
			case "VerticalGradientMaterialCondition" -> {
				// Real seed extraction needs vanilla's randomDeriver (a
				// per-world RNG splitter that produces a deterministic
				// double per-Y from the seed Identifier). Without booting
				// MC the splitter can't be reproduced, so emit fallback
				// for this condition. Two occurrences in the default
				// overworld tree — rounding error against the 528-node
				// total.
				emit(RuleBytecode.OP_FALLBACK);
				hasFallback = true;
			}
			case "StoneDepthMaterialCondition" -> {
				// Vanilla: StoneDepth(int offset, boolean addSurfaceDepth,
				// int secondaryDepthRange, VerticalSurfaceType surfaceType)
				emit(RuleBytecode.OP_STONE_DEPTH);
				emitInt(readIntField(node, "offset", 0));
				emitU8(readBoolField(node, "addSurfaceDepth", false));
				emitInt(readIntField(node, "secondaryDepthRange", 0));
				emitU8(readEnumOrdinalField(node, "surfaceType"));
			}
			case "WaterMaterialCondition" -> {
				// Vanilla: Water(int offset, int surfaceDepthMultiplier,
				// boolean addStoneDepthBelow)
				emit(RuleBytecode.OP_WATER);
				emitInt(readIntField(node, "offset", 0));
				emitInt(readIntField(node, "surfaceDepthMultiplier", 0));
				emitU8(readBoolField(node, "addStoneDepthBelow", false));
			}
			case "HoleMaterialCondition"            -> emit(RuleBytecode.OP_HOLE);
			case "SurfaceMaterialCondition"         -> emit(RuleBytecode.OP_SURFACE);
			case "BiomeMaterialCondition" -> {
				emit(RuleBytecode.OP_BIOME);
				Object biomes = readBiomesField(node);
				java.util.List<String> key = BiomeSetPool.canonicalKey(biomes);
				int insertionId = biomePoolBuilder.intern(key);
				biomeIdOffsets.add(out.size()); // patch site BEFORE writing
				emitU16(insertionId);
			}
			case "TemperatureMaterialCondition"     -> emit(RuleBytecode.OP_TEMPERATURE);
			case "SteepMaterialCondition"           -> emit(RuleBytecode.OP_STEEP);
			case "NotMaterialCondition" -> {
				emit(RuleBytecode.OP_NOT);
				visitChildren(node);
			}
			default -> { return false; }
		}
		return true;
	}

	/**
	 * Best-effort recursion: walks the node's declared fields and
	 * recurses into anything whose class lives in the
	 * {@code MaterialRules} package. This is enough for the spike —
	 * opcode count + fallback verdict only depend on which node types
	 * we encountered, not on operand layout.
	 */
	private void visitChildren(Object node) {
		Class<?> c = node.getClass();
		while (c != null && c != Object.class) {
			for (java.lang.reflect.Field f : c.getDeclaredFields()) {
				if (java.lang.reflect.Modifier.isStatic(f.getModifiers())) continue;
				try {
					f.setAccessible(true);
					Object v = f.get(node);
					if (v == null) continue;
					recurseIfRuleNode(v);
				} catch (ReflectiveOperationException | RuntimeException ignored) {
					// Field unreadable — skip. Safe because opcode
					// count is the only signal at this stage.
				}
			}
			c = c.getSuperclass();
		}
	}

	private void recurseIfRuleNode(Object v) {
		if (v instanceof java.util.List<?> list) {
			for (Object item : list) recurseIfRuleNode(item);
			return;
		}
		if (isRuleNodeCandidate(v)) {
			visitRule(v);
		}
	}
}
