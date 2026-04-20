package me.apika.apikaprobe;

import java.util.concurrent.atomic.AtomicLong;

import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Metrics for [PreChunkDispatcher]. Every 5s logs:
 *   submitted       : tickets we queued this window
 *   already-loaded  : predictions that vanilla had already satisfied (ok —
 *                     means our predictor is aimed right, vanilla was just
 *                     faster; high counts suggest we can shrink lookahead)
 *   loaded          : tickets that resolved to FULL in the window
 *   avg/max lead    : ticks between our submission and the future completing
 *                     — higher = more headroom before player arrives. If this
 *                     is > LOOKAHEAD_TICKS, we're not actually beating vanilla
 *                     for that chunk (player would have already arrived).
 *
 * Completion callbacks fire on the server thread (addChunkLoadingTicket's
 * future completes there), but AtomicLongs make that thread-agnostic.
 */
public final class PreChunkMonitor {
	private static final Logger LOGGER = LoggerFactory.getLogger("ferrite");
	private static final long REPORT_INTERVAL_NS = 5_000_000_000L;

	private static final AtomicLong SUBMITTED = new AtomicLong();
	private static final AtomicLong ALREADY_LOADED = new AtomicLong();
	private static final AtomicLong LOADED = new AtomicLong();
	private static final AtomicLong LEAD_SUM_TICKS = new AtomicLong();
	private static final AtomicLong LEAD_MAX_TICKS = new AtomicLong();

	private static volatile long lastReportNs = System.nanoTime();

	private PreChunkMonitor() {}

	public static void register() {
		ServerTickEvents.END_SERVER_TICK.register(server -> maybeReport());
	}

	public static void onSubmit() {
		SUBMITTED.incrementAndGet();
	}

	public static void onAlreadyLoaded() {
		ALREADY_LOADED.incrementAndGet();
	}

	public static void onLoaded(long leadTicks) {
		LOADED.incrementAndGet();
		LEAD_SUM_TICKS.addAndGet(leadTicks);
		LEAD_MAX_TICKS.updateAndGet(prev -> Math.max(prev, leadTicks));
	}

	private static void maybeReport() {
		long now = System.nanoTime();
		if (now - lastReportNs < REPORT_INTERVAL_NS) return;

		long submitted = SUBMITTED.getAndSet(0L);
		long alreadyLoaded = ALREADY_LOADED.getAndSet(0L);
		long loaded = LOADED.getAndSet(0L);
		long leadSum = LEAD_SUM_TICKS.getAndSet(0L);
		long leadMax = LEAD_MAX_TICKS.getAndSet(0L);
		lastReportNs = now;

		if (submitted == 0L && alreadyLoaded == 0L && loaded == 0L) return;

		String avgLead = loaded == 0L ? "-" : String.valueOf(leadSum / loaded);
		LOGGER.info("[prechunk] submitted={} already-loaded={} loaded={} avg-lead={}t max-lead={}t",
				submitted, alreadyLoaded, loaded, avgLead, leadMax);
	}
}
