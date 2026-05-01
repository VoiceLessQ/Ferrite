package me.apika.apikaprobe.monitor;

import java.util.concurrent.atomic.AtomicLong;

import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Sampled timer for Aquifer.Impl.apply(NoisePos, double).
 *
 * apply is called once per block during sampleBlockState — ~98K times
 * per chunk, ~29M per 5-second window on active flight. Full timing
 * would add measurement overhead on the same order as what we're
 * trying to measure.
 *
 * Strategy: every call increments a raw counter (AtomicLong, atomic
 * fetch-and-add ~10ns). Only every Nth call (N = 100) sets the
 * ThreadLocal start time and records on RETURN. That keeps the hot
 * path cost at ~10ns per call while still producing ~290K timed
 * samples per 5s window — plenty of statistical signal.
 *
 * At report time we read ChunkGenMonitor's live sync-noise count to
 * compute ms/chunk extrapolated from the sample average.
 *
 * Register BEFORE ChunkGenMonitor so this fires first and reads
 * sync-noise counters before they reset.
 */
public final class AquiferMonitor {
	private static final Logger LOGGER = LoggerFactory.getLogger("ferrite");

	/** Default OFF — JFR profile (2026-04-28) showed the per-block
	 *  AquiferMixin onApplyBegin/End mixin pair contributing ~3-5 ms/chunk
	 *  of overhead to chunkgen workers. Pure observation cost. The
	 *  AquiferMixin checks this flag at @Inject HEAD and returns
	 *  immediately when off, skipping the call into onApplyBegin/End
	 *  entirely. Flip to true only when actively measuring aquifer
	 *  per-block cost. */
	public static volatile boolean ENABLED = false;

	private static final long REPORT_INTERVAL_NS = 5_000_000_000L;
	private static final int SAMPLE_EVERY = 100;

	private static final ThreadLocal<Long> APPLY_TS = ThreadLocal.withInitial(() -> 0L);

	// Raw number of apply() invocations — all of them, counted.
	private static final AtomicLong CALL_COUNT = new AtomicLong();
	// Number of invocations that were actually timed (CALL_COUNT / SAMPLE_EVERY,
	// give or take rounding across threads).
	private static final AtomicLong SAMPLED_COUNT = new AtomicLong();
	private static final AtomicLong SAMPLED_TOTAL_NS = new AtomicLong();
	private static final AtomicLong SAMPLED_MAX_NS = new AtomicLong();

	private static volatile long lastReportNs = System.nanoTime();

	private AquiferMonitor() {}

	public static void register() {
		ServerTickEvents.END_SERVER_TICK.register(server -> maybeReport());
	}

	/**
	 * Called from @Inject HEAD on every apply() invocation.
	 * Only every SAMPLE_EVERY-th call will actually start a timer.
	 */
	public static void onApplyBegin() {
		if (!ENABLED) return;
		long n = CALL_COUNT.getAndIncrement();
		if (n % SAMPLE_EVERY == 0) {
			APPLY_TS.set(System.nanoTime());
		}
	}

	/**
	 * Called from @Inject RETURN. If this thread has a start time set
	 * (i.e. HEAD on this thread hit a sampled call), record the duration.
	 * Otherwise a no-op.
	 */
	public static void onApplyEnd() {
		if (!ENABLED) return;
		long start = APPLY_TS.get();
		if (start == 0L) {
			return;
		}
		APPLY_TS.set(0L);
		long duration = System.nanoTime() - start;
		SAMPLED_COUNT.incrementAndGet();
		SAMPLED_TOTAL_NS.addAndGet(duration);
		SAMPLED_MAX_NS.updateAndGet(prev -> Math.max(prev, duration));
	}

	private static void maybeReport() {
		long now = System.nanoTime();
		if (now - lastReportNs < REPORT_INTERVAL_NS) {
			return;
		}

		long calls = CALL_COUNT.getAndSet(0L);
		long sampled = SAMPLED_COUNT.getAndSet(0L);
		long total = SAMPLED_TOTAL_NS.getAndSet(0L);
		long max = SAMPLED_MAX_NS.getAndSet(0L);

		// Live-read chunk count from ChunkGenMonitor (pre-reset — we register first).
		long chunks = ChunkGenMonitor.getSyncNoiseCount();

		lastReportNs = now;

		if (calls == 0L || sampled == 0L) {
			return;
		}

		double avgNs = total / (double) sampled;
		double maxMs = max / 1_000_000.0;
		// Extrapolate: avg per call × calls in window = total ns spent in apply
		// across all threads; divide by chunk count for per-chunk cost.
		double extrapolatedMsPerChunk = chunks == 0L
				? 0.0
				: (avgNs * calls) / chunks / 1_000_000.0;

		LOGGER.info("[aquifer] calls={} sampled={} avg={} ns max={} ms  extrapolated={} ms/chunk",
				calls,
				sampled,
				String.format("%.0f", avgNs),
				String.format("%.2f", maxMs),
				String.format("%.2f", extrapolatedMsPerChunk));
	}
}
