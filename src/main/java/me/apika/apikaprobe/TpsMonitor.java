package me.apika.apikaprobe;

import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Measures real server-tick DURATION (time spent inside one tick) and logs
 * avg / max / TPS every 5 seconds.
 *
 * Uses START_SERVER_TICK + END_SERVER_TICK to get the true cost of each
 * tick — not the interval between ticks, which on a healthy server is
 * always ~50ms regardless of actual work done.
 *
 * Server tick events fire on the server thread only, so all fields here
 * are effectively single-threaded and need no synchronization.
 */
public final class TpsMonitor {
	private static final Logger LOGGER = LoggerFactory.getLogger("rusty");

	private static final long REPORT_INTERVAL_NS = 5_000_000_000L; // 5 seconds

	private static long tickStartNanos = 0L;
	private static long tickCount = 0L;
	private static long totalNanos = 0L;
	private static long maxNanos = 0L;
	private static long lastReportNanos = System.nanoTime();

	private TpsMonitor() {}

	public static void register() {
		ServerTickEvents.START_SERVER_TICK.register(server -> tickStartNanos = System.nanoTime());
		ServerTickEvents.END_SERVER_TICK.register(server -> onTickEnd());
	}

	private static void onTickEnd() {
		long now = System.nanoTime();

		if (tickStartNanos > 0L) {
			long duration = now - tickStartNanos;
			tickCount++;
			totalNanos += duration;
			if (duration > maxNanos) {
				maxNanos = duration;
			}
		}

		if (now - lastReportNanos >= REPORT_INTERVAL_NS && tickCount > 0L) {
			double avgMs = (totalNanos / (double) tickCount) / 1_000_000.0;
			double maxMs = maxNanos / 1_000_000.0;
			// MC targets 20 TPS (50ms budget). If avg duration fits, TPS stays 20;
			// if it overruns, TPS drops proportionally.
			double tps = Math.min(20.0, 1000.0 / avgMs);

			LOGGER.info("[mspt] avg={} ms  max={} ms  tps={}  samples={}",
					String.format("%.2f", avgMs),
					String.format("%.2f", maxMs),
					String.format("%.2f", tps),
					tickCount);

			tickCount = 0L;
			totalNanos = 0L;
			maxNanos = 0L;
			lastReportNanos = now;
		}
	}
}
