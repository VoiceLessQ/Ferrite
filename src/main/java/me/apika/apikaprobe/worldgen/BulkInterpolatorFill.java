package me.apika.apikaprobe.worldgen;

import me.apika.apikaprobe.ExampleMod;
import me.apika.apikaprobe.RustBridge;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.DoubleBuffer;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicLong;

import me.apika.apikaprobe.mixin.DensityInterpolatorAccessor;

/**
 * Phase 2.5 step 2b — bulk-fill the slice arrays vanilla's
 * {@code ChunkNoiseSampler.sampleDensity} would fill cell-by-cell.
 *
 * <p>Per chunk, vanilla calls {@code sampleDensity(start, cellX)} 9 times
 * (1 start + 4 advances × 2). Each iterates {@code horizontalCellCount+1}
 * z-rows × N interpolators × {@code verticalCellCount+1} Y entries via
 * the per-block applier. Total: ~12k–20k DF computes per chunk depending
 * on N.
 *
 * <p>This collapses each per-z-row × per-interpolator inner loop into one
 * Rust JNI call per interpolator that fills the entire slice's z-rows.
 * 9 sampleDensity passes × N interpolators = 9N JNI calls per chunk.
 *
 * <p>{@link #ENABLED} gates the swap. When false (default), the mixin
 * is a no-op and vanilla runs unmodified — clean rollback for parity
 * validation.
 */
public final class BulkInterpolatorFill {
	private BulkInterpolatorFill() {}

	/** Default OFF: live measurement showed ~14-18 ms/bulk × 9 bulks/chunk
	 *  for ~150 ms regression vs vanilla's 55-79 ms baseline. The Rust
	 *  DF interpreter lacks the per-block CacheOnce reuse that vanilla
	 *  gets for free inside slopedCheese — same wall as Phase 1B. The
	 *  infrastructure (capture + fill mixin + JNI) is correct and proven
	 *  via the bulk-slice diag (0 fallbacks). To enable for experiments:
	 *  {@code -Dferrite.bulkSlice=true}. To win this design, Rust needs
	 *  its own CacheOnce-equivalent intermediate caching. */
	public static volatile boolean ENABLED = Boolean.parseBoolean(
			System.getProperty("ferrite.bulkSlice", "false"));

	public static final AtomicLong bulkSliceFills = new AtomicLong();
	public static final AtomicLong vanillaFallbacks = new AtomicLong();
	public static final AtomicLong totalJniCalls = new AtomicLong();
	public static final AtomicLong totalJniNs = new AtomicLong();
	public static final AtomicLong unmatchedInterpolators = new AtomicLong();

	/** ThreadLocal scratch buffers to avoid per-call direct-buffer churn.
	 *  Sized for a single interpolator's slice (sideX=1, sideY=49, sideZ=5
	 *  = 245 doubles in the common overworld case). Lazily grows. */
	private static final class Scratch {
		ByteBuffer outBuf = ByteBuffer.allocateDirect(245 * Double.BYTES)
				.order(ByteOrder.nativeOrder());
		double[] tmp = new double[245];

		void ensure(int totalDoubles) {
			int needed = totalDoubles * Double.BYTES;
			if (outBuf.capacity() < needed) {
				outBuf = ByteBuffer.allocateDirect(needed).order(ByteOrder.nativeOrder());
			}
			if (tmp.length < totalDoubles) {
				tmp = new double[totalDoubles];
			}
		}
	}

	private static final ThreadLocal<Scratch> SCRATCH = ThreadLocal.withInitial(Scratch::new);

	/** Cached encoded name buffers — UTF-8 bytes prepacked into a direct
	 *  ByteBuffer once per name. Names are stable for the world's lifetime. */
	private static final java.util.concurrent.ConcurrentHashMap<String, EncodedName> NAME_CACHE =
			new java.util.concurrent.ConcurrentHashMap<>();

	private record EncodedName(ByteBuffer buf, int len) {}

	private static EncodedName encodeName(String name) {
		return NAME_CACHE.computeIfAbsent(name, n -> {
			byte[] bytes = n.getBytes(StandardCharsets.UTF_8);
			ByteBuffer b = ByteBuffer.allocateDirect(bytes.length).order(ByteOrder.nativeOrder());
			b.put(bytes); b.flip();
			return new EncodedName(b, bytes.length);
		});
	}

