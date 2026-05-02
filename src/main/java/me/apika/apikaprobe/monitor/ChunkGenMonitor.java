package me.apika.apikaprobe.monitor;

import java.util.concurrent.atomic.AtomicLong;

import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Per-phase chunk-generation cost monitor.
 *
 * Tracks three separate phases:
 *   noise-dispatch : the public populateNoise(Blender, NoiseConfig, ...)
 *                    which returns a CompletableFuture — only measures
 *                    future-creation overhead, not the real work.
 *   noise-sync     : the private populateNoise(... , int, int) called
 *                    inside the async task body — the real noise work.
 *   surface        : buildSurface, synchronous.
 *
 * Writers: chunk-gen worker threads call the onXStart/End hooks from
 * Mixin @Inject points. Start times live in ThreadLocals. Accumulators
 * are AtomicLong for lock-free concurrent updates.
 *
 * Reader: the server thread polls every 5 seconds via END_SERVER_TICK.
 * Accumulators reset after each report — windows are independent so
 * exploring new chunks shows its own cost, not a session average.
 */
public final class ChunkGenMonitor {
	private static final Logger LOGGER = LoggerFactory.getLogger("ferrite");

	private static final long REPORT_INTERVAL_NS = 5_000_000_000L;

	private static final ThreadLocal<Long> NOISE_START = ThreadLocal.withInitial(() -> 0L);
	private static final ThreadLocal<Long> SYNC_NOISE_START = ThreadLocal.withInitial(() -> 0L);
	private static final ThreadLocal<Long> SURFACE_START = ThreadLocal.withInitial(() -> 0L);

	private static final AtomicLong NOISE_COUNT = new AtomicLong();
	private static final AtomicLong NOISE_TOTAL_NS = new AtomicLong();
	private static final AtomicLong NOISE_MAX_NS = new AtomicLong();

	private static final AtomicLong SYNC_NOISE_COUNT = new AtomicLong();
	private static final AtomicLong SYNC_NOISE_TOTAL_NS = new AtomicLong();
	private static final AtomicLong SYNC_NOISE_MAX_NS = new AtomicLong();

	private static final AtomicLong SURFACE_COUNT = new AtomicLong();
	private static final AtomicLong SURFACE_TOTAL_NS = new AtomicLong();
	private static final AtomicLong SURFACE_MAX_NS = new AtomicLong();

	private static volatile long lastReportNs = System.nanoTime();

	private ChunkGenMonitor() {}

	public static void register() {
		ServerTickEvents.END_SERVER_TICK.register(server -> maybeReport());
	}

	// --- Phase hooks --------------------------------------------------------

	public static void onNoiseStart() {
		NOISE_START.set(System.nanoTime());
	}

	public static void onNoiseEnd() {
		recordEnd(NOISE_START, NOISE_COUNT, NOISE_TOTAL_NS, NOISE_MAX_NS);
	}

	public static void onSyncNoiseStart() {
		SYNC_NOISE_START.set(System.nanoTime());
	}

	public static void onSyncNoiseEnd() {
		recordEnd(SYNC_NOISE_START, SYNC_NOISE_COUNT, SYNC_NOISE_TOTAL_NS, SYNC_NOISE_MAX_NS);
	}

	public static void onSurfaceStart() {
		SURFACE_START.set(System.nanoTime());
	}

	public static void onSurfaceEnd() {
		recordEnd(SURFACE_START, SURFACE_COUNT, SURFACE_TOTAL_NS, SURFACE_MAX_NS);
	}

	// Live read accessors for cross-monitor use (e.g. NoiseStageMonitor uses
	// these to compute inferred blockstate cost before this monitor resets).
	// Values reflect the current 5-second window's accumulators in progress.

	public static long getSyncNoiseCount() {
		return SYNC_NOISE_COUNT.get();
	}

	public static long getSyncNoiseTotalNs() {
		return SYNC_NOISE_TOTAL_NS.get();
	}

	// --- Internals ----------------------------------------------------------

	private static void recordEnd(
			ThreadLocal<Long> startRef,
			AtomicLong count,
			AtomicLong totalNs,
			AtomicLong maxNs) {
		long start = startRef.get();
		if (start == 0L) {
			return;
		}
		startRef.set(0L);
		long duration = System.nanoTime() - start;
		count.incrementAndGet();
		totalNs.addAndGet(duration);
		updateMax(maxNs, duration);
	}

	private static void updateMax(AtomicLong max, long candidate) {
		max.updateAndGet(prev -> Math.max(prev, candidate));
	}

	private static void maybeReport() {
		long now = System.nanoTime();
		if (now - lastReportNs < REPORT_INTERVAL_NS) {
			return;
		}

		long nCount = NOISE_COUNT.getAndSet(0L);
		long nTotal = NOISE_TOTAL_NS.getAndSet(0L);
		long nMax = NOISE_MAX_NS.getAndSet(0L);

		long snCount = SYNC_NOISE_COUNT.getAndSet(0L);
		long snTotal = SYNC_NOISE_TOTAL_NS.getAndSet(0L);
		long snMax = SYNC_NOISE_MAX_NS.getAndSet(0L);

		long sCount = SURFACE_COUNT.getAndSet(0L);
		long sTotal = SURFACE_TOTAL_NS.getAndSet(0L);
		long sMax = SURFACE_MAX_NS.getAndSet(0L);

		lastReportNs = now;

		if (nCount == 0L && snCount == 0L && sCount == 0L) {
			return;
		}

		MonitorLog.info("[chunkgen] noise-dispatch: n={} avg={} ms max={} ms  noise-sync: n={} avg={} ms max={} ms  surface: n={} avg={} ms max={} ms",
				nCount,
				formatAvg(nCount, nTotal),
				formatMs(nMax),
				snCount,
				formatAvg(snCount, snTotal),
				formatMs(snMax),
				sCount,
				formatAvg(sCount, sTotal),
				formatMs(sMax));
	}

	private static String formatAvg(long count, long totalNs) {
		return count == 0L ? "0.00" : formatMs(totalNs / count);
	}

	private static String formatMs(long nanos) {
		return String.format("%.2f", nanos / 1_000_000.0);
	}
}
