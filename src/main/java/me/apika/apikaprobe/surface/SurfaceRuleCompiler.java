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
	private boolean hasFallback = false;
	private int opcodeCount = 0;

	private SurfaceRuleCompiler() {}

	public static CompiledRuleTree compile(Object rootRule) {
		if (rootRule == null) {
			return CompiledRuleTree.fallbackOnly();
		}
		SurfaceRuleCompiler c = new SurfaceRuleCompiler();
		c.visitRule(rootRule);
		return new CompiledRuleTree(c.out.toByteArray(), c.hasFallback, c.opcodeCount);
	}

	private void emit(byte opcode) {
		out.write(opcode & 0xFF);
		opcodeCount++;
	}

	private void visitRule(Object node) {
		String name = node.getClass().getSimpleName();
		switch (name) {
			case "BlockMaterialRule",
				 "SimpleBlockStateRule" -> emit(RuleBytecode.OP_BLOCK);
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
			case "AboveYMaterialCondition"          -> emit(RuleBytecode.OP_ABOVE_Y);
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
		String pkg = v.getClass().getPackageName();
		// Recurse into vanilla MaterialRules nodes and into our own
		// synthetic test nodes (apikaprobe.surface). The "surface"
		// substring is broad enough to match both without recursing
		// into BlockState / String / primitive fields.
		if (pkg.contains("surface")) {
			visitRule(v);
		}
	}
}
