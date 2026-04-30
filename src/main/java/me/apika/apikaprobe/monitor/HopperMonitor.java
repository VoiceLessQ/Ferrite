package me.apika.apikaprobe.monitor;

import java.util.concurrent.atomic.AtomicLong;

import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class HopperMonitor {
	private static final Logger LOGGER = LoggerFactory.getLogger("ferrite");
	private static final long REPORT_INTERVAL_NS = 5_000_000_000L;

	private static final ThreadLocal<Long> SCAN_START = ThreadLocal.withInitial(() -> 0L);

	private static long thisTickHoppers   = 0L;
	private static long thisTickScans     = 0L;
	private static long thisTickNs        = 0L;
	private static long thisTickItemsFound = 0L;

	private static final AtomicLong TOTAL_HOPPERS    = new AtomicLong();
	private static final AtomicLong TOTAL_SCANS      = new AtomicLong();
	private static final AtomicLong TOTAL_NS         = new AtomicLong();
	private static final AtomicLong TOTAL_ITEMS      = new AtomicLong();
	private static final AtomicLong TICK_COUNT       = new AtomicLong();

	private static volatile long lastReportNs = System.nanoTime();

	private HopperMonitor() {}

	public static void register() {
		ServerTickEvents.END_SERVER_TICK.register(server -> onServerTickEnd());
	}

	public static void onHopperTick() {
		thisTickHoppers++;
	}

	public static void onScanBegin() {
		SCAN_START.set(System.nanoTime());
	}

	public static void onScanEnd(int itemsFound) {
		long start = SCAN_START.get();
		if (start == 0L) return;
		SCAN_START.set(0L);
		thisTickScans++;
		thisTickNs += System.nanoTime() - start;
		thisTickItemsFound += itemsFound;
	}

	private static void onServerTickEnd() {
		if (thisTickHoppers > 0L) {
			TOTAL_HOPPERS.addAndGet(thisTickHoppers);
			TOTAL_SCANS.addAndGet(thisTickScans);
			TOTAL_NS.addAndGet(thisTickNs);
			TOTAL_ITEMS.addAndGet(thisTickItemsFound);
		}
		thisTickHoppers    = 0L;
		thisTickScans      = 0L;
		thisTickNs         = 0L;
		thisTickItemsFound = 0L;
		TICK_COUNT.incrementAndGet();
		maybeReport();
	}

	private static void maybeReport() {
		long now = System.nanoTime();
		if (now - lastReportNs < REPORT_INTERVAL_NS) return;

		long ticks   = TICK_COUNT.getAndSet(0L);
		long hoppers = TOTAL_HOPPERS.getAndSet(0L);
		long scans   = TOTAL_SCANS.getAndSet(0L);
		long totalNs = TOTAL_NS.getAndSet(0L);
		long items   = TOTAL_ITEMS.getAndSet(0L);
		lastReportNs = now;

		if (ticks == 0L || hoppers == 0L) return;

		double hoppersPerTick = (double) hoppers / ticks;
		double scansPerTick   = (double) scans / ticks;
		double totalMsPerTick = totalNs / 1_000_000.0 / ticks;
		double avgUsPerScan   = scans > 0L ? totalNs / 1_000.0 / scans : 0.0;
		double itemsPerScan   = scans > 0L ? (double) items / scans : 0.0;

		LOGGER.info(
			"[hopper] hoppers={}/tick scans={}/tick total={}ms avg={}us/scan itemsFound={}/scan  n={} ticks",
			String.format("%.1f", hoppersPerTick),
			String.format("%.1f", scansPerTick),
			String.format("%.3f", totalMsPerTick),
			String.format("%.2f", avgUsPerScan),
			String.format("%.1f", itemsPerScan),
			ticks
		);
	}
}
