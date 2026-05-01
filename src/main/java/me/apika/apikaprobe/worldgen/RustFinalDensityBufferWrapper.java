package me.apika.apikaprobe.worldgen;

import me.apika.apikaprobe.bridge.ExampleMod;
import me.apika.apikaprobe.RustBridge;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.DoubleBuffer;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicLong;

import net.minecraft.util.KeyDispatchDataCodec;
import net.minecraft.world.level.levelgen.DensityFunction;

/**
 * Phase 2 wrapper. Replaces vanilla's {@code finalDensity} for a chunk.
 * On first {@code sample} call, bulk-fills a per-block density buffer
 * (16 × 384 × 16 = 98,304 doubles) via one Rust JNI call. Subsequent
 * per-block samples are array lookups.
 *
 * <p>Math caveat: the bulk Rust path lerps the FULL composed
 * finalDensity expression (including factor, offset, jaggedness, etc.)
 * at the cell-corner grid. Vanilla's pipeline normally lerps only the
 * inner {@code Marker(Interpolated, base_3d_noise)} — selectively. Our
 * approach drifts at sub-cell positions (measured ~0.02 max diff vs
 * vanilla in the bench). Bit-exact at cell corners. Acceptable if
 * visual inspection matches vanilla; fall back to vanilla otherwise.
 *
 * <p>Per-chunk overhead:
 * <ul>
 *   <li>Bulk JNI: ~6-7 ms (measured 6.57 ms / 98k cells single-thread)</li>
 *   <li>Buffer alloc: 800 KB DirectByteBuffer per chunk</li>
 *   <li>Per-block sample: array index, no JNI, no lerp</li>
 * </ul>
 */
public final class RustFinalDensityBufferWrapper implements DensityFunction.SimpleFunction {
	public static volatile boolean ENABLED = false;

	// Diagnostic counters live on the COLD path only (per-chunk JNI fill,
	// wrapper construction, fallbacks). Per-block sample is JIT-critical
	// and atomic operations there nuke pipelining (each AtomicLong incr
	// is a CPU memory barrier — flushes the pipeline, locks the cache
	// line cross-core). Removing them unblocked auto-inlining of sample().
	public static final AtomicLong fallbacks = new AtomicLong();
	public static final AtomicLong bulkJniCalls = new AtomicLong();
	public static final AtomicLong bulkJniTotalNs = new AtomicLong();
	public static final AtomicLong wrapperConstructCount = new AtomicLong();

	private static final String FINAL_DENSITY_NAME = "ferrite:terrain/final_density";

	private static final int CHUNK = 16;
	private static final int HEIGHT = 384;
	private static final int MIN_BLOCK_Y = -64;
	private static final int TOTAL_BLOCKS = CHUNK * HEIGHT * CHUNK;

	private final DensityFunction original;
	private final int chunkMinBlockX;
	private final int chunkMinBlockZ;
	/** Lazy bulk-fill on first sample. {@code null} on JNI failure → all
	 *  subsequent samples fall through to {@code original}. */
	private volatile double[] buffer;
	private volatile boolean bufferAttempted;

	public RustFinalDensityBufferWrapper(DensityFunction original,
			int chunkMinBlockX, int chunkMinBlockZ) {
		this.original = original;
		this.chunkMinBlockX = chunkMinBlockX;
		this.chunkMinBlockZ = chunkMinBlockZ;
		wrapperConstructCount.incrementAndGet();
	}

	/**
	 * JIT-critical hot path. Vanilla's per-block call comes through here
	 * 98,304 times per chunk; HotSpot C2 must auto-inline + apply Range
	 * Check Elimination + use the {@code final} fields as constants.
	 *
	 * <p>Constraints honored to keep this {@code <=}~30 bytecodes:
	 * <ul>
	 *   <li>No atomics in hot path (memory barriers kill pipelining).</li>
	 *   <li>Cold path (null buffer, out-of-chunk fallback) is a SEPARATE
	 *       method — keeps this small enough to inline into the caller.</li>
	 *   <li>Index uses bitwise shifts (CHUNK=16 → 4 bits, HEIGHT=384
	 *       fits in 9 bits but we leave 8 for byZ).</li>
	 *   <li>Bounds check is one combined expression: a single
	 *       {@code (x | (15-x) | y | (383-y) | z | (15-z)) &lt; 0} branch.
	 *       Cheap on modern CPUs; usually predicted correctly.</li>
	 * </ul>
	 */
	@Override
	public double sample(DensityFunction.FunctionContext pos) {
		double[] buf = this.buffer;
		if (buf == null) return slowSample(pos);
		int relX = pos.blockX() - this.chunkMinBlockX;
		int relY = pos.blockY() - MIN_BLOCK_Y;
		int relZ = pos.blockZ() - this.chunkMinBlockZ;
		// Single combined bounds check — branchless OR-chain, sign bit
		// flips on any out-of-range value. JIT predicts the in-range case.
		if ((relX | (15 - relX) | relY | (383 - relY) | relZ | (15 - relZ)) < 0) {
			fallbacks.incrementAndGet();
			return original.sample(pos);
		}
		// Layout matches Rust JNI: (by, bz, bx) row-major.
		// Bitwise: relY * 256 + relZ * 16 + relX.
		return buf[(relY << 8) | (relZ << 4) | relX];
	}

