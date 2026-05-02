package me.apika.apikaprobe.monitor;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Single chokepoint for periodic monitor reports.  Every per-tick monitor
 * routes its `[bucket] avg=... max=...` lines through here so a single
 * runtime flag can silence the whole class of log noise without touching
 * one-shot bootstrap logs or command output.
 *
 * <p>Initial state from {@code -Dferrite.log.monitors.off=true} (default
 * enabled).  Runtime toggle via {@code /ferrite log monitors on|off|status}.
 *
 * <p>Counters and rate-limiter state in each monitor still tick normally
 * when ENABLED is false — only the LOGGER emission is suppressed, so
 * re-enabling picks up cleanly from the next 5s window.
 */
public final class MonitorLog {
	private MonitorLog() {}

	public static volatile boolean ENABLED =
			!Boolean.getBoolean("ferrite.log.monitors.off");

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
