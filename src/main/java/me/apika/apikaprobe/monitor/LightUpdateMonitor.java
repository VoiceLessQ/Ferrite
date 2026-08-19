package me.apika.apikaprobe.monitor;

import java.util.concurrent.atomic.AtomicLong;

import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;

/**
 * Light engine cost monitor, split by thread.
 *
 *   feed    : ThreadedLevelLightEngine.checkBlock enqueues, the only
 *             per-block-change cost the tick thread pays in 26.1.x.
 *   update  : ThreadedLevelLightEngine.runUpdate, the batched (1000)
 *             propagation pass on the light ConsecutiveExecutor.
 *             Off-thread; tracked for throughput and backlog, it does
 *             not block ticks directly.
 *   backlog : lightTasks.size() sampled at runUpdate entry, window max.
 *
 * Cross-thread, so AtomicLong (same AtomicLong pattern as the other monitors). 5 s window
 * drained from END_SERVER_TICK, line printed only when updates ran.
 */
public final class LightUpdateMonitor {
	private static final long REPORT_INTERVAL_NS = 5_000_000_000L;

	private static final ThreadLocal<Long> UPDATE_START = ThreadLocal.withInitial(() -> 0L);

	private static final AtomicLong FEED_COUNT = new AtomicLong();

	private static final AtomicLong UPDATE_COUNT = new AtomicLong();
	private static final AtomicLong UPDATE_TOTAL_NS = new AtomicLong();
	private static final AtomicLong UPDATE_MAX_NS = new AtomicLong();
	private static final AtomicLong BACKLOG_MAX = new AtomicLong();

	private static volatile long lastReportNs = System.nanoTime();

	private LightUpdateMonitor() {}

	public static void register() {
		ServerTickEvents.END_SERVER_TICK.register(server -> maybeReport());
	}

	public static void onFeed() {
		FEED_COUNT.incrementAndGet();
	}

	public static void onUpdateStart(int backlog) {
		UPDATE_START.set(System.nanoTime());
		BACKLOG_MAX.updateAndGet(prev -> Math.max(prev, backlog));
	}

	public static void onUpdateEnd() {
		long start = UPDATE_START.get();
		if (start == 0L) {
			return;
		}
		UPDATE_START.set(0L);
		long duration = System.nanoTime() - start;
		UPDATE_COUNT.incrementAndGet();
		UPDATE_TOTAL_NS.addAndGet(duration);
		UPDATE_MAX_NS.updateAndGet(prev -> Math.max(prev, duration));
	}

	private static void maybeReport() {
		long now = System.nanoTime();
		if (now - lastReportNs < REPORT_INTERVAL_NS) {
			return;
		}

		long feeds = FEED_COUNT.getAndSet(0L);
		long uCount = UPDATE_COUNT.getAndSet(0L);
		long uTotal = UPDATE_TOTAL_NS.getAndSet(0L);
		long uMax = UPDATE_MAX_NS.getAndSet(0L);
		long backlog = BACKLOG_MAX.getAndSet(0L);

		lastReportNs = now;

		if (uCount == 0L && feeds == 0L) {
			return;
		}

		MonitorLog.info("[light] feed(tick-thread): n={}  update(light-thread): n={} avg={} ms max={} ms total={} ms backlogMax={}",
				feeds,
				uCount,
				formatAvg(uCount, uTotal),
				formatMs(uMax),
				formatMs(uTotal),
				backlog);
	}

	private static String formatAvg(long count, long totalNs) {
		return count == 0L ? "0.00" : formatMs(totalNs / count);
	}

	private static String formatMs(long nanos) {
		return String.format("%.2f", nanos / 1_000_000.0);
	}
}
