package me.apika.apikaprobe.monitor;

import java.util.concurrent.atomic.AtomicLong;

import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class LookControlMonitor {
	private static final Logger LOGGER = LoggerFactory.getLogger("ferrite");
	private static final long REPORT_INTERVAL_NS = 5_000_000_000L;

	private static final ThreadLocal<Long> TICK_START = ThreadLocal.withInitial(() -> 0L);

	private static long thisTickCalls = 0L;
	private static long thisTickNs    = 0L;

	private static final AtomicLong TOTAL_CALLS = new AtomicLong();
	private static final AtomicLong TOTAL_NS    = new AtomicLong();
	private static final AtomicLong TICK_COUNT  = new AtomicLong();

	private static volatile long lastReportNs = System.nanoTime();

	private LookControlMonitor() {}

	public static void register() {
		ServerTickEvents.END_SERVER_TICK.register(server -> onServerTickEnd());
	}

	public static void onTickBegin() {
		TICK_START.set(System.nanoTime());
	}

	public static void onTickEnd() {
		long start = TICK_START.get();
		if (start == 0L) return;
		TICK_START.set(0L);
		thisTickCalls++;
		thisTickNs += System.nanoTime() - start;
	}

	private static void onServerTickEnd() {
		if (thisTickCalls > 0L) {
			TOTAL_CALLS.addAndGet(thisTickCalls);
			TOTAL_NS.addAndGet(thisTickNs);
		}
		thisTickCalls = 0L;
		thisTickNs    = 0L;
		TICK_COUNT.incrementAndGet();
		maybeReport();
	}

	private static void maybeReport() {
		long now = System.nanoTime();
		if (now - lastReportNs < REPORT_INTERVAL_NS) return;

		long ticks   = TICK_COUNT.getAndSet(0L);
		long calls   = TOTAL_CALLS.getAndSet(0L);
		long totalNs = TOTAL_NS.getAndSet(0L);
		lastReportNs = now;

		if (ticks == 0L || calls == 0L) return;

		double callsPerTick   = (double) calls / ticks;
		double totalMsPerTick = totalNs / 1_000_000.0 / ticks;
		double avgUsPerCall   = totalNs / 1_000.0 / calls;

		MonitorLog.info(
			"[look-control] calls={}/tick total={}ms avg={}us/call  n={} ticks",
			String.format("%.1f", callsPerTick),
			String.format("%.3f", totalMsPerTick),
			String.format("%.2f", avgUsPerCall),
			ticks
		);
	}
}
