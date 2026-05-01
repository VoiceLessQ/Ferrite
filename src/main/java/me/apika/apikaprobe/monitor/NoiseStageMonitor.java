package me.apika.apikaprobe.monitor;

import java.util.concurrent.atomic.AtomicLong;

import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Sub-phase breakdown inside noise-sync.
 *
 * Times two non-overlapping stages of NoiseChunk:
 *   start : sampleStartDensity() — one call per chunk, sets up start buffer
 *   end   : sampleEndDensity(int cellX) — called per cellX (~4×/chunk)
 *
 * The inferred `blockstate` cost is what's left of noise-sync after
 * subtracting start + end. That's the sampleBlockState() hot loop
 * (~98K calls per chunk) which we deliberately don't instrument to
 * avoid measurement distortion.
 *
 * Registration order matters: this monitor reads ChunkGenMonitor's
 * live sync-noise counters before they reset. So register this before
 * ChunkGenMonitor so its END_SERVER_TICK listener fires first.
 */
public final class NoiseStageMonitor {
	private static final Logger LOGGER = LoggerFactory.getLogger("ferrite");

	private static final long REPORT_INTERVAL_NS = 5_000_000_000L;

	private static final ThreadLocal<Long> START_TS = ThreadLocal.withInitial(() -> 0L);
	private static final ThreadLocal<Long> END_TS = ThreadLocal.withInitial(() -> 0L);

	private static final AtomicLong START_COUNT = new AtomicLong();
	private static final AtomicLong START_TOTAL_NS = new AtomicLong();
	private static final AtomicLong START_MAX_NS = new AtomicLong();

	private static final AtomicLong END_COUNT = new AtomicLong();
	private static final AtomicLong END_TOTAL_NS = new AtomicLong();
	private static final AtomicLong END_MAX_NS = new AtomicLong();

	private static volatile long lastReportNs = System.nanoTime();

	private NoiseStageMonitor() {}

	public static void register() {
		ServerTickEvents.END_SERVER_TICK.register(server -> maybeReport());
	}

	public static void onStartBegin() {
		START_TS.set(System.nanoTime());
	}

	public static void onStartEnd() {
		record(START_TS, START_COUNT, START_TOTAL_NS, START_MAX_NS);
	}

	public static void onEndBegin() {
		END_TS.set(System.nanoTime());
	}

	public static void onEndEnd() {
		record(END_TS, END_COUNT, END_TOTAL_NS, END_MAX_NS);
	}

	private static void record(
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
		maxNs.updateAndGet(prev -> Math.max(prev, duration));
	}

	private static void maybeReport() {
		long now = System.nanoTime();
		if (now - lastReportNs < REPORT_INTERVAL_NS) {
			return;
		}

		long sCount = START_COUNT.getAndSet(0L);
		long sTotal = START_TOTAL_NS.getAndSet(0L);
		long sMax = START_MAX_NS.getAndSet(0L);

		long eCount = END_COUNT.getAndSet(0L);
		long eTotal = END_TOTAL_NS.getAndSet(0L);
		long eMax = END_MAX_NS.getAndSet(0L);

		// Read ChunkGenMonitor's live sync-noise counters. We register BEFORE
		// ChunkGenMonitor so these haven't been reset yet this tick.
		long syncCount = ChunkGenMonitor.getSyncNoiseCount();
		long syncTotal = ChunkGenMonitor.getSyncNoiseTotalNs();

		lastReportNs = now;

		if (sCount == 0L && eCount == 0L && syncCount == 0L) {
			return;
		}

		// Per-chunk averages.
		// sampleStartDensity fires once per chunk, so startCount == chunk count.
		// sampleEndDensity fires ~4× per chunk, so endTotal per chunk = eTotal / startCount.
		// Sync-noise total per chunk = syncTotal / syncCount.
		double startPerChunkMs = sCount == 0L ? 0.0 : (sTotal / (double) sCount) / 1_000_000.0;
		double endPerChunkMs = sCount == 0L ? 0.0 : (eTotal / (double) sCount) / 1_000_000.0;
		double syncPerChunkMs = syncCount == 0L ? 0.0 : (syncTotal / (double) syncCount) / 1_000_000.0;
		double inferredBlockstateMs = Math.max(0.0, syncPerChunkMs - startPerChunkMs - endPerChunkMs);

		LOGGER.info("[noisestages] start: avg={} ms max={} ms  end: avg={} ms max={} ms (n={})  blockstate(inferred): avg={} ms/chunk",
				String.format("%.2f", startPerChunkMs),
				formatMs(sMax),
				String.format("%.2f", endPerChunkMs),
				formatMs(eMax),
				eCount,
				String.format("%.2f", inferredBlockstateMs));
	}

	private static String formatMs(long nanos) {
		return String.format("%.2f", nanos / 1_000_000.0);
	}
}
