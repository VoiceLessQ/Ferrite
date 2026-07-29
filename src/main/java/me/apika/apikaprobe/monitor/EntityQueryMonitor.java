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

	// Move/query interleave probe (index-design decider, LOCAL_DESIGN).
	// A "transition" is a query that saw at least one move since the
	// previous query: transitions/tick near queries/tick means fully
	// interleaved (lazy per-section rebuild degenerates); a small number
	// means movement and queries cluster in phases (lazy is fine).
	private static boolean movedSinceQuery = false;
	private static long thisTickMovesSame = 0L;
	private static long thisTickMovesCross = 0L;
	private static long thisTickTransitions = 0L;

	private static final AtomicLong TOTAL_COUNT = new AtomicLong();
	private static final AtomicLong TOTAL_NS = new AtomicLong();
	private static final AtomicLong TICK_COUNT = new AtomicLong();
	private static final AtomicLong TOTAL_MOVES_SAME = new AtomicLong();
	private static final AtomicLong TOTAL_MOVES_CROSS = new AtomicLong();
	private static final AtomicLong TOTAL_TRANSITIONS = new AtomicLong();
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
			if (movedSinceQuery) {
				thisTickTransitions++;
				movedSinceQuery = false;
			}
			queryStart = System.nanoTime();
		}
	}

	/** Section-callback move event; crossing = entity changed sections. */
	public static void onMoveEvent(boolean crossing) {
		if (Thread.currentThread() != serverThread) return;
		if (crossing) thisTickMovesCross++; else thisTickMovesSame++;
		movedSinceQuery = true;
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
		TOTAL_MOVES_SAME.addAndGet(thisTickMovesSame);
		TOTAL_MOVES_CROSS.addAndGet(thisTickMovesCross);
		TOTAL_TRANSITIONS.addAndGet(thisTickTransitions);
		thisTickMovesSame = 0L;
		thisTickMovesCross = 0L;
		thisTickTransitions = 0L;
		movedSinceQuery = false;
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
		long movesSame = TOTAL_MOVES_SAME.getAndSet(0L);
		long movesCross = TOTAL_MOVES_CROSS.getAndSet(0L);
		long transitions = TOTAL_TRANSITIONS.getAndSet(0L);
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
		MonitorLog.info(
			"[entity-query] interleave: moves={}/tick (same-section={} cross={})  mq-transitions={}/tick ({}% of queries)",
			String.format("%.1f", (double) (movesSame + movesCross) / ticks),
			String.format("%.1f", (double) movesSame / ticks),
			String.format("%.1f", (double) movesCross / ticks),
			String.format("%.1f", (double) transitions / ticks),
			String.format("%.1f", count == 0 ? 0.0 : 100.0 * transitions / count)
		);
	}
}
