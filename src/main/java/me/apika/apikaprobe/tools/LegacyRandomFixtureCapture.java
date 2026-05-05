package me.apika.apikaprobe.tools;

import net.minecraft.world.level.levelgen.LegacyRandomSource;

/**
 * Captures bit-exact reference values from vanilla 26.1.2's
 * {@link LegacyRandomSource} so the existing Rust port at
 * {@code crate::xoroshiro::LegacyRandomSource} can be parity-tested
 * against vanilla output.
 *
 * <p>Output format: ready-to-paste Rust constants for the test
 * fixtures in {@code rust/mod/src/xoroshiro.rs::tests}.
 *
 * <h3>How to run</h3>
 * <pre>{@code ./gradlew captureLegacyRandomFixtures}</pre>
 *
 * <h3>Why this exists</h3>
 *
 * <p>Pure-clean-room translation: the Rust impl is hand-ported from
 * vanilla source, but the fixture values come from running vanilla
 * itself. If the two ever diverge, the parity tests fail and we
 * know exactly where (next32, nextDouble, or the Fisher-Yates
 * shuffle) to look. Fixtures don't drift between MC versions
 * unless Mojang changes the LCG constants, which they haven't.
 *
 * <p>The capturer reads vanilla classes via the dev-environment's
 * Loom classpath. It writes nothing to disk and changes no game state.
 */
public final class LegacyRandomFixtureCapture {

	private LegacyRandomFixtureCapture() {}

	public static void main(String[] args) {
		System.out.println("=== BEGIN FIXTURES ===");
		System.out.println();

		captureFirst5Next32();
		System.out.println();
		captureFirst5NextDouble();
		System.out.println();
		captureEndIslandPermutation();

		System.out.println();
		System.out.println("=== END FIXTURES ===");
	}

	/**
	 * First 5 raw {@code next(32)} outputs after {@code LegacyRandomSource(0)}.
	 * {@code next(int)} is package-private in vanilla; reflection accesses it
	 * to keep this tool independent of access-widener entries.
	 */
	private static void captureFirst5Next32() {
		LegacyRandomSource r = new LegacyRandomSource(0L);
		System.out.println("const FIXTURE_SEED0_NEXT32: [i32; 5] = [");
		for (int i = 0; i < 5; i++) {
			int v = invokeNext(r, 32);
			System.out.printf("    %d,%n", v);
		}
		System.out.println("];");
	}

	/** First 5 {@code nextDouble()} outputs after {@code LegacyRandomSource(0)}. */
	private static void captureFirst5NextDouble() {
		LegacyRandomSource r = new LegacyRandomSource(0L);
		System.out.println("const FIXTURE_SEED0_NEXTDOUBLE: [f64; 5] = [");
		for (int i = 0; i < 5; i++) {
			double d = r.nextDouble();
			System.out.printf("    f64::from_bits(0x%016x), // %a%n",
					Double.doubleToLongBits(d), d);
		}
		System.out.println("];");
	}

	/**
	 * 256-entry permutation array after the full EndIsland seeding sequence:
	 * {@code consumeCount(17292) + 3 nextDouble() + 256 nextInt(256-i) Fisher-Yates}.
	 * This is the strongest end-to-end check — it exercises every method on
	 * the LCG path, including {@code nextInt(bound)} with every non-power-of-2
	 * bound from 256 down to 1.
	 */
	private static void captureEndIslandPermutation() {
		LegacyRandomSource r = new LegacyRandomSource(0L);
		r.consumeCount(17292);
		// Mirror SimplexNoise constructor: 3 nextDoubles for xo/yo/zo, then
		// the Fisher-Yates shuffle of the 256-entry permutation.
		r.nextDouble();
		r.nextDouble();
		r.nextDouble();

		int[] p = new int[256];
		for (int i = 0; i < 256; i++) p[i] = i;
		for (int i = 0; i < 256; i++) {
			int offset = r.nextInt(256 - i);
			int tmp = p[i];
			p[i] = p[offset + i];
			p[offset + i] = tmp;
		}

		System.out.println("const FIXTURE_SEED0_ENDISLAND_PERMUTATION: [i32; 256] = [");
		for (int row = 0; row < 256; row += 16) {
			StringBuilder sb = new StringBuilder("    ");
			for (int col = 0; col < 16; col++) {
				sb.append(String.format("%3d, ", p[row + col]));
			}
			System.out.println(sb.toString().stripTrailing());
		}
		System.out.println("];");
	}

	// ------------------------------------------------------------------
	// Reflection helper for package-private next(int)
	// ------------------------------------------------------------------

	private static int invokeNext(LegacyRandomSource r, int bits) {
		try {
			java.lang.reflect.Method m =
					LegacyRandomSource.class.getDeclaredMethod("next", int.class);
			m.setAccessible(true);
			return (int) m.invoke(r, bits);
		} catch (ReflectiveOperationException e) {
			throw new RuntimeException("LegacyRandomSource.next(int) not accessible: "
					+ e.getMessage(), e);
		}
	}
}
