package me.apika.apikaprobe;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;

import me.apika.apikaprobe.worldgen.BiomeParity;

/**
 * Per-chunk timing of the FEATURES (decoration) phase, bucketed by
 * the chunk's center biome.
 *
 * <p>Goal: discover which biomes' decorator phases are cheap (skippable
 * candidates) vs expensive (where Rust acceleration would actually pay).
 * Data-driven precursor to any "skip the decorator on ocean" optimization.
 *
 * <p>Lifecycle:
 * <ul>
 *   <li>{@link ChunkDecoratorTimingMixin} HEAD captures start time.</li>
 *   <li>{@link ChunkDecoratorTimingMixin} RETURN computes elapsed +
 *       feeds {@link #record(int, int, long)}.</li>
 *   <li>Per-server-tick handler logs aggregated buckets every 5s.</li>
 * </ul>
 *
 * <p>Per-thread state: chunk gen runs on worker threads. Start nanos
 * lives in a ThreadLocal so concurrent chunks don't trample each other.
 */
public final class ChunkDecoratorTiming {
	private static final long REPORT_INTERVAL_NS = 5_000_000_000L;

	private static final ThreadLocal<long[]> startScratch = ThreadLocal.withInitial(() -> new long[1]);

	/** biome name → (total ns, chunk count). */
	private static final Map<String, Bucket> buckets = new ConcurrentHashMap<>();
	private static long lastReportNanos = System.nanoTime();

	private ChunkDecoratorTiming() {}

	public static void register() {
		ServerTickEvents.END_SERVER_TICK.register(server -> maybeReport());
	}

	public static void start() {
		startScratch.get()[0] = System.nanoTime();
	}

	public static void end(int centerX, int centerZ) {
		long startNs = startScratch.get()[0];
		if (startNs == 0L) return;
		startScratch.get()[0] = 0L;
		long elapsedNs = System.nanoTime() - startNs;
		String biome = BiomeParity.lookupBiomeAt(centerX, 64, centerZ);
		if (biome == null) biome = "<unknown>";
		record(elapsedNs, biome);
	}

	private static void record(long elapsedNs, String biome) {
		buckets.computeIfAbsent(biome, k -> new Bucket()).add(elapsedNs);
	}

	private static void maybeReport() {
		long now = System.nanoTime();
		if (now - lastReportNanos < REPORT_INTERVAL_NS) return;
		lastReportNanos = now;

		// Snapshot + reset.
		Bucket[] snapshot;
		String[] names;
		synchronized (buckets) {
			int n = buckets.size();
			if (n == 0) return;
			snapshot = new Bucket[n];
			names = new String[n];
			int i = 0;
			for (Map.Entry<String, Bucket> e : buckets.entrySet()) {
				names[i] = e.getKey();
				snapshot[i] = e.getValue().snapshotAndReset();
				i++;
			}
		}

		// Sort by total time desc.
		Integer[] order = new Integer[snapshot.length];
		for (int i = 0; i < order.length; i++) order[i] = i;
		java.util.Arrays.sort(order, (a, b) -> Long.compare(snapshot[b].totalNs, snapshot[a].totalNs));

		long grandTotal = 0;
		long grandCount = 0;
		for (Bucket b : snapshot) {
			grandTotal += b.totalNs;
			grandCount += b.count;
		}
		if (grandCount == 0) return;

		double grandAvgMs = (double) grandTotal / 1_000_000.0 / (double) grandCount;
		ExampleMod.LOGGER.info(
				"[chunkgen-features] window: {} chunks, total={}ms, avg={}ms/chunk",
				grandCount, grandTotal / 1_000_000, String.format("%.2f", grandAvgMs));
		int shown = Math.min(snapshot.length, 12);
		for (int k = 0; k < shown; k++) {
			int i = order[k];
			Bucket b = snapshot[i];
			if (b.count == 0) continue;
			double avgMs = (double) b.totalNs / 1_000_000.0 / (double) b.count;
			ExampleMod.LOGGER.info(
					"[chunkgen-features]   {}: n={} avg={}ms total={}ms",
					names[i], b.count, String.format("%.3f", avgMs), b.totalNs / 1_000_000);
		}
	}

	private static final class Bucket {
		final AtomicLong totalNsAtomic = new AtomicLong();
		final AtomicLong countAtomic = new AtomicLong();
		long totalNs;
		long count;

		void add(long ns) {
			totalNsAtomic.addAndGet(ns);
			countAtomic.incrementAndGet();
		}

		Bucket snapshotAndReset() {
			Bucket s = new Bucket();
			s.totalNs = totalNsAtomic.getAndSet(0);
			s.count = countAtomic.getAndSet(0);
			return s;
		}
	}
}
