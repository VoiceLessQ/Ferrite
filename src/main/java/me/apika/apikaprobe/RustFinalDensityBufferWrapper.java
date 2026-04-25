package me.apika.apikaprobe;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.DoubleBuffer;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicLong;

import net.minecraft.util.dynamic.CodecHolder;
import net.minecraft.world.gen.densityfunction.DensityFunction;

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
public final class RustFinalDensityBufferWrapper implements DensityFunction.Base {
	public static volatile boolean ENABLED = false;

	public static final AtomicLong sampleCalls = new AtomicLong();
	public static final AtomicLong bufferHits = new AtomicLong();
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

	@Override
	public double sample(NoisePos pos) {
		sampleCalls.incrementAndGet();
		double[] buf = ensureBuffer();
		if (buf == null) {
			fallbacks.incrementAndGet();
			return original.sample(pos);
		}

		int blockX = pos.blockX();
		int blockY = pos.blockY();
		int blockZ = pos.blockZ();
		int relX = blockX - chunkMinBlockX;
		int relY = blockY - MIN_BLOCK_Y;
		int relZ = blockZ - chunkMinBlockZ;

		if (relX < 0 || relX >= CHUNK
				|| relZ < 0 || relZ >= CHUNK
				|| relY < 0 || relY >= HEIGHT) {
			fallbacks.incrementAndGet();
			return original.sample(pos);
		}

		bufferHits.incrementAndGet();
		// Layout matches Rust JNI: row-major (by, bz, bx).
		return buf[(relY * CHUNK + relZ) * CHUNK + relX];
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
	public DensityFunction apply(DensityFunctionVisitor visitor) {
		return visitor.apply(this);
	}

	@Override
	public double minValue() { return original.minValue(); }

	@Override
	public double maxValue() { return original.maxValue(); }

	@Override
	public CodecHolder<? extends DensityFunction> getCodecHolder() {
		throw new UnsupportedOperationException(
				"RustFinalDensityBufferWrapper is runtime-only");
	}

	public static String diagSummary() {
		long calls = sampleCalls.get();
		long jni = bulkJniCalls.get();
		long jniNs = bulkJniTotalNs.get();
		long wrappers = wrapperConstructCount.get();
		long hits = bufferHits.get();
		long fbs = fallbacks.get();

		double jniAvgMs = jni == 0 ? 0 : (double) jniNs / jni / 1_000_000.0;
		double callsPerChunk = wrappers == 0 ? 0 : (double) calls / wrappers;

		return String.format(
				"[noise-buffer diag] enabled=%s wrappers=%d calls=%d (%.0f/chunk) "
						+ "hits=%d fallbacks=%d jni=%d (%.2fms avg)",
				ENABLED, wrappers, calls, callsPerChunk, hits, fbs, jni, jniAvgMs);
	}

	public static void resetDiag() {
		sampleCalls.set(0);
		bufferHits.set(0);
		fallbacks.set(0);
		bulkJniCalls.set(0);
		bulkJniTotalNs.set(0);
		wrapperConstructCount.set(0);
	}
}
