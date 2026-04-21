package me.apika.apikaprobe.surface;

import java.util.List;

/**
 * Standalone evaluator self-test. Compiles a few synthetic trees and
 * runs them through {@link SurfaceRuleEvaluator} with known inputs;
 * asserts the returned value matches the expected blockstate (or
 * null). Validates that the eval loop dispatches each opcode shape
 * correctly — NOT a vanilla-parity check (that's the validator's job).
 */
public final class SurfaceRuleEvaluatorSelfTest {

	public static void main(String[] args) {
		int failed = 0;
		failed += run("evalSingleBlock",        SurfaceRuleEvaluatorSelfTest::evalSingleBlock);
		failed += run("evalConditionalHoleHit", SurfaceRuleEvaluatorSelfTest::evalConditionalHoleHit);
		failed += run("evalConditionalHoleMiss",SurfaceRuleEvaluatorSelfTest::evalConditionalHoleMiss);
		failed += run("evalSequenceFirstHit",   SurfaceRuleEvaluatorSelfTest::evalSequenceFirstHit);
		failed += run("evalSequenceFallthrough",SurfaceRuleEvaluatorSelfTest::evalSequenceFallthrough);
		failed += run("evalNotInversion",       SurfaceRuleEvaluatorSelfTest::evalNotInversion);
		failed += run("evalBiomeMatch",         SurfaceRuleEvaluatorSelfTest::evalBiomeMatch);
		failed += run("evalNoiseInRange",       SurfaceRuleEvaluatorSelfTest::evalNoiseInRange);
		failed += run("evalFallbackTreeReturnsNull", SurfaceRuleEvaluatorSelfTest::evalFallbackTreeReturnsNull);

		if (failed == 0) {
			System.out.println("[surface-eval-self-test] ALL PASS");
			System.exit(0);
		} else {
			System.out.println("[surface-eval-self-test] " + failed + " FAILED");
			System.exit(1);
		}
	}

	private static int run(String name, Runnable test) {
		try {
			test.run();
			System.out.println("  pass  " + name);
			return 0;
		} catch (Throwable t) {
			System.out.println("  FAIL  " + name + " — " + t.getMessage());
			return 1;
		}
	}

	private static void assertSame(String what, Object expected, Object actual) {
		if (expected == null && actual == null) return;
		if (expected == null || actual == null || !expected.toString().equals(actual.toString())) {
			throw new AssertionError(what + " expected=" + expected + " actual=" + actual);
		}
	}

	// --- helpers -----------------------------------------------------------

	private static ColumnContext ctx(int runDepth) {
		return new ColumnContext("minecraft:plains", 64, runDepth, 5, 5, 63, false, false, 64, new double[8]);
	}

	private static ColumnContext ctxFor(String biome, double[] noise) {
		return new ColumnContext(biome, 64, 5, 5, 5, 63, false, false, 64, noise);
	}

	// --- tests -------------------------------------------------------------

	private static void evalSingleBlock() {
		FakeState snow = new FakeState("snow");
		CompiledRuleTree t = SurfaceRuleCompiler.compile(new BlockMaterialRule(snow));
		Object r = SurfaceRuleEvaluator.evaluate(t, ctx(0));
		assertSame("single block returns its state", snow, r);
	}

	private static void evalConditionalHoleHit() {
		// Condition(Hole, Block(snow)) — when runDepth=0, Hole is true,
		// so the Block in the then-branch fires and returns snow.
		FakeState snow = new FakeState("snow");
		CompiledRuleTree t = SurfaceRuleCompiler.compile(
			new ConditionMaterialRule(new HoleMaterialCondition(), new BlockMaterialRule(snow)));
		Object r = SurfaceRuleEvaluator.evaluate(t, ctx(0));
		assertSame("hole hit → snow", snow, r);
	}

	private static void evalConditionalHoleMiss() {
		// Same shape; runDepth=10 → Hole false → IF_ELSE skips the
		// then-branch → no Block fires → returns null.
		FakeState snow = new FakeState("snow");
		CompiledRuleTree t = SurfaceRuleCompiler.compile(
			new ConditionMaterialRule(new HoleMaterialCondition(), new BlockMaterialRule(snow)));
		Object r = SurfaceRuleEvaluator.evaluate(t, ctx(10));
		assertSame("hole miss → null", null, r);
	}

	private static void evalSequenceFirstHit() {
		// Sequence(Block(a), Block(b)) — first child sets value, sequence
		// short-circuits via SEQUENCE_NEXT jumping past the rest.
		FakeState a = new FakeState("a");
		FakeState b = new FakeState("b");
		CompiledRuleTree t = SurfaceRuleCompiler.compile(
			new SequenceMaterialRule(List.of(new BlockMaterialRule(a), new BlockMaterialRule(b))));
		Object r = SurfaceRuleEvaluator.evaluate(t, ctx(0));
		assertSame("first sequence child wins", a, r);
	}

