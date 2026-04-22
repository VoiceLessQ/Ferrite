package me.apika.apikaprobe.surface;

/**
 * Per-column input bundle for {@link SurfaceRuleEvaluator}.
 *
 * Mirrors the per-column region of the buffer spec
 * (docs/SURFACE_RULE_BUFFER_SPEC.md), with the biome surfaced as a
 * registry-name string instead of an integer ID — the evaluator does
 * set-membership against {@link CompiledRuleTree#biomeSetTable} which
 * holds names. The integer-ID encoding is a perf-only refinement for
 * the future Rust port; for the Java validator, strings are clearer.
 *
 * {@link #noiseValues} is indexed by the final (sorted) noise channel
 * ID — i.e. the same index Rust would use after the channel pool is
 * frozen. Caller pre-samples each noise channel referenced in the
 * compiled tree before invoking the evaluator.
 */
public record ColumnContext(
		String biomeName,
		int blockY,
		int runDepth,
		int stoneDepthAbove,
		int stoneDepthBelow,
		int fluidHeight,
		boolean isCold,
		boolean isSteep,
		int surfaceHeight,
		double secondaryDepth,
		double[] noiseValues) {
}
