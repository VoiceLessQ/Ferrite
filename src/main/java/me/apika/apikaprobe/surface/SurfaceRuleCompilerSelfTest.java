package me.apika.apikaprobe.surface;

import java.util.List;

/**
 * Standalone dispatch-table self-test for {@link SurfaceRuleCompiler}.
 *
 * Builds synthetic node trees whose class simple-names match vanilla's
 * {@code SurfaceRules} inner classes and runs them through the
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
		failed += run("opHoleZeroOperands",      SurfaceRuleCompilerSelfTest::opHoleZeroOperands);
		failed += run("opSteepZeroOperands",     SurfaceRuleCompilerSelfTest::opSteepZeroOperands);
		failed += run("opSurfaceZeroOperands",   SurfaceRuleCompilerSelfTest::opSurfaceZeroOperands);
		failed += run("opTemperatureZeroOperands", SurfaceRuleCompilerSelfTest::opTemperatureZeroOperands);
		failed += run("opAboveYWithOperands",    SurfaceRuleCompilerSelfTest::opAboveYWithOperands);
		failed += run("opAboveYDefaultOperands", SurfaceRuleCompilerSelfTest::opAboveYDefaultOperands);
		failed += run("opBlockTableDedup",       SurfaceRuleCompilerSelfTest::opBlockTableDedup);
		failed += run("opBlockTableSorted",      SurfaceRuleCompilerSelfTest::opBlockTableSorted);
		failed += run("opBlockIdsDeterministic", SurfaceRuleCompilerSelfTest::opBlockIdsDeterministic);
		failed += run("opBiomePoolDedup",        SurfaceRuleCompilerSelfTest::opBiomePoolDedup);
		failed += run("opBiomePoolContentEq",    SurfaceRuleCompilerSelfTest::opBiomePoolContentEq);
		failed += run("opBiomePoolSorted",       SurfaceRuleCompilerSelfTest::opBiomePoolSorted);
		failed += run("opNoiseThreshLayout",     SurfaceRuleCompilerSelfTest::opNoiseThreshLayout);
		failed += run("opNoisePoolDedup",        SurfaceRuleCompilerSelfTest::opNoisePoolDedup);
		failed += run("opNoisePoolSorted",       SurfaceRuleCompilerSelfTest::opNoisePoolSorted);
		failed += run("opStoneDepthLayout",      SurfaceRuleCompilerSelfTest::opStoneDepthLayout);
		failed += run("opWaterLayout",           SurfaceRuleCompilerSelfTest::opWaterLayout);
		failed += run("opVertGradientFallback",  SurfaceRuleCompilerSelfTest::opVertGradientFallback);
		failed += run("opNotChild",              SurfaceRuleCompilerSelfTest::opNotChild);
		failed += run("opIfElseJumpTargets",     SurfaceRuleCompilerSelfTest::opIfElseJumpTargets);
		failed += run("opSequenceEndOffsets",    SurfaceRuleCompilerSelfTest::opSequenceEndOffsets);

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

	private static void assertEq(String what, String expected, String actual) {
		if (!java.util.Objects.equals(expected, actual)) {
			throw new AssertionError(what + " expected=" + expected + " actual=" + actual);
		}
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
		// opcodeCount = OP_BLOCK + trailing OP_RETURN_DONE = 2
		assertEq("opcodeCount", 2, t.opcodeCount());
		// bytecode = OP_BLOCK(1) + u32(4) + OP_RETURN_DONE(1) = 6
		assertEq("bytecode length", 6, t.bytecode().length);
		assertEq("first byte", RuleBytecode.OP_BLOCK, t.bytecode()[0]);
		assertEq("trailing terminator", RuleBytecode.OP_RETURN_DONE, t.bytecode()[5]);
		assertEq("table size (1 unique state)", 1, t.blockstateTable().length);
	}

	private static void singleConditionTree() {
		Object inner = new BlockMaterialRule();
		Object cond  = new BiomeMaterialCondition();
		Object outer = new ConditionMaterialRule(cond, inner);
		CompiledRuleTree t = SurfaceRuleCompiler.compile(outer);
		assertFalse("hasFallback", t.hasFallback());
		// BIOME + IF_ELSE + BLOCK + RETURN_DONE = 4 opcodes
		assertEq("opcodeCount", 4, t.opcodeCount());
	}

	private static void everyKnownConditionType() {
		// VerticalGradient is intentionally OP_FALLBACK (random seed
		// extraction not implemented) — exclude from the no-fallback
		// roundup. It still appears in the dispatch table.
		List<Object> conds = List.of(
			new AboveYMaterialCondition(),
			new NoiseThresholdMaterialCondition(),
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

	// --- operand-extraction tests (items 1-5) -----------------------------

	private static void assertBytecode(String what, byte[] actual, byte... expected) {
		if (actual.length != expected.length) {
			throw new AssertionError(what + " length expected=" + expected.length + " actual=" + actual.length);
		}
		for (int i = 0; i < expected.length; i++) {
			if (actual[i] != expected[i]) {
				throw new AssertionError(what + " byte[" + i + "] expected=" + (expected[i] & 0xFF)
						+ " actual=" + (actual[i] & 0xFF));
			}
		}
	}

	private static byte[] le32(int v) {
		return new byte[]{
			(byte)(v        & 0xFF),
			(byte)((v >>> 8)  & 0xFF),
			(byte)((v >>> 16) & 0xFF),
			(byte)((v >>> 24) & 0xFF)
		};
	}

	private static byte[] concat(byte[] a, byte[] b) {
		byte[] r = new byte[a.length + b.length];
		System.arraycopy(a, 0, r, 0, a.length);
		System.arraycopy(b, 0, r, a.length, b.length);
		return r;
	}

	private static byte[] withTerminator(byte... body) {
		byte[] out = new byte[body.length + 1];
		System.arraycopy(body, 0, out, 0, body.length);
		out[body.length] = RuleBytecode.OP_RETURN_DONE;
		return out;
	}

	private static void opHoleZeroOperands() {
		CompiledRuleTree t = SurfaceRuleCompiler.compile(new HoleMaterialCondition());
		assertFalse("hasFallback", t.hasFallback());
		assertBytecode("hole bytes", t.bytecode(), withTerminator(RuleBytecode.OP_HOLE));
	}

	private static void opSteepZeroOperands() {
		CompiledRuleTree t = SurfaceRuleCompiler.compile(new SteepMaterialCondition());
		assertFalse("hasFallback", t.hasFallback());
		assertBytecode("steep bytes", t.bytecode(), withTerminator(RuleBytecode.OP_STEEP));
	}

	private static void opSurfaceZeroOperands() {
		CompiledRuleTree t = SurfaceRuleCompiler.compile(new SurfaceMaterialCondition());
		assertFalse("hasFallback", t.hasFallback());
		assertBytecode("surface bytes", t.bytecode(), withTerminator(RuleBytecode.OP_SURFACE));
	}

	private static void opTemperatureZeroOperands() {
		// Vanilla TemperatureMaterialCondition is a parameterless singleton —
		// no expect_cold immediate exists to extract. Spec divergence noted.
		CompiledRuleTree t = SurfaceRuleCompiler.compile(new TemperatureMaterialCondition());
		assertFalse("hasFallback", t.hasFallback());
		assertBytecode("temperature bytes", t.bytecode(), withTerminator(RuleBytecode.OP_TEMPERATURE));
	}

	private static void opAboveYWithOperands() {
		Object node = new AboveYMaterialCondition(7, true);
		CompiledRuleTree t = SurfaceRuleCompiler.compile(node);
		assertFalse("hasFallback", t.hasFallback());
		// Layout: OP(1) + i32 anchorY(4) + i32 surfaceDepthMultiplier(4) + u8 addStoneDepth(1) = 10
		// Synthetic node has no anchor field → resolveYOffset returns 0.
		byte[] body = concat(new byte[]{RuleBytecode.OP_ABOVE_Y}, le32(0));
		body = concat(body, le32(7));
		body = concat(body, new byte[]{1});
		assertBytecode("above_y(7,true) bytes", t.bytecode(), withTerminator(body));
		assertEq("above_y total length (incl terminator)", 11, t.bytecode().length);
	}

	private static int readBlockIdAt(byte[] bytecode, int offset) {
		return (bytecode[offset] & 0xFF)
			| ((bytecode[offset + 1] & 0xFF) << 8)
			| ((bytecode[offset + 2] & 0xFF) << 16)
			| ((bytecode[offset + 3] & 0xFF) << 24);
	}

	private static void opBlockTableDedup() {
		FakeBlockState shared = new FakeBlockState("minecraft:stone");
		Object root = new SequenceMaterialRule(java.util.List.of(
			new BlockMaterialRule(shared),
			new BlockMaterialRule(shared),
			new BlockMaterialRule(shared)
		));
		CompiledRuleTree t = SurfaceRuleCompiler.compile(root);
		assertFalse("hasFallback", t.hasFallback());
		assertEq("table dedupes shared state", 1, t.blockstateTable().length);
	}

	private static void opBlockTableSorted() {
		FakeBlockState a = new FakeBlockState("z_last");
		FakeBlockState b = new FakeBlockState("a_first");
		FakeBlockState c = new FakeBlockState("m_middle");
		Object root = new SequenceMaterialRule(java.util.List.of(
			new BlockMaterialRule(a), // insertion 0
			new BlockMaterialRule(b), // insertion 1
			new BlockMaterialRule(c)  // insertion 2
		));
		CompiledRuleTree t = SurfaceRuleCompiler.compile(root);
		assertFalse("hasFallback", t.hasFallback());
		Object[] table = t.blockstateTable();
		assertEq("table size", 3, table.length);
		assertEq("[0] sorted=a_first", "a_first", table[0].toString());
		assertEq("[1] sorted=m_middle", "m_middle", table[1].toString());
		assertEq("[2] sorted=z_last", "z_last", table[2].toString());

		// Bytecode IDs must reference final (sorted) positions, not insertion order.
		// Layout per child group: OP_BLOCK(1) + u32(4) + OP_SEQUENCE_NEXT(1) + u32(4) = 10 bytes
		// 3 groups + OP_RETURN_DONE(1) = 31 bytes
		assertEq("bytecode length", 31, t.bytecode().length);
		// Block id u32 immediates at offsets 1, 11, 21
		assertEq("a → final id 2", 2, readBlockIdAt(t.bytecode(), 1));
		assertEq("b → final id 0", 0, readBlockIdAt(t.bytecode(), 11));
		assertEq("c → final id 1", 1, readBlockIdAt(t.bytecode(), 21));
	}

	private static void opBlockIdsDeterministic() {
		// Same logical tree compiled twice must produce identical
		// bytecode + identical IDs, even if a.toString() / b.toString()
		// would have produced different insertion orders.
		FakeBlockState a = new FakeBlockState("alpha");
		FakeBlockState b = new FakeBlockState("beta");
		Object t1 = new SequenceMaterialRule(java.util.List.of(
			new BlockMaterialRule(b), new BlockMaterialRule(a)));
		Object t2 = new SequenceMaterialRule(java.util.List.of(
			new BlockMaterialRule(a), new BlockMaterialRule(b)));
		CompiledRuleTree c1 = SurfaceRuleCompiler.compile(t1);
		CompiledRuleTree c2 = SurfaceRuleCompiler.compile(t2);
		// Tables should be in the same sorted order
		assertEq("c1 table[0]", "alpha", c1.blockstateTable()[0].toString());
		assertEq("c1 table[1]", "beta",  c1.blockstateTable()[1].toString());
		assertEq("c2 table[0]", "alpha", c2.blockstateTable()[0].toString());
		assertEq("c2 table[1]", "beta",  c2.blockstateTable()[1].toString());
		// In both, alpha→0, beta→1 regardless of source insertion order.
		// Layout per child group: OP_BLOCK(1) + u32(4) + OP_SEQUENCE_NEXT(1) + u32(4) = 10 bytes
		// First block id at offset 1, second at offset 11.
		assertEq("c1 first block id (beta)", 1, readBlockIdAt(c1.bytecode(), 1));
		assertEq("c1 second block id (alpha)", 0, readBlockIdAt(c1.bytecode(), 11));
		// c2 reverse
		assertEq("c2 first block id (alpha)", 0, readBlockIdAt(c2.bytecode(), 1));
		assertEq("c2 second block id (beta)", 1, readBlockIdAt(c2.bytecode(), 11));
	}

	private static int readU16At(byte[] bytecode, int offset) {
		return (bytecode[offset] & 0xFF) | ((bytecode[offset + 1] & 0xFF) << 8);
	}

	private static void opBiomePoolDedup() {
		// Three biome conditions referencing the same biome list
		// (different List instances, same content) should collapse to
		// one pool entry.
		Object root = new SequenceMaterialRule(java.util.List.of(
			new ConditionMaterialRule(
				new BiomeMaterialCondition(java.util.List.of("plains", "forest")),
				new BlockMaterialRule()),
			new ConditionMaterialRule(
				new BiomeMaterialCondition(java.util.List.of("plains", "forest")),
				new BlockMaterialRule()),
			new ConditionMaterialRule(
				new BiomeMaterialCondition(java.util.List.of("plains", "forest")),
				new BlockMaterialRule())
		));
		CompiledRuleTree t = SurfaceRuleCompiler.compile(root);
		assertFalse("hasFallback", t.hasFallback());
		assertEq("biome pool dedupes identical sets", 1, t.biomeSetTable().length);
	}

	private static void opBiomePoolContentEq() {
		// Same content, different element order — must still collapse.
		Object root = new SequenceMaterialRule(java.util.List.of(
			new ConditionMaterialRule(
				new BiomeMaterialCondition(java.util.List.of("plains", "forest")),
				new BlockMaterialRule()),
			new ConditionMaterialRule(
				new BiomeMaterialCondition(java.util.List.of("forest", "plains")),
				new BlockMaterialRule())
		));
		CompiledRuleTree t = SurfaceRuleCompiler.compile(root);
		assertFalse("hasFallback", t.hasFallback());
		assertEq("biome pool collapses on content (sorted canonical key)",
				1, t.biomeSetTable().length);
	}

	private static void opBiomePoolSorted() {
		// Three distinct biome sets — pool must be sorted by canonical key.
		Object root = new SequenceMaterialRule(java.util.List.of(
			new ConditionMaterialRule(
				new BiomeMaterialCondition(java.util.List.of("z_set")),
				new BlockMaterialRule()),
			new ConditionMaterialRule(
				new BiomeMaterialCondition(java.util.List.of("a_set")),
				new BlockMaterialRule()),
			new ConditionMaterialRule(
				new BiomeMaterialCondition(java.util.List.of("m_set")),
				new BlockMaterialRule())
		));
		CompiledRuleTree t = SurfaceRuleCompiler.compile(root);
		assertFalse("hasFallback", t.hasFallback());
		assertEq("pool size", 3, t.biomeSetTable().length);
		assertEq("[0] sorted", "a_set", t.biomeSetTable()[0].get(0));
		assertEq("[1] sorted", "m_set", t.biomeSetTable()[1].get(0));
		assertEq("[2] sorted", "z_set", t.biomeSetTable()[2].get(0));
		// Insertion order: z(0), a(1), m(2). After sort: a→0, m→1, z→2.
		// New layout per Condition: BIOME(1) + u16(2) + IF_ELSE(1) + then_off(4) +
		//   else_off(4) + BLOCK(1) + u32(4) = 17 bytes
		// Each Condition is one Sequence child, separated by SEQUENCE_NEXT(1) + u32(4) = 5 bytes
		// Per Sequence-child group: 17 + 5 = 22 bytes. Biome u16 at offsets 1, 23, 45.
		assertEq("z → final id 2", 2, readU16At(t.bytecode(), 1));
		assertEq("a → final id 0", 0, readU16At(t.bytecode(), 23));
		assertEq("m → final id 1", 1, readU16At(t.bytecode(), 45));
	}

	private static double readDoubleAt(byte[] bytecode, int offset) {
		long bits = 0;
		for (int i = 0; i < 8; i++) {
			bits |= ((long)(bytecode[offset + i] & 0xFF)) << (i * 8);
		}
		return Double.longBitsToDouble(bits);
	}

	private static void opNoiseThreshLayout() {
		// Layout: OP(1) + u16 channel id(2) + f64 min(8) + f64 max(8) = 19 bytes
		Object node = new NoiseThresholdMaterialCondition("test:channel_a", -0.5, 0.75);
		CompiledRuleTree t = SurfaceRuleCompiler.compile(node);
		assertFalse("hasFallback", t.hasFallback());
		assertEq("noise pool size", 1, t.noiseChannelTable().length);
		assertEq("noise channel name", "test:channel_a", t.noiseChannelTable()[0]);
		// 19 op-bytes + 1 trailing OP_RETURN_DONE = 20
		assertEq("bytecode length", 20, t.bytecode().length);
		assertEq("opcode", RuleBytecode.OP_NOISE_THRESH, t.bytecode()[0]);
		assertEq("channel id (only entry)", 0, readU16At(t.bytecode(), 1));
		if (readDoubleAt(t.bytecode(), 3) != -0.5) {
			throw new AssertionError("min threshold mismatch: " + readDoubleAt(t.bytecode(), 3));
		}
		if (readDoubleAt(t.bytecode(), 11) != 0.75) {
			throw new AssertionError("max threshold mismatch: " + readDoubleAt(t.bytecode(), 11));
		}
		assertEq("trailing terminator", RuleBytecode.OP_RETURN_DONE, t.bytecode()[19]);
	}

	private static void opNoisePoolDedup() {
		Object root = new SequenceMaterialRule(java.util.List.of(
			new NoiseThresholdMaterialCondition("shared", 0.0, 1.0),
			new NoiseThresholdMaterialCondition("shared", 0.5, 1.5),
			new NoiseThresholdMaterialCondition("shared", -1.0, 0.0)
		));
		CompiledRuleTree t = SurfaceRuleCompiler.compile(root);
		assertFalse("hasFallback", t.hasFallback());
		assertEq("noise pool dedupes shared channel name", 1, t.noiseChannelTable().length);
	}

	private static void opNoisePoolSorted() {
		Object root = new SequenceMaterialRule(java.util.List.of(
			new NoiseThresholdMaterialCondition("z_chan", 0, 1),
			new NoiseThresholdMaterialCondition("a_chan", 0, 1),
			new NoiseThresholdMaterialCondition("m_chan", 0, 1)
		));
		CompiledRuleTree t = SurfaceRuleCompiler.compile(root);
		assertFalse("hasFallback", t.hasFallback());
		assertEq("pool size", 3, t.noiseChannelTable().length);
		assertEq("[0]", "a_chan", t.noiseChannelTable()[0]);
		assertEq("[1]", "m_chan", t.noiseChannelTable()[1]);
		assertEq("[2]", "z_chan", t.noiseChannelTable()[2]);
		// New layout per child group: NoiseThresh(19) + SEQUENCE_NEXT(5) = 24 bytes
		// 3 groups + RETURN_DONE = 73 bytes total. Channel u16 at offsets 1, 25, 49.
		// Insertion: z(0), a(1), m(2). Sorted: a→0, m→1, z→2.
		assertEq("z → final 2", 2, readU16At(t.bytecode(), 1));
		assertEq("a → final 0", 0, readU16At(t.bytecode(), 25));
		assertEq("m → final 1", 1, readU16At(t.bytecode(), 49));
	}

	private static void opStoneDepthLayout() {
		// Layout: OP(1) + i32 offset(4) + u8 addSurfaceDepth(1) + i32 secondaryDepthRange(4) + u8 surfaceType(1) = 11 bytes
		Object node = new StoneDepthMaterialCondition(3, true, 7, FakeSurfaceType.CEILING);
		CompiledRuleTree t = SurfaceRuleCompiler.compile(node);
		assertFalse("hasFallback", t.hasFallback());
		// 11 op-bytes + 1 trailing OP_RETURN_DONE = 12
		assertEq("bytecode length", 12, t.bytecode().length);
		assertEq("opcode", RuleBytecode.OP_STONE_DEPTH, t.bytecode()[0]);
		assertEq("offset i32 le[0]", 3, t.bytecode()[1] & 0xFF);
		assertEq("addSurfaceDepth u8", 1, t.bytecode()[5] & 0xFF);
		assertEq("secondaryDepthRange i32 le[0]", 7, t.bytecode()[6] & 0xFF);
		assertEq("surfaceType ordinal (CEILING=1)", 1, t.bytecode()[10] & 0xFF);
		assertEq("trailing terminator", RuleBytecode.OP_RETURN_DONE, t.bytecode()[11]);
	}

	private static void opWaterLayout() {
		// Layout: OP(1) + i32 offset(4) + i32 mult(4) + u8 addStoneDepthBelow(1) = 10 bytes
		Object node = new WaterMaterialCondition(2, 5, true);
		CompiledRuleTree t = SurfaceRuleCompiler.compile(node);
		assertFalse("hasFallback", t.hasFallback());
		// 10 op-bytes + 1 trailing OP_RETURN_DONE = 11
		assertEq("bytecode length", 11, t.bytecode().length);
		assertEq("opcode", RuleBytecode.OP_WATER, t.bytecode()[0]);
		assertEq("offset i32 le[0]", 2, t.bytecode()[1] & 0xFF);
		assertEq("mult i32 le[0]", 5, t.bytecode()[5] & 0xFF);
		assertEq("addStoneDepthBelow u8", 1, t.bytecode()[9] & 0xFF);
		assertEq("trailing terminator", RuleBytecode.OP_RETURN_DONE, t.bytecode()[10]);
	}

	private static void opVertGradientFallback() {
		// VertGradient now emits real opcodes with per-block PRNG support
		// (random_name index for the validator's PositionalRandomFactory cache).
		// Synthetic node has no anchor fields → resolveYOffset returns 0,
		// no randomName field → readRandomName returns "" (interned at idx 0).
		// Layout: OP(1) + u16 randomNameIdx(2) + i32 trueAtAndBelow(4) +
		//         i32 falseAtAndAbove(4) + RETURN_DONE(1) = 12
		CompiledRuleTree t = SurfaceRuleCompiler.compile(new VerticalGradientMaterialCondition());
		assertFalse("hasFallback (no longer fallback)", t.hasFallback());
		assertEq("bytecode length", 12, t.bytecode().length);
		assertEq("opcode", RuleBytecode.OP_VERT_GRADIENT, t.bytecode()[0]);
		assertEq("randomNameTable length", 1, t.randomNameTable().length);
	}

	private static void opIfElseJumpTargets() {
		// Single ConditionMaterialRule(BiomeMaterialCondition, BlockMaterialRule)
		// Layout: BIOME(1) + u16(2) + IF_ELSE(1) + then_off(4) + else_off(4) +
		//         BLOCK(1) + u32(4) + RETURN_DONE(1) = 18 bytes
		// then_off should point to the start of BLOCK = byte 12
		// else_off should point to position right after BLOCK's u32 = byte 17
		Object node = new ConditionMaterialRule(
			new BiomeMaterialCondition(java.util.List.of("plains")),
			new BlockMaterialRule());
		CompiledRuleTree t = SurfaceRuleCompiler.compile(node);
		assertFalse("hasFallback", t.hasFallback());
		assertEq("bytecode length", 18, t.bytecode().length);
		assertEq("[3] OP_IF_ELSE", RuleBytecode.OP_IF_ELSE, t.bytecode()[3]);
		assertEq("then_offset → start of then-branch (BLOCK)", 12, readBlockIdAt(t.bytecode(), 4));
		assertEq("else_offset → past then-branch (terminator)", 17, readBlockIdAt(t.bytecode(), 8));
		assertEq("[12] OP_BLOCK (then-branch)", RuleBytecode.OP_BLOCK, t.bytecode()[12]);
		assertEq("[17] terminator", RuleBytecode.OP_RETURN_DONE, t.bytecode()[17]);
	}

	private static void opSequenceEndOffsets() {
		// Sequence of three OP_HOLE conditions.
		// Layout per child group: HOLE(1) + SEQUENCE_NEXT(1) + end_off(4) = 6 bytes
		// 3 groups + RETURN_DONE = 19 bytes total.
		// All three SEQUENCE_NEXT end_offsets should target position 18
		// (immediately past the last SEQUENCE_NEXT's offset = right at RETURN_DONE).
		Object node = new SequenceMaterialRule(java.util.List.of(
			new HoleMaterialCondition(),
			new HoleMaterialCondition(),
			new HoleMaterialCondition()));
		CompiledRuleTree t = SurfaceRuleCompiler.compile(node);
		assertFalse("hasFallback", t.hasFallback());
		assertEq("bytecode length", 19, t.bytecode().length);
		// SEQUENCE_NEXT opcodes at bytes 1, 7, 13; their end_off u32 at 2, 8, 14.
		assertEq("[1] OP_SEQUENCE_NEXT", RuleBytecode.OP_SEQUENCE_NEXT, t.bytecode()[1]);
		assertEq("[7] OP_SEQUENCE_NEXT", RuleBytecode.OP_SEQUENCE_NEXT, t.bytecode()[7]);
		assertEq("[13] OP_SEQUENCE_NEXT", RuleBytecode.OP_SEQUENCE_NEXT, t.bytecode()[13]);
		assertEq("end_off #1 → 18", 18, readBlockIdAt(t.bytecode(), 2));
		assertEq("end_off #2 → 18", 18, readBlockIdAt(t.bytecode(), 8));
		assertEq("end_off #3 → 18", 18, readBlockIdAt(t.bytecode(), 14));
		assertEq("[18] terminator", RuleBytecode.OP_RETURN_DONE, t.bytecode()[18]);
	}

	private static void opNotChild() {
		// OP_NOT followed by child opcode — the child's full bytecode
		// stream lives in-line. Hole has zero operands so total = 2 bytes.
		Object node = new NotMaterialCondition(new HoleMaterialCondition());
		CompiledRuleTree t = SurfaceRuleCompiler.compile(node);
		assertFalse("hasFallback", t.hasFallback());
		assertEq("bytecode length (NOT + HOLE + RETURN_DONE)", 3, t.bytecode().length);
		assertEq("[0] OP_NOT", RuleBytecode.OP_NOT, t.bytecode()[0]);
		assertEq("[1] OP_HOLE child", RuleBytecode.OP_HOLE, t.bytecode()[1]);
		assertEq("[2] terminator", RuleBytecode.OP_RETURN_DONE, t.bytecode()[2]);
	}

	private static void opAboveYDefaultOperands() {
		Object node = new AboveYMaterialCondition(); // 0, false
		CompiledRuleTree t = SurfaceRuleCompiler.compile(node);
		assertFalse("hasFallback", t.hasFallback());
		byte[] body = concat(new byte[]{RuleBytecode.OP_ABOVE_Y}, le32(0));
		body = concat(body, le32(0));
		body = concat(body, new byte[]{0});
		assertBytecode("above_y(0,false) bytes", t.bytecode(), withTerminator(body));
	}

	// --- synthetic node classes -------------------------------------------
	// Class simple-names must match vanilla SurfaceRules$Inner names so
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

	/**
	 * Synthetic stand-in for a vanilla BlockState. Lives in a non-surface
	 * package so the compiler's recursion gate skips it (sortKey reaches
	 * it via toString fallback since it has no getBlock()).
	 */
	static final class FakeBlockState {
		final String name;
		FakeBlockState(String name) { this.name = name; }
		@Override public String toString() { return name; }
	}

	@SuppressWarnings("unused")
	static final class BlockMaterialRule {
		final FakeBlockState state;
		BlockMaterialRule() { this(new FakeBlockState("default")); }
		BlockMaterialRule(FakeBlockState state) { this.state = state; }
	}

	@SuppressWarnings("unused")
	static final class SimpleBlockStateRule {
		final FakeBlockState state;
		SimpleBlockStateRule() { this(new FakeBlockState("default")); }
		SimpleBlockStateRule(FakeBlockState state) { this.state = state; }
	}

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

	static final class AboveYMaterialCondition {
		@SuppressWarnings("unused") final int surfaceDepthMultiplier;
		@SuppressWarnings("unused") final boolean addStoneDepthBelow;
		AboveYMaterialCondition() { this(0, false); }
		AboveYMaterialCondition(int surfaceDepthMultiplier, boolean addStoneDepthBelow) {
			this.surfaceDepthMultiplier = surfaceDepthMultiplier;
			this.addStoneDepthBelow = addStoneDepthBelow;
		}
	}
	@SuppressWarnings("unused")
	static final class NoiseThresholdMaterialCondition {
		final String noise;
		final double minThreshold;
		final double maxThreshold;
		NoiseThresholdMaterialCondition() { this("default", 0.0, 0.0); }
		NoiseThresholdMaterialCondition(String noise, double min, double max) {
			this.noise = noise;
			this.minThreshold = min;
			this.maxThreshold = max;
		}
	}
	static final class VerticalGradientMaterialCondition {}
	enum FakeSurfaceType { FLOOR, CEILING }

	@SuppressWarnings("unused")
	static final class StoneDepthMaterialCondition {
		final int offset;
		final boolean addSurfaceDepth;
		final int secondaryDepthRange;
		final FakeSurfaceType surfaceType;
		StoneDepthMaterialCondition() { this(0, false, 0, FakeSurfaceType.FLOOR); }
		StoneDepthMaterialCondition(int o, boolean a, int s, FakeSurfaceType t) {
			this.offset = o; this.addSurfaceDepth = a;
			this.secondaryDepthRange = s; this.surfaceType = t;
		}
	}

	@SuppressWarnings("unused")
	static final class WaterMaterialCondition {
		final int offset;
		final int surfaceDepthMultiplier;
		final boolean addStoneDepthBelow;
		WaterMaterialCondition() { this(0, 0, false); }
		WaterMaterialCondition(int o, int m, boolean a) {
			this.offset = o; this.surfaceDepthMultiplier = m;
			this.addStoneDepthBelow = a;
		}
	}
	static final class HoleMaterialCondition {}
	static final class SurfaceMaterialCondition {}
	@SuppressWarnings("unused")
	static final class BiomeMaterialCondition {
		final java.util.List<String> biomes;
		BiomeMaterialCondition() { this(java.util.List.of()); }
		BiomeMaterialCondition(java.util.List<String> biomes) { this.biomes = biomes; }
	}
	static final class TemperatureMaterialCondition {}
	static final class SteepMaterialCondition {}

	static final class NotMaterialCondition {
		final Object child;
		NotMaterialCondition(Object child) { this.child = child; }
	}

	static final class TotallyMadeUpRule {}
}