	private static void evalSequenceFallthrough() {
		// Sequence(Cond(Hole, Block(a)), Block(b)) — when runDepth=10,
		// first child fails to set value (Hole false), sequence falls
		// through to second child which unconditionally returns b.
		FakeState a = new FakeState("a");
		FakeState b = new FakeState("b");
		CompiledRuleTree t = SurfaceRuleCompiler.compile(
			new SequenceMaterialRule(List.of(
				new ConditionMaterialRule(new HoleMaterialCondition(), new BlockMaterialRule(a)),
				new BlockMaterialRule(b))));
		Object r = SurfaceRuleEvaluator.evaluate(t, ctx(10));
		assertSame("fallthrough to second child", b, r);
	}

	private static void evalNotInversion() {
		// Cond(Not(Hole), Block(a)) — runDepth=10: Hole false, NOT inverts
		// to true, then-branch fires.
		FakeState a = new FakeState("a");
		CompiledRuleTree t = SurfaceRuleCompiler.compile(
			new ConditionMaterialRule(
				new NotMaterialCondition(new HoleMaterialCondition()),
				new BlockMaterialRule(a)));
		Object r = SurfaceRuleEvaluator.evaluate(t, ctx(10));
		assertSame("NOT(Hole) when runDepth high → fires", a, r);
	}

	private static void evalBiomeMatch() {
		FakeState a = new FakeState("a");
		CompiledRuleTree t = SurfaceRuleCompiler.compile(
			new ConditionMaterialRule(
				new BiomeMaterialCondition(List.of("minecraft:plains", "minecraft:forest")),
				new BlockMaterialRule(a)));
		Object hit = SurfaceRuleEvaluator.evaluate(t, ctxFor("minecraft:plains", new double[0]));
		Object miss = SurfaceRuleEvaluator.evaluate(t, ctxFor("minecraft:desert", new double[0]));
		assertSame("biome in set → fires", a, hit);
		assertSame("biome not in set → null", null, miss);
	}

	private static void evalNoiseInRange() {
		FakeState a = new FakeState("a");
		double[] noise = new double[]{0.5}; // only channel 0
		CompiledRuleTree t = SurfaceRuleCompiler.compile(
			new ConditionMaterialRule(
				new NoiseThresholdMaterialCondition("minecraft:test_noise", 0.0, 1.0),
				new BlockMaterialRule(a)));
		assertSame("noise in range → fires", a,
			SurfaceRuleEvaluator.evaluate(t, ctxFor("any", noise)));
		assertSame("noise out of range → null", null,
			SurfaceRuleEvaluator.evaluate(t, ctxFor("any", new double[]{2.0})));
	}

	private static void evalFallbackTreeReturnsNull() {
		// VerticalGradient compiles to OP_FALLBACK + hasFallback=true.
		// Evaluator must return null without trying to walk further.
		CompiledRuleTree t = SurfaceRuleCompiler.compile(new VerticalGradientMaterialCondition());
		Object r = SurfaceRuleEvaluator.evaluate(t, ctx(0));
		assertSame("fallback tree → null", null, r);
	}

	// --- synthetic node classes (mirror the compiler test set) ------------

	static final class FakeState {
		final String name;
		FakeState(String name) { this.name = name; }
		@Override public String toString() { return name; }
	}

	@SuppressWarnings("unused")
	static final class BlockMaterialRule {
		final FakeState state;
		BlockMaterialRule(FakeState state) { this.state = state; }
	}

	static final class SequenceMaterialRule {
		@SuppressWarnings("unused") final List<Object> children;
		SequenceMaterialRule(List<Object> children) { this.children = children; }
	}

	static final class ConditionMaterialRule {
		@SuppressWarnings("unused") final Object condition;
		@SuppressWarnings("unused") final Object child;
		ConditionMaterialRule(Object condition, Object child) {
			this.condition = condition;
			this.child = child;
		}
	}

	static final class HoleMaterialCondition {}

	static final class NotMaterialCondition {
		@SuppressWarnings("unused") final Object child;
		NotMaterialCondition(Object child) { this.child = child; }
	}

	@SuppressWarnings("unused")
	static final class BiomeMaterialCondition {
		final List<String> biomes;
		BiomeMaterialCondition(List<String> biomes) { this.biomes = biomes; }
	}

	@SuppressWarnings("unused")
	static final class NoiseThresholdMaterialCondition {
		final String noise;
		final double minThreshold;
		final double maxThreshold;
		NoiseThresholdMaterialCondition(String noise, double min, double max) {
			this.noise = noise;
			this.minThreshold = min;
			this.maxThreshold = max;
		}
	}

	static final class VerticalGradientMaterialCondition {}
}
