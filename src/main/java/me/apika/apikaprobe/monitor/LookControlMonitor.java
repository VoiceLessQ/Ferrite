package me.apika.apikaprobe.monitor;

import java.util.concurrent.atomic.AtomicLong;

import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;

public final class LookControlMonitor {
	private static final long REPORT_INTERVAL_NS = 5_000_000_000L;

	// long[1] not boxed Long: fires per mob per tick.
	private static final ThreadLocal<long[]> TICK_START = ThreadLocal.withInitial(() -> new long[1]);

	private static final AtomicLong THIS_TICK_CALLS = new AtomicLong();
	private static final AtomicLong THIS_TICK_NS = new AtomicLong();

	private static final AtomicLong TOTAL_CALLS = new AtomicLong();
	private static final AtomicLong TICK_COUNT  = new AtomicLong();
	private static final LatencyHistogram TICK_COST = new LatencyHistogram();

	private static volatile long lastReportNs = System.nanoTime();

	private LookControlMonitor() {}

	public static void register() {
		ServerTickEvents.END_SERVER_TICK.register(server -> onServerTickEnd());
	}

	public static void onTickBegin() {
		if (!MonitorLog.ENABLED) return;
		TICK_START.get()[0] = System.nanoTime();
	}

	public static void onTickEnd() {
		long[] s = TICK_START.get();
		long start = s[0];
		if (start == 0L) return;
		s[0] = 0L;
		THIS_TICK_CALLS.incrementAndGet();
		THIS_TICK_NS.addAndGet(System.nanoTime() - start);
	}

	private static void onServerTickEnd() {
		long calls = THIS_TICK_CALLS.getAndSet(0L);
		long tickNs = THIS_TICK_NS.getAndSet(0L);
		if (calls > 0L) {
			TOTAL_CALLS.addAndGet(calls);
			TICK_COST.record(tickNs);
		}
		TICK_COUNT.incrementAndGet();
		maybeReport();
	}

	private static void maybeReport() {
		long now = System.nanoTime();
		if (now - lastReportNs < REPORT_INTERVAL_NS) return;

		long ticks = TICK_COUNT.getAndSet(0L);
		long calls = TOTAL_CALLS.getAndSet(0L);
		LatencyHistogram.Snapshot snap = TICK_COST.drain();
		lastReportNs = now;

		if (ticks == 0L || calls == 0L) return;

		double callsPerTick = (double) calls / ticks;

		MonitorLog.info(
			"[look-control] calls={}/tick  cost: {}  ticks={}",
			String.format("%.1f", callsPerTick),
			snap.formatLine(),
			ticks
		);
	}
}
