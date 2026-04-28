package me.apika.apikaprobe.monitor;

import java.util.concurrent.atomic.AtomicLong;

import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Metrics for [PreChunkDispatcher]. Every 5s logs:
 *   vd              : current server player view distance (chunks)
 *   submitted       : tickets we queued this window (post-dedupe)
 *   dedupe-skipped  : predictions throttled by our own LAST_SUBMIT
 *                     rate-limiter. Healthy in steady travel — the same
 *                     chunk stays the target for many ticks as the player
 *                     approaches. Vanilla dedupes internally too, so
 *                     submitting anyway would be safe; this is just local
 *                     hygiene.
 *   loaded          : tickets that resolved to FULL in the window
 *   avg/max lead    : ticks between our submission and the future completing.
 *                     Low (single digits) = the chunk was already at or near
 *                     FULL when we asked → we're not adding value. High =
 *                     the chunk needed real work → our prediction bought
 *                     real headroom. Useful window is roughly
 *                     lookahead_min < avg-lead < lookahead_max.
 *
 * Completion callbacks fire on the server thread (addChunkLoadingTicket's
 * future completes there), but AtomicLongs make that thread-agnostic.
 */
public final class PreChunkMonitor {
	private static final Logger LOGGER = LoggerFactory.getLogger("ferrite");
	private static final long REPORT_INTERVAL_NS = 5_000_000_000L;

	private static final AtomicLong SUBMITTED = new AtomicLong();
	private static final AtomicLong DEDUPE_SKIPPED = new AtomicLong();
	private static final AtomicLong LOADED = new AtomicLong();
	private static final AtomicLong LEAD_SUM_TICKS = new AtomicLong();
	private static final AtomicLong LEAD_MAX_TICKS = new AtomicLong();

	// Snapshot of the most recent server view distance, for the report line.
	// Written every tick by the dispatcher, read once per 5s window.
	private static volatile int lastViewDistance = -1;

	private static volatile long lastReportNs = System.nanoTime();

	private PreChunkMonitor() {}

	public static void register() {
		ServerTickEvents.END_SERVER_TICK.register(server -> maybeReport());
	}

	public static void recordViewDistance(int vd) {
		lastViewDistance = vd;
	}

	public static void onSubmit() {
		SUBMITTED.incrementAndGet();
	}

	public static void onDedupeSkipped() {
		DEDUPE_SKIPPED.incrementAndGet();
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
		long dedupeSkipped = DEDUPE_SKIPPED.getAndSet(0L);
		long loaded = LOADED.getAndSet(0L);
		long leadSum = LEAD_SUM_TICKS.getAndSet(0L);
		long leadMax = LEAD_MAX_TICKS.getAndSet(0L);
		lastReportNs = now;

		if (submitted == 0L && dedupeSkipped == 0L && loaded == 0L) return;

		String avgLead = loaded == 0L ? "-" : String.valueOf(leadSum / loaded);
		LOGGER.info("[prechunk] vd={} submitted={} dedupe-skipped={} loaded={} avg-lead={}t max-lead={}t",
				lastViewDistance, submitted, dedupeSkipped, loaded, avgLead, leadMax);
	}
}
