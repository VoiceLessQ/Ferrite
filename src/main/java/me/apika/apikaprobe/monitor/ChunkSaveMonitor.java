package me.apika.apikaprobe.monitor;

import java.util.concurrent.atomic.AtomicLong;

import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;

/**
 * Chunk save cost monitor, split by thread.
 *
 *   copyOf : SerializableChunkData.copyOf, the server-thread snapshot
 *            (section/light/heightmap copies plus block-entity NBT).
 *            This is the only save cost the tick pays in 26.1.x.
 *   write  : SerializableChunkData.write, the NBT encode + palette
 *            bit-pack. Runs on Util.backgroundExecutor(); tracked for
 *            context only, it does not block ticks.
 *
 * Same shape as ChunkGenMonitor: ThreadLocal starts, AtomicLong
 * accumulators, 5 s window drained from END_SERVER_TICK, line printed
 * only when a save happened.
 */
public final class ChunkSaveMonitor {
	private static final long REPORT_INTERVAL_NS = 5_000_000_000L;

	private static final ThreadLocal<Long> COPY_START = ThreadLocal.withInitial(() -> 0L);
	private static final ThreadLocal<Long> WRITE_START = ThreadLocal.withInitial(() -> 0L);

	private static final AtomicLong COPY_COUNT = new AtomicLong();
	private static final AtomicLong COPY_TOTAL_NS = new AtomicLong();
	private static final AtomicLong COPY_MAX_NS = new AtomicLong();

	private static final AtomicLong WRITE_COUNT = new AtomicLong();
	private static final AtomicLong WRITE_TOTAL_NS = new AtomicLong();
	private static final AtomicLong WRITE_MAX_NS = new AtomicLong();

	private static volatile long lastReportNs = System.nanoTime();

	private ChunkSaveMonitor() {}

	public static void register() {
		ServerTickEvents.END_SERVER_TICK.register(server -> maybeReport());
	}

	public static void onCopyStart() {
		COPY_START.set(System.nanoTime());
	}

	public static void onCopyEnd() {
		recordEnd(COPY_START, COPY_COUNT, COPY_TOTAL_NS, COPY_MAX_NS);
	}

	public static void onWriteStart() {
		WRITE_START.set(System.nanoTime());
	}

	public static void onWriteEnd() {
		recordEnd(WRITE_START, WRITE_COUNT, WRITE_TOTAL_NS, WRITE_MAX_NS);
	}

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
		maxNs.updateAndGet(prev -> Math.max(prev, duration));
	}

	private static void maybeReport() {
		long now = System.nanoTime();
		if (now - lastReportNs < REPORT_INTERVAL_NS) {
			return;
		}

		long cCount = COPY_COUNT.getAndSet(0L);
		long cTotal = COPY_TOTAL_NS.getAndSet(0L);
		long cMax = COPY_MAX_NS.getAndSet(0L);

		long wCount = WRITE_COUNT.getAndSet(0L);
		long wTotal = WRITE_TOTAL_NS.getAndSet(0L);
		long wMax = WRITE_MAX_NS.getAndSet(0L);

		lastReportNs = now;

		if (cCount == 0L && wCount == 0L) {
			return;
		}

		MonitorLog.info("[chunk-save] copyOf(server-thread): n={} avg={} ms max={} ms total={} ms  write(background): n={} avg={} ms max={} ms",
				cCount,
				formatAvg(cCount, cTotal),
				formatMs(cMax),
				formatMs(cTotal),
				wCount,
				formatAvg(wCount, wTotal),
				formatMs(wMax));
	}

	private static String formatAvg(long count, long totalNs) {
		return count == 0L ? "0.00" : formatMs(totalNs / count);
	}

	private static String formatMs(long nanos) {
		return String.format("%.2f", nanos / 1_000_000.0);
	}
}
