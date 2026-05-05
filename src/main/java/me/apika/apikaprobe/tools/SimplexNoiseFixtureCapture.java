package me.apika.apikaprobe.tools;

import net.minecraft.world.level.levelgen.LegacyRandomSource;
import net.minecraft.world.level.levelgen.synth.SimplexNoise;

/**
 * Captures bit-exact reference values from vanilla 26.1.2's
 * {@link SimplexNoise} so the existing Rust port at
 * {@code crate::perlin::SimplexNoise} can be parity-tested against
 * vanilla output.
 *
 * <p>Output format: ready-to-paste Rust constants for the test
 * fixtures in {@code rust/mod/src/perlin.rs::tests}.
 *
 * <h3>How to run</h3>
 * <pre>{@code ./gradlew captureSimplexNoiseFixtures}</pre>
 *
 * <h3>Methodology</h3>
 *
 * <p>EndIsland's seeding is mirrored exactly: {@code new
 * LegacyRandomSource(0)}, {@code consumeCount(17292)}, then
 * {@code new SimplexNoise(random)}. The captured 2D outputs come
 * from that one shared noise instance.
 *
 * <p>Only 2D capture: vanilla also has {@code getValue(x, y, z)} but
 * the Rust port at {@code crate::perlin::SimplexNoise} currently
 * exposes only the 2D variant (which is what EndIsland calls).
 * Add 3D capture when/if the 3D path lands.
 */
public final class SimplexNoiseFixtureCapture {

	private SimplexNoiseFixtureCapture() {}

	private static final double[][] POINTS_2D = {
			{1.0, 1.0},
			{100.5, -200.3},
			{-1234.5, 6789.0},
			{0.5, 0.5},
			{12.34, -56.78},
	};

	public static void main(String[] args) {
		// EndIsland's seeding: new LegacyRandomSource(seed), consumeCount(17292),
		// new SimplexNoise(random).
		LegacyRandomSource rng = new LegacyRandomSource(0L);
		rng.consumeCount(17292);
		SimplexNoise noise = new SimplexNoise(rng);

		System.out.println("=== BEGIN FIXTURES ===");
		System.out.println();
		captureGetValue2D(noise);
		System.out.println();
		System.out.println("=== END FIXTURES ===");
	}

	private static void captureGetValue2D(SimplexNoise noise) {
		System.out.println("const FIXTURE_GET_VALUE_2D: [(f64, f64, u64); " + POINTS_2D.length + "] = [");
		for (double[] p : POINTS_2D) {
			double v = noise.getValue(p[0], p[1]);
			// Inputs printed via f64::from_bits so they round-trip bit-for-bit
			// through Rust const evaluation. Outputs as raw u64 bits for
			// to_bits() comparison on the Rust side.
			System.out.printf("    (f64::from_bits(0x%016xu64), f64::from_bits(0x%016xu64), 0x%016xu64), // %a%n",
					Double.doubleToLongBits(p[0]),
					Double.doubleToLongBits(p[1]),
					Double.doubleToLongBits(v),
					v);
		}
		System.out.println("];");
	}
}
