package me.apika.apikaprobe.surface;

/**
 * Output of {@link SurfaceRuleCompiler}. Flat opcode stream + the
 * tables its immediates index into + metadata about whether the source
 * tree contained any nodes the compiler doesn't recognise.
 *
 * If {@link #hasFallback} is true, the dispatcher must route this
 * chunk's surface eval back to vanilla — the bytecode may be partial
 * or contain {@link RuleBytecode#OP_FALLBACK} sentinels.
 */
public record CompiledRuleTree(
		byte[] bytecode,
		boolean hasFallback,
		int opcodeCount,
		Object[] blockstateTable,
		java.util.List<String>[] biomeSetTable,
		String[] noiseChannelTable,
		/** Identifier strings for each VerticalGradient rule's random_name (e.g.
		 * "minecraft:bedrock_floor", "minecraft:deepslate"). OP_VERT_GRADIENT
		 * indexes into this table to look up the per-block PRNG splitter at
		 * runtime. */
		String[] randomNameTable) {

	public static CompiledRuleTree fallbackOnly() {
		@SuppressWarnings("unchecked")
		java.util.List<String>[] empty = new java.util.List[0];
		return new CompiledRuleTree(
				new byte[]{RuleBytecode.OP_FALLBACK}, true, 1,
				new Object[0], empty, new String[0], new String[0]);
	}
}
