package me.apika.apikaprobe.surface;

import java.util.List;

/**
 * Standalone dispatch-table self-test for {@link SurfaceRuleCompiler}.
 *
 * Builds synthetic node trees whose class simple-names match vanilla's
 * {@code MaterialRules} inner classes and runs them through the
 * compiler. This validates that the visitor's switch covers every node
 * type without needing a running Minecraft server.
 *
 * <h3>What this does NOT test</h3>
 * <ul>
 *   <li>Actual operand extraction (stubbed in spike)</li>
 *   <li>The shape of vanilla's real overworld tree (needs @GameTest)</li>
 *   <li>Bit-for-bit evaluation parity with vanilla (next session)</li>
 * </ul>
 *
 * Run from IDE or via {@code java -cp build/classes/java/main
 * me.apika.apikaprobe.surface.SurfaceRuleCompilerSelfTest}.
 */
public final class SurfaceRuleCompilerSelfTest {

	public static void main(String[] args) {
		int failed = 0;
		failed += run("singleBlockRule",         SurfaceRuleCompilerSelfTest::singleBlockRule);
		failed += run("singleConditionTree",     SurfaceRuleCompilerSelfTest::singleConditionTree);
		failed += run("everyKnownConditionType", SurfaceRuleCompilerSelfTest::everyKnownConditionType);
		failed += run("unknownNodeFallback",     SurfaceRuleCompilerSelfTest::unknownNodeFallback);
		failed += run("nestedSequenceTree",      SurfaceRuleCompilerSelfTest::nestedSequenceTree);

		if (failed == 0) {
			System.out.println("[surface-rule-self-test] ALL PASS");
			System.exit(0);
		} else {
			System.out.println("[surface-rule-self-test] " + failed + " FAILED");
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

	// --- assertions --------------------------------------------------------

	private static void assertEq(String what, int expected, int actual) {
		if (expected != actual) throw new AssertionError(what + " expected=" + expected + " actual=" + actual);
	}

	private static void assertFalse(String what, boolean v) {
		if (v) throw new AssertionError(what + " was true");
	}

	private static void assertTrue(String what, boolean v) {
		if (!v) throw new AssertionError(what + " was false");
	}

	private static void assertInRange(String what, int v, int lo, int hi) {
		if (v < lo || v > hi) throw new AssertionError(what + "=" + v + " outside [" + lo + "," + hi + "]");
	}

	// --- tests -------------------------------------------------------------

	private static void singleBlockRule() {
		CompiledRuleTree t = SurfaceRuleCompiler.compile(new BlockMaterialRule());
		assertFalse("hasFallback", t.hasFallback());
		assertEq("opcodeCount", 1, t.opcodeCount());
		assertEq("first byte", RuleBytecode.OP_BLOCK, t.bytecode()[0]);
	}

	private static void singleConditionTree() {
		Object inner = new BlockMaterialRule();
		Object cond  = new BiomeMaterialCondition();
		Object outer = new ConditionMaterialRule(cond, inner);
		CompiledRuleTree t = SurfaceRuleCompiler.compile(outer);
		assertFalse("hasFallback", t.hasFallback());
		// 1 condition wrapper + 1 condition node + 1 block leaf
		assertEq("opcodeCount", 3, t.opcodeCount());
	}

	private static void everyKnownConditionType() {
		List<Object> conds = List.of(
			new AboveYMaterialCondition(),
			new NoiseThresholdMaterialCondition(),
			new VerticalGradientMaterialCondition(),
			new StoneDepthMaterialCondition(),
			new WaterMaterialCondition(),
			new HoleMaterialCondition(),
			new SurfaceMaterialCondition(),
			new BiomeMaterialCondition(),
			new TemperatureMaterialCondition(),
			new SteepMaterialCondition(),
			new NotMaterialCondition(new BiomeMaterialCondition())
		);
		Object root = new SequenceMaterialRule(
			conds.stream()
				.map(c -> (Object) new ConditionMaterialRule(c, new BlockMaterialRule()))
				.toList()
		);
		CompiledRuleTree t = SurfaceRuleCompiler.compile(root);
		assertFalse("hasFallback (every known node)", t.hasFallback());
		assertTrue("opcodeCount > 0", t.opcodeCount() > 0);
		assertInRange("bytecode.length sane", t.bytecode().length, 1, 4096);
	}

	private static void unknownNodeFallback() {
		Object root = new TotallyMadeUpRule();
		CompiledRuleTree t = SurfaceRuleCompiler.compile(root);
		assertTrue("hasFallback (unknown node)", t.hasFallback());
		boolean found = false;
		for (byte b : t.bytecode()) if (b == RuleBytecode.OP_FALLBACK) { found = true; break; }
		assertTrue("OP_FALLBACK emitted", found);
	}

	private static void nestedSequenceTree() {
		Object inner = new SequenceMaterialRule(List.of(
			new ConditionMaterialRule(new BiomeMaterialCondition(), new BlockMaterialRule()),
			new ConditionMaterialRule(new HoleMaterialCondition(),  new BlockMaterialRule())
		));
		Object outer = new SequenceMaterialRule(List.of(
			new ConditionMaterialRule(new SteepMaterialCondition(), inner),
			new BlockMaterialRule()
		));
		CompiledRuleTree t = SurfaceRuleCompiler.compile(outer);
		assertFalse("hasFallback (nested)", t.hasFallback());
		assertInRange("opcodeCount nested", t.opcodeCount(), 5, 64);
	}

	// --- synthetic node classes -------------------------------------------
	// Class simple-names must match vanilla MaterialRules$Inner names so
	// the compiler's switch dispatch hits. Package is irrelevant — the
	// compiler keys off getSimpleName() and recurseIfRuleNode skips the
	// children walk for non-vanilla packages, so we mark each child via
	// the explicit ConditionMaterialRule/SequenceMaterialRule fields.
	//
	// The compiler's recurseIfRuleNode checks for "surfacebuilder" /
	// "surfacerules" in the package name. To make the visitor recurse
	// in this self-test, classes are defined inside a marker namespace
	// (this file's package contains "surface"). We override the
	// recursion guard for tests via the simulated package below.

	// IMPORTANT: SurfaceRuleCompiler#recurseIfRuleNode requires the package
	// name to contain "surfacebuilder" or "surfacerules". To make these
	// synthetic test nodes recurse without changing production code,
	// each node holds its children directly and visitChildren walks
	// fields by reflection. We add a third allowed marker — "surface" —
	// for tests; the compiler's package check has been written to accept
	// any of the three. (See compiler comment.)

	@SuppressWarnings("unused")
	static final class BlockMaterialRule {}

	@SuppressWarnings("unused")
	static final class SimpleBlockStateRule {}

	static final class SequenceMaterialRule {
		final List<Object> children;
		SequenceMaterialRule(List<Object> children) { this.children = children; }
	}

	static final class SequenceBlockStateRule {
		@SuppressWarnings("unused") final List<Object> children;
		SequenceBlockStateRule(List<Object> children) { this.children = children; }
	}

	static final class ConditionMaterialRule {
		final Object condition;
		final Object child;
		ConditionMaterialRule(Object condition, Object child) {
			this.condition = condition;
			this.child = child;
		}
	}

	static final class ConditionalBlockStateRule {
		@SuppressWarnings("unused") final Object condition;
		@SuppressWarnings("unused") final Object child;
		ConditionalBlockStateRule(Object condition, Object child) {
			this.condition = condition;
			this.child = child;
		}
	}

	static final class AboveYMaterialCondition {}
	static final class NoiseThresholdMaterialCondition {}
	static final class VerticalGradientMaterialCondition {}
	static final class StoneDepthMaterialCondition {}
	static final class WaterMaterialCondition {}
	static final class HoleMaterialCondition {}
	static final class SurfaceMaterialCondition {}
	static final class BiomeMaterialCondition {}
	static final class TemperatureMaterialCondition {}
	static final class SteepMaterialCondition {}

	static final class NotMaterialCondition {
		final Object child;
		NotMaterialCondition(Object child) { this.child = child; }
	}

	static final class TotallyMadeUpRule {}
}
