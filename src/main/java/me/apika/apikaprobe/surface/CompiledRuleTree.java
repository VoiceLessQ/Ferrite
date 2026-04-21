package me.apika.apikaprobe.surface;

/**
 * Output of {@link SurfaceRuleCompiler}. A flat opcode stream plus
 * metadata about whether the source tree contained any nodes the
 * compiler doesn't recognise.
 *
 * If {@link #hasFallback} is true, the dispatcher must route this
 * chunk's surface eval back to vanilla — the bytecode may be partial
 * or contain {@link RuleBytecode#OP_FALLBACK} sentinels.
 */
public record CompiledRuleTree(byte[] bytecode, boolean hasFallback, int opcodeCount) {

	public static CompiledRuleTree fallbackOnly() {
		return new CompiledRuleTree(new byte[]{RuleBytecode.OP_FALLBACK}, true, 1);
	}
}
