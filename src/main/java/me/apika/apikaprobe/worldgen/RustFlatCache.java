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
 * Phase 2.5: drop-in replacement for vanilla's {@code NoiseChunk.FlatCache}.
 * Vanilla's wrap chain substitutes {@code Marker(FlatCache, X)} with a
 * {@code FlatCache} instance that lazily fills a 5×5 quart-grid of
 * X-values per chunk. Our version IS that cache — populated in bulk by
 * Rust on first sample, then served from memory at vanilla speed.
 *
 * <p>Layout: 5 × 5 = 25 doubles indexed by quart-grid {@code (qx, qz)}
 * relative to the chunk. Y-invariant (FlatCache only varies in X/Z).
 *
 * <p>Per chunk: one bulk JNI call (sampleDensityRegion3DRust with
 * sideY=1) → 25 corner samples. Per sample: one array index lookup.
 */
public final class RustFlatCache implements DensityFunction.Base {
	// Diagnostic counters live on the COLD path only. Per-block sample is
	// JIT-critical AND multi-thread-hot under vanilla's parallel chunkgen
	// worker pool — hot-path AtomicLong incrs cause cache-line ping-pong
	// across Worker-Main-N cores. See docs/PARALLELISM_AUDIT.md.
	public static final AtomicLong wrapperConstructCount = new AtomicLong();
	public static final AtomicLong fallbacks = new AtomicLong();
	public static final AtomicLong bulkJniCalls = new AtomicLong();
	public static final AtomicLong bulkJniTotalNs = new AtomicLong();

	private static final int SIDE = 5;
	private static final int TOTAL = SIDE * SIDE;

	private final DensityFunction original;
	private final String registeredName;
	private final int chunkMinBlockX;
	private final int chunkMinBlockZ;
	private volatile double[] cache;
	private volatile boolean attempted;

	public RustFlatCache(DensityFunction original, String registeredName,
			int chunkMinBlockX, int chunkMinBlockZ) {
		this.original = original;
		this.registeredName = registeredName;
		this.chunkMinBlockX = chunkMinBlockX;
		this.chunkMinBlockZ = chunkMinBlockZ;
		wrapperConstructCount.incrementAndGet();
	}

	@Override
	public double sample(NoisePos pos) {
		double[] c = ensureCache();
		if (c == null) {
			fallbacks.incrementAndGet();
			return original.sample(pos);
		}
		// Vanilla FlatCache uses quart-grid: qx = (blockX >> 2) - chunkQuartMinX
		int qx = (pos.blockX() >> 2) - (chunkMinBlockX >> 2);
		int qz = (pos.blockZ() >> 2) - (chunkMinBlockZ >> 2);
		if (qx < 0 || qx >= SIDE || qz < 0 || qz >= SIDE) {
			fallbacks.incrementAndGet();
			return original.sample(pos);
		}
		return c[qz * SIDE + qx];
	}

	private double[] ensureCache() {
		double[] local = cache;
		if (local != null || attempted) return local;
		synchronized (this) {
			local = cache;
			if (local != null || attempted) return local;

			long t0 = System.nanoTime();
			byte[] nameBytes = registeredName.getBytes(StandardCharsets.UTF_8);
			ByteBuffer nameBuf = ByteBuffer.allocateDirect(nameBytes.length)
					.order(ByteOrder.nativeOrder());
			nameBuf.put(nameBytes); nameBuf.flip();
			ByteBuffer outBuf = ByteBuffer.allocateDirect(TOTAL * 8)
					.order(ByteOrder.nativeOrder());

			// FlatCache is Y-invariant: sample at any one Y level.
			// Use Y=0 as a stable choice. Step=4 for quart grid.
			int written = RustBridge.sampleDensityRegion3DRust(
					nameBuf, nameBytes.length,
					chunkMinBlockX, 0, chunkMinBlockZ,
					SIDE, 1, SIDE, 4, outBuf);
			bulkJniCalls.incrementAndGet();
			bulkJniTotalNs.addAndGet(System.nanoTime() - t0);
			attempted = true;
			if (written != TOTAL) return null;

			double[] arr = new double[TOTAL];
			outBuf.position(0);
			DoubleBuffer dbuf = outBuf.asDoubleBuffer();
			dbuf.get(arr);
			cache = arr;
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
				"RustFlatCache is runtime-only");
	}

	public static String diagSummary() {
		long jni = bulkJniCalls.get();
		long jniNs = bulkJniTotalNs.get();
		long wrappers = wrapperConstructCount.get();
		long fbs = fallbacks.get();

		double jniAvgUs = jni == 0 ? 0 : (double) jniNs / jni / 1_000.0;

		return String.format(
				"[flat-cache diag] wrappers=%d fallbacks=%d jni=%d (%.1fµs avg)",
				wrappers, fbs, jni, jniAvgUs);
	}

	public static void resetDiag() {
		fallbacks.set(0);
		bulkJniCalls.set(0);
		bulkJniTotalNs.set(0);
		wrapperConstructCount.set(0);
	}
}
