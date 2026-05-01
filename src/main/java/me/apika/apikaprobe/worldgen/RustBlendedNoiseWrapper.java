package me.apika.apikaprobe.worldgen;

import me.apika.apikaprobe.bridge.ExampleMod;
import me.apika.apikaprobe.RustBridge;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.DoubleBuffer;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicLong;

import net.minecraft.util.CodecHolder;
import net.minecraft.world.level.levelgen.DensityFunction;

/**
 * Surgical Rust-backed leaf replacement for vanilla's {@code BlendedNoise}
 * (yarn {@code InterpolatedNoiseSampler}). Used inside vanilla's
 * {@code NoiseInterpolator(our_wrapper)} — vanilla samples us at
 * cell-corner block positions (cellWidth=4 X/Z, cellHeight=8 Y) and does
 * its own per-block trilinear lerp from those corners.
 *
 * <p>Our job is just to serve corner values bit-exact at vanilla speed
 * (memory hit). Pre-bulk-samples 5×97×5 = 2,425 corners on first
 * {@code sample} call via Rust's {@code sampleDensityRegion3DRust}
 * (registered as {@code minecraft:overworld/base_3d_noise} et al — the
 * BlendedNoise components vanilla composes inside finalDensity).
 *
 * <p>Vanilla's wrap chain keeps {@code Marker(Interpolated)} →
 * {@code NoiseInterpolator}, but the wrapped DF inside is our cheap
 * leaf instead of vanilla's expensive BlendedNoise. Vanilla's outer
 * composition (factor × offset + jaggedness × ...) runs unchanged at
 * vanilla speed.
 *
 * <p>Match the BlendedNoise's scale parameters against the three known
 * registered names (overworld / nether / end) — the per-dimension scale
 * tuple in {@code NoiseRouterData.bootstrap} is unique. Unknown
 * combinations fall through to original.sample (e.g., a future custom
 * datapack dimension).
 */
public final class RustBlendedNoiseWrapper implements DensityFunction.Base {
	public static volatile boolean ENABLED = false;

	// Diagnostic counters live on the COLD path only (per-chunk JNI fill,
	// wrapper construction, fallbacks). Per-block sample is JIT-critical
	// AND multi-thread-hot under vanilla's parallel chunkgen worker pool;
	// hot-path AtomicLong incrs become CPU memory barriers AND cause
	// cache-line ping-pong across Worker-Main-N cores. Same fix template
	// as RustFinalDensityBufferWrapper. See docs/PARALLELISM_AUDIT.md.
	public static final AtomicLong bulkJniCalls = new AtomicLong();
	public static final AtomicLong bulkJniTotalNs = new AtomicLong();
	public static final AtomicLong fallbacks = new AtomicLong();
	public static final AtomicLong wrapperConstructCount = new AtomicLong();

	private static final int SIDE_X = 5;
	private static final int SIDE_Y = 97;
	private static final int SIDE_Z = 5;
	private static final int STEP = 4;
	private static final int TOTAL_CELLS = SIDE_X * SIDE_Y * SIDE_Z;
	private static final int ORIGIN_Y = -64;
	private static final int MIN_QY = -16;

	private final DensityFunction original;
	private final String registeredName; // null if no match → always fall back
	private final int chunkMinBlockX;
	private final int chunkMinBlockZ;
	private volatile double[] cornerCache;

	public RustBlendedNoiseWrapper(DensityFunction original, String registeredName,
			int chunkMinBlockX, int chunkMinBlockZ) {
		this.original = original;
		this.registeredName = registeredName;
		this.chunkMinBlockX = chunkMinBlockX;
		this.chunkMinBlockZ = chunkMinBlockZ;
		wrapperConstructCount.incrementAndGet();
	}

	/** Match the BlendedNoise's 5 scale params against the three known
	 *  vanilla registry names. Returns null if no match (e.g. custom
	 *  datapack BlendedNoise) → caller should fall through to the
	 *  original DF rather than constructing our wrapper. */
	public static String identifyDimension(double xzScale, double yScale,
			double xzFactor, double yFactor, double smear) {
		if (xzScale == 0.25 && yScale == 0.125 && xzFactor == 80.0 && yFactor == 160.0 && smear == 8.0)
			return "minecraft:overworld/base_3d_noise";
		if (xzScale == 0.25 && yScale == 0.375 && xzFactor == 80.0 && yFactor == 60.0 && smear == 8.0)
			return "minecraft:nether/base_3d_noise";
		if (xzScale == 0.25 && yScale == 0.25 && xzFactor == 80.0 && yFactor == 160.0 && smear == 4.0)
			return "minecraft:end/base_3d_noise";
		return null;
	}

