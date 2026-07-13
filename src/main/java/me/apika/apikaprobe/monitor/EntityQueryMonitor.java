package me.apika.apikaprobe.monitor;

import java.util.concurrent.atomic.AtomicLong;

import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;

/**
 * Counts entity spatial queries (EntitySectionStorage.getEntities, both
 * overloads): the "which entities are in this box" scans behind targeting,
 * sensing, collision candidate collection, and cramming.
 *
 * Gate 3 instrument for the entity-spatial-query-index candidate (JOURNEY
 * "26.2 tick-time discovery"): queries/tick and per-query cost at horde
 * load decide whether a batched index is even worth scoping.
 *
 * Reports:
 *   queries/tick     spatial scans per server tick
 *   per_query        average cost of one scan
 *   tick-cost        per-tick total scan time (mean + p50/p95/p99/max)
 *
 * Outermost-query timing only; nested scans fold into their parent.
 * Server thread only; off-thread queries are ignored.
 */
public final class EntityQueryMonitor {
	private static final long REPORT_INTERVAL_NS = 5_000_000_000L;

	private static volatile Thread serverThread;

	private static int depth = 0;
	private static long queryStart = 0L;

	private static long thisTickCount = 0L;
	private static long thisTickNs = 0L;

	private static final AtomicLong TOTAL_COUNT = new AtomicLong();
	private static final AtomicLong TOTAL_NS = new AtomicLong();
	private static final AtomicLong TICK_COUNT = new AtomicLong();
	private static final LatencyHistogram TICK_COST = new LatencyHistogram();

	private static volatile long lastReportNs = System.nanoTime();

	private EntityQueryMonitor() {}

	public static void register() {
		ServerTickEvents.END_SERVER_TICK.register(server -> {
			serverThread = Thread.currentThread();
			onServerTickEnd();
		});
	}

	public static void onQueryBegin() {
		if (Thread.currentThread() != serverThread) return;
		if (depth++ == 0) {
			queryStart = System.nanoTime();
		}
	}

	public static void onQueryEnd() {
		if (Thread.currentThread() != serverThread) return;
		if (depth == 0) return;
		if (--depth == 0) {
			thisTickNs += System.nanoTime() - queryStart;
			thisTickCount++;
		}
	}

	private static void onServerTickEnd() {
		if (thisTickCount > 0L) {
			TOTAL_COUNT.addAndGet(thisTickCount);
			TOTAL_NS.addAndGet(thisTickNs);
			TICK_COST.record(thisTickNs);
		}
		thisTickCount = 0L;
		thisTickNs = 0L;
		depth = 0;
		TICK_COUNT.incrementAndGet();
		maybeReport();
	}

	private static void maybeReport() {
		long now = System.nanoTime();
		if (now - lastReportNs < REPORT_INTERVAL_NS) return;

		long ticks = TICK_COUNT.getAndSet(0L);
		long count = TOTAL_COUNT.getAndSet(0L);
		long ns = TOTAL_NS.getAndSet(0L);
		LatencyHistogram.Snapshot snap = TICK_COST.drain();
		lastReportNs = now;

		if (ticks == 0L || count == 0L) return;

		double perTick = (double) count / ticks;
		double perQueryUs = ns / 1_000.0 / count;

		MonitorLog.info(
			"[entity-query] queries={}/tick  per_query={}us  tick-cost: {}  ticks={}",
			String.format("%.1f", perTick),
			String.format("%.2f", perQueryUs),
			snap.formatLine(),
			ticks
		);
	}
}