	/**
	 * Try to bulk-fill all interpolators' slices for one sampleDensity
	 * pass. Returns {@code true} if every interpolator was filled
	 * (caller cancels vanilla), {@code false} if any miss occurred and
	 * vanilla must run as a fallback.
	 */
	public static boolean fillAllSlices(
			java.util.List<?> interpolators,
			boolean start,
			int cellX,
			int startCellZ,
			int horizontalCellBlockCount,
			int verticalCellBlockCount,
			int horizontalCellCount,
			int verticalCellCount,
			int minimumCellY) {
		if (!ENABLED) return false;
		int n = interpolators.size();
		if (n == 0) return true;

		String[] names = new String[n];
		for (int i = 0; i < n; i++) {
			String name = InterpolatorNameRegistry.nameFor(interpolators.get(i));
			if (name == null) {
				unmatchedInterpolators.incrementAndGet();
				vanillaFallbacks.incrementAndGet();
				return false;
			}
			names[i] = name;
		}

		int sideX = 1;
		int sideY = verticalCellCount + 1;
		int sideZ = horizontalCellCount + 1;
		int total = sideX * sideY * sideZ;

		Scratch scratch = SCRATCH.get();
		scratch.ensure(total);
		ByteBuffer outBuf = scratch.outBuf;
		double[] tmp = scratch.tmp;

		int originX = cellX * horizontalCellBlockCount;
		int originY = minimumCellY * verticalCellBlockCount;
		int originZ = startCellZ * horizontalCellBlockCount;

		long t0 = System.nanoTime();
		for (int i = 0; i < n; i++) {
			EncodedName en = encodeName(names[i]);
			outBuf.position(0);
			int written = RustBridge.sampleDensitySlicesRust(
					en.buf, en.len,
					originX, originY, originZ,
					sideX, sideY, sideZ,
					horizontalCellBlockCount,
					verticalCellBlockCount,
					horizontalCellBlockCount,
					outBuf);
			totalJniCalls.incrementAndGet();
			if (written != total) {
				vanillaFallbacks.incrementAndGet();
				return false;
			}

			outBuf.position(0);
			DoubleBuffer dbuf = outBuf.asDoubleBuffer();
			dbuf.get(tmp, 0, total);

			Object interp = interpolators.get(i);
			DensityInterpolatorAccessor acc = (DensityInterpolatorAccessor) interp;
			double[][] slice = start
					? acc.apikaprobe$getStartDensityBuffer()
					: acc.apikaprobe$getEndDensityBuffer();
			// slice[iz] is the row-iz buffer (sized sideY = verticalCellCount+1).
			// Output layout: (iy * sideZ + iz) since sideX=1.
			for (int iz = 0; iz < sideZ; iz++) {
				double[] row = slice[iz];
				for (int iy = 0; iy < sideY; iy++) {
					row[iy] = tmp[iy * sideZ + iz];
				}
			}
		}
		totalJniNs.addAndGet(System.nanoTime() - t0);
		bulkSliceFills.incrementAndGet();
		return true;
	}

	public static String diagSummary() {
		long bulks = bulkSliceFills.get();
		long fbs = vanillaFallbacks.get();
		long jni = totalJniCalls.get();
		long jniNs = totalJniNs.get();
		long unmatched = unmatchedInterpolators.get();
		double jniAvgUs = jni == 0 ? 0 : (double) jniNs / jni / 1_000.0;
		double jniPerBulkMs = bulks == 0 ? 0 : (double) jniNs / bulks / 1_000_000.0;
		return String.format(
				"[bulk-slice] enabled=%s bulks=%d fallbacks=%d jni=%d (%.1fµs avg, %.2fms/bulk) unmatched=%d",
				ENABLED, bulks, fbs, jni, jniAvgUs, jniPerBulkMs, unmatched);
	}

	public static void resetDiag() {
		bulkSliceFills.set(0);
		vanillaFallbacks.set(0);
		totalJniCalls.set(0);
		totalJniNs.set(0);
		unmatchedInterpolators.set(0);
	}
}
