package me.apika.apikaprobe.monitor;

import java.util.concurrent.atomic.AtomicLong;

import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Measures NearestAttackableTargetGoal.canStart() call frequency and findClosestTarget()
 * scan cost per tick.
 *
 * canStart() is called every tick by GoalSelector for every inactive
 * NearestAttackableTargetGoal. Most calls return immediately via the reciprocalChance
 * gate (random.nextInt(n) != 0). When the gate passes, findClosestTarget()
 * fires a world.getEntitiesByClass() spatial scan.
 *
 * This monitor separates the two so we can see:
 *   canStart calls/tick  — total goal evaluations hitting NearestAttackableTargetGoal
 *   scans/tick           — actual entity scans after the chance gate
 *   scan time/tick       — total wall time spent inside findClosestTarget()
 *   per-scan cost        — average cost of one scan (= scan_time / scans)
 */
public final class TargetScanMonitor {
	private static final Logger LOGGER = LoggerFactory.getLogger("ferrite");
	private static final long REPORT_INTERVAL_NS = 5_000_000_000L;

	private static final ThreadLocal<Long> SCAN_START = ThreadLocal.withInitial(() -> 0L);

	private static long thisTickCanStart = 0L;
	private static long thisTickScanCount = 0L;
	private static long thisTickScanNs = 0L;

	private static final AtomicLong TOTAL_CAN_START = new AtomicLong();
	private static final AtomicLong TOTAL_SCAN_COUNT = new AtomicLong();
	private static final AtomicLong TOTAL_SCAN_NS = new AtomicLong();
	private static final AtomicLong TICK_COUNT = new AtomicLong();

	private static volatile long lastReportNs = System.nanoTime();

	private TargetScanMonitor() {}

	public static void register() {
		ServerTickEvents.END_SERVER_TICK.register(server -> onServerTickEnd());
	}

	// --- Hooks ---------------------------------------------------------------

	public static void onCanStartCalled() {
		thisTickCanStart++;
	}

	public static void onScanBegin() {
		SCAN_START.set(System.nanoTime());
	}

	public static void onScanEnd() {
		long start = SCAN_START.get();
		if (start == 0L) return;
		SCAN_START.set(0L);
		thisTickScanCount++;
		thisTickScanNs += System.nanoTime() - start;
	}

	// --- Internals -----------------------------------------------------------

	private static void onServerTickEnd() {
		if (thisTickCanStart > 0L) TOTAL_CAN_START.addAndGet(thisTickCanStart);
		if (thisTickScanCount > 0L) {
			TOTAL_SCAN_COUNT.addAndGet(thisTickScanCount);
			TOTAL_SCAN_NS.addAndGet(thisTickScanNs);
		}
		thisTickCanStart = 0L;
		thisTickScanCount = 0L;
		thisTickScanNs = 0L;
		TICK_COUNT.incrementAndGet();
		maybeReport();
	}

	private static void maybeReport() {
		long now = System.nanoTime();
		if (now - lastReportNs < REPORT_INTERVAL_NS) return;

		long ticks = TICK_COUNT.getAndSet(0L);
		long canStartTotal = TOTAL_CAN_START.getAndSet(0L);
		long scanCount = TOTAL_SCAN_COUNT.getAndSet(0L);
		long scanNs = TOTAL_SCAN_NS.getAndSet(0L);
		lastReportNs = now;

		if (ticks == 0L) return;

		double canStartPerTick = (double) canStartTotal / ticks;
		double scansPerTick = (double) scanCount / ticks;
		double gatePassPct = canStartTotal > 0 ? (100.0 * scanCount / canStartTotal) : 0.0;
		double scanMsPerTick = scanNs / 1_000_000.0 / ticks;
		double perScanMs = scanCount > 0 ? (scanNs / 1_000_000.0 / scanCount) : 0.0;

		LOGGER.info(
			"[target-scan] canStart: avg={}/tick  scans: avg={}/tick ({})  scan_time: avg={}ms/tick  per_scan={}ms  n={} ticks",
			String.format("%.1f", canStartPerTick),
			String.format("%.1f", scansPerTick),
			String.format("%.1f%%", gatePassPct),
			String.format("%.3f", scanMsPerTick),
			String.format("%.3f", perScanMs),
			ticks
		);
	}
}
