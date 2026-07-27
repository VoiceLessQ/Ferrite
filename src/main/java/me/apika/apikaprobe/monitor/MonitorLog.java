package me.apika.apikaprobe.monitor;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Single chokepoint for periodic monitor reports.  Every per-tick monitor
 * routes its `[bucket] avg=... max=...` lines through here so a single
 * runtime flag can silence the whole class of log noise without touching
 * one-shot bootstrap logs or command output.
 *
 * <p>Initial state: enabled, unless {@code -Dferrite.log.monitors.off=true}
 * or the JVM max heap is 3 GB or less (Pi-class hardware with slow SD-card
 * I/O should not pay ~5 log lines/sec by default; the boot stamp says which
 * default applied).  Runtime toggle via {@code /ferrite log monitors
 * on|off|status}; {@code -Dferrite.log.monitors.on=true} forces on
 * regardless of heap.
 *
 * <p>Counters and rate-limiter state in each monitor still tick normally
 * when ENABLED is false — only the LOGGER emission is suppressed, so
 * re-enabling picks up cleanly from the next 5s window.
 */
public final class MonitorLog {
	private MonitorLog() {}

	/** Heap at or below this boots with monitors off (small-server default). */
	private static final long SMALL_HEAP_BYTES = 3L * 1024 * 1024 * 1024;

	public static final boolean SMALL_HEAP =
			Runtime.getRuntime().maxMemory() <= SMALL_HEAP_BYTES;

	public static volatile boolean ENABLED = initialState();

	private static boolean initialState() {
		if (Boolean.getBoolean("ferrite.log.monitors.off")) return false;
		if (Boolean.getBoolean("ferrite.log.monitors.on")) return true;
		return !SMALL_HEAP;
	}

	private static final Logger L = LoggerFactory.getLogger("ferrite");

	public static void info(String fmt, Object... args) {
		if (ENABLED) L.info(fmt, args);
	}

	public static void info(String msg) {
		if (ENABLED) L.info(msg);
	}

	public static void warn(String fmt, Object... args) {
		if (ENABLED) L.warn(fmt, args);
	}

	public static void warn(String msg) {
		if (ENABLED) L.warn(msg);
	}
}