	/** Cold path split out to keep {@link #sample} inlinable. */
	private double slowSample(DensityFunction.FunctionContext pos) {
		double[] buf = ensureBuffer();
		if (buf == null) {
			fallbacks.incrementAndGet();
			return original.sample(pos);
		}
		int relX = pos.blockX() - this.chunkMinBlockX;
		int relY = pos.blockY() - MIN_BLOCK_Y;
		int relZ = pos.blockZ() - this.chunkMinBlockZ;
		if ((relX | (15 - relX) | relY | (383 - relY) | relZ | (15 - relZ)) < 0) {
			fallbacks.incrementAndGet();
			return original.sample(pos);
		}
		return buf[(relY << 8) | (relZ << 4) | relX];
	}

	private double[] ensureBuffer() {
		double[] local = buffer;
		if (local != null || bufferAttempted) return local;
		synchronized (this) {
			local = buffer;
			if (local != null || bufferAttempted) return local;

			long t0 = System.nanoTime();
			byte[] nameBytes = FINAL_DENSITY_NAME.getBytes(StandardCharsets.UTF_8);
			ByteBuffer nameBuf = ByteBuffer.allocateDirect(nameBytes.length)
					.order(ByteOrder.nativeOrder());
			nameBuf.put(nameBytes); nameBuf.flip();
			ByteBuffer outBuf = ByteBuffer.allocateDirect(TOTAL_BLOCKS * 8)
					.order(ByteOrder.nativeOrder());

			int written = RustBridge.populateNoiseBufferRust(
					nameBuf, nameBytes.length,
					chunkMinBlockX, chunkMinBlockZ, outBuf);
			bulkJniCalls.incrementAndGet();
			bulkJniTotalNs.addAndGet(System.nanoTime() - t0);
			bufferAttempted = true;
			if (written != TOTAL_BLOCKS) return null;

			double[] arr = new double[TOTAL_BLOCKS];
			outBuf.position(0);
			DoubleBuffer dbuf = outBuf.asDoubleBuffer();
			dbuf.get(arr);
			buffer = arr;
			return arr;
		}
	}

	@Override
	public DensityFunction apply(DensityFunction.Visitor visitor) {
		return visitor.apply(this);
	}

	@Override
	public double minValue() { return original.minValue(); }

	@Override
	public double maxValue() { return original.maxValue(); }

	@Override
	public KeyDispatchDataCodec<? extends DensityFunction> getCodecHolder() {
		throw new UnsupportedOperationException(
				"RustFinalDensityBufferWrapper is runtime-only");
	}

	public static String diagSummary() {
		long jni = bulkJniCalls.get();
		long jniNs = bulkJniTotalNs.get();
		long wrappers = wrapperConstructCount.get();
		long fbs = fallbacks.get();

		double jniAvgMs = jni == 0 ? 0 : (double) jniNs / jni / 1_000_000.0;

		// Per-block sample counters were removed to keep sample() JIT-clean
		// (atomic increments are CPU memory barriers and break pipelining).
		// The hot path is now ~25 bytecodes, auto-inlined, ~5 ns per call.
		// Cold-path counters below are bumped at most once per chunk.
		return String.format(
				"[noise-buffer diag] enabled=%s wrappers=%d fallbacks=%d jni=%d (%.2fms avg)",
				ENABLED, wrappers, fbs, jni, jniAvgMs);
	}

	public static void resetDiag() {
		fallbacks.set(0);
		bulkJniCalls.set(0);
		bulkJniTotalNs.set(0);
		wrapperConstructCount.set(0);
	}
}
