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
	private final java.util.List<Integer> blockIdOffsets = new java.util.ArrayList<>();
	private boolean hasFallback = false;
	private int opcodeCount = 0;

	private SurfaceRuleCompiler() {}

	public static CompiledRuleTree compile(Object rootRule) {
		if (rootRule == null) {
			return CompiledRuleTree.fallbackOnly();
		}
		SurfaceRuleCompiler c = new SurfaceRuleCompiler();
		c.visitRule(rootRule);
		PerWorldBlockStateTable table = c.tableBuilder.freeze();
		byte[] bytecode = c.out.toByteArray();
		// Patch every recorded blockstate-ID slot from insertion-order
		// to final (sorted) ID so the bytecode is deterministic across
		// recompiles of the same tree.
		for (int off : c.blockIdOffsets) {
			int insertionId = readIntLE(bytecode, off);
			int finalId = table.finalIdFor(insertionId);
			writeIntLE(bytecode, off, finalId);
		}
		return new CompiledRuleTree(bytecode, c.hasFallback, c.opcodeCount, table.idToState());
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
				 "SequenceBlockStateRule" -> {
				emit(RuleBytecode.OP_SEQUENCE);
				visitChildren(node);
			}
			case "ConditionMaterialRule",
				 "ConditionalBlockStateRule" -> {
				emit(RuleBytecode.OP_CONDITION);
				visitChildren(node);
			}
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
			case "NoiseThresholdMaterialCondition"  -> emit(RuleBytecode.OP_NOISE_THRESH);
			case "VerticalGradientMaterialCondition"-> emit(RuleBytecode.OP_VERT_GRADIENT);
			case "StoneDepthMaterialCondition"      -> emit(RuleBytecode.OP_STONE_DEPTH);
			case "WaterMaterialCondition"           -> emit(RuleBytecode.OP_WATER);
			case "HoleMaterialCondition"            -> emit(RuleBytecode.OP_HOLE);
			case "SurfaceMaterialCondition"         -> emit(RuleBytecode.OP_SURFACE);
			case "BiomeMaterialCondition"           -> emit(RuleBytecode.OP_BIOME);
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