	@Override
	public double sample(NoisePos pos) {
		int blockX = pos.blockX();
		int blockY = pos.blockY();
		int blockZ = pos.blockZ();

		if (registeredName == null) {
			fallbacks.incrementAndGet();
			return original.sample(pos);
		}

		double[] cache = ensureCache();
		if (cache == null) {
			fallbacks.incrementAndGet();
			return original.sample(pos);
		}

		int relX = blockX - chunkMinBlockX;
		int relZ = blockZ - chunkMinBlockZ;
		int relY = blockY - ORIGIN_Y;
		int qy = blockY >> 2;

		// Trilinear lerp from quart-resolution cache. Vanilla calls us
		// only at cell corners (cellWidth=4, cellHeight=8) → fx/fy/fz
		// are normally 0; lerp degenerates to direct cache lookup. The
		// lerp branch handles edge cases where vanilla samples between
		// our cache points (e.g., aquifer might query at non-corner Y).
		int qxLow = relX >> 2;
		int qzLow = relZ >> 2;
		int qyIdxLow = qy - MIN_QY;

		if (relX < 0 || qxLow >= SIDE_X - 1
				|| relZ < 0 || qzLow >= SIDE_Z - 1
				|| qyIdxLow < 0 || qyIdxLow >= SIDE_Y - 1) {
			fallbacks.incrementAndGet();
			return original.sample(pos);
		}

		double fx = (relX & 3) / 4.0;
		double fy = (relY & 3) / 4.0;
		double fz = (relZ & 3) / 4.0;

		double v000 = at(cache, qxLow,     qyIdxLow,     qzLow    );
		double v100 = at(cache, qxLow + 1, qyIdxLow,     qzLow    );
		double v010 = at(cache, qxLow,     qyIdxLow + 1, qzLow    );
		double v110 = at(cache, qxLow + 1, qyIdxLow + 1, qzLow    );
		double v001 = at(cache, qxLow,     qyIdxLow,     qzLow + 1);
		double v101 = at(cache, qxLow + 1, qyIdxLow,     qzLow + 1);
		double v011 = at(cache, qxLow,     qyIdxLow + 1, qzLow + 1);
		double v111 = at(cache, qxLow + 1, qyIdxLow + 1, qzLow + 1);

		double l00 = v000 + fx * (v100 - v000);
		double l10 = v010 + fx * (v110 - v010);
		double l01 = v001 + fx * (v101 - v001);
		double l11 = v011 + fx * (v111 - v011);
		double l0 = l00 + fy * (l10 - l00);
		double l1 = l01 + fy * (l11 - l01);
		return l0 + fz * (l1 - l0);
	}

	private static double at(double[] cache, int qx, int qyIdx, int qz) {
		return cache[(qyIdx * SIDE_Z + qz) * SIDE_X + qx];
	}

	private double[] ensureCache() {
		double[] local = cornerCache;
		if (local != null) return local;
		synchronized (this) {
			local = cornerCache;
			if (local != null) return local;

			long t0 = System.nanoTime();
			byte[] nameBytes = registeredName.getBytes(StandardCharsets.UTF_8);
			ByteBuffer nameBuf = ByteBuffer.allocateDirect(nameBytes.length)
					.order(ByteOrder.nativeOrder());
			nameBuf.put(nameBytes);
			nameBuf.flip();
			ByteBuffer outBuf = ByteBuffer.allocateDirect(TOTAL_CELLS * 8)
					.order(ByteOrder.nativeOrder());

			int written = RustBridge.sampleDensityRegion3DRust(
					nameBuf, nameBytes.length,
					chunkMinBlockX, ORIGIN_Y, chunkMinBlockZ,
					SIDE_X, SIDE_Y, SIDE_Z, STEP, outBuf);
			bulkJniCalls.incrementAndGet();
			bulkJniTotalNs.addAndGet(System.nanoTime() - t0);
			if (written != TOTAL_CELLS) return null;

			double[] cache = new double[TOTAL_CELLS];
			outBuf.position(0);
			DoubleBuffer dbuf = outBuf.asDoubleBuffer();
			dbuf.get(cache);
			cornerCache = cache;
			return cache;
		}
	}

	@Override
	public DensityFunction apply(DensityFunctionVisitor visitor) {
		return visitor.apply(this);
	}

	@Override
	public double minValue() {
		return original.minValue();
	}

	@Override
	public double maxValue() {
		return original.maxValue();
	}

	@Override
	public CodecHolder<? extends DensityFunction> getCodecHolder() {
		throw new UnsupportedOperationException(
				"RustBlendedNoiseWrapper is runtime-only and cannot be serialized");
	}

	public static String diagSummary() {
		long jni = bulkJniCalls.get();
		long jniNs = bulkJniTotalNs.get();
		long wrappers = wrapperConstructCount.get();
		long fbs = fallbacks.get();

		double jniAvgMs = jni == 0 ? 0 : (double) jniNs / jni / 1_000_000.0;

		return String.format(
				"[blended-rust diag] enabled=%s wrappers=%d fallbacks=%d jni=%d (%.2fms avg)",
				ENABLED, wrappers, fbs, jni, jniAvgMs);
	}

	public static void resetDiag() {
		bulkJniCalls.set(0);
		bulkJniTotalNs.set(0);
		fallbacks.set(0);
		wrapperConstructCount.set(0);
	}
}
