package me.apika.apikaprobe.monitor;

import java.util.concurrent.atomic.AtomicLong;

import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.util.math.BlockPos;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class HopperHintMonitor {
	private static final Logger LOGGER = LoggerFactory.getLogger("ferrite");
	private static final long REPORT_INTERVAL_NS = 5_000_000_000L;

	public static volatile boolean USE_HINT = Boolean.parseBoolean(
		System.getProperty("ferrite.hopper.extract.useHint", "true"));
	public static volatile boolean VALIDATE = Boolean.parseBoolean(
		System.getProperty("ferrite.hopper.extract.validate", "false"));

	private static final AtomicLong HINT_HITS         = new AtomicLong();
	private static final AtomicLong HINT_WRAPPED      = new AtomicLong();
	private static final AtomicLong HINT_FAILED       = new AtomicLong();
	private static final AtomicLong HINT_NO_INV       = new AtomicLong();
	private static final AtomicLong HINT_TOTAL_START  = new AtomicLong();
	private static final AtomicLong HINT_TOTAL_NS     = new AtomicLong();

	private static final AtomicLong VAL_TOTAL         = new AtomicLong();
	private static final AtomicLong VAL_STALE         = new AtomicLong();
	private static final AtomicLong VAL_NO_INV        = new AtomicLong();
	private static final AtomicLong VAL_UNSUPPORTED   = new AtomicLong();
	private static final AtomicLong VAL_LOG_BUDGET    = new AtomicLong(20L);

	private static volatile long lastReportNs = System.nanoTime();

	private HopperHintMonitor() {}

	public static void register() {
		ServerTickEvents.END_SERVER_TICK.register(server -> maybeReport());
		MonitorLog.info("[hopper-hint] init useHint={} validate={}", USE_HINT, VALIDATE);
	}

	public static void onHit(int hintAt, long elapsedNs) {
		HINT_HITS.incrementAndGet();
		HINT_TOTAL_START.addAndGet(hintAt);
		HINT_TOTAL_NS.addAndGet(elapsedNs);
	}

	public static void onWrap(long elapsedNs) {
		HINT_WRAPPED.incrementAndGet();
		HINT_TOTAL_NS.addAndGet(elapsedNs);
	}

	public static void onAllFail(long elapsedNs) {
		HINT_FAILED.incrementAndGet();
		HINT_TOTAL_NS.addAndGet(elapsedNs);
	}

	public static void onNoInventory() {
		HINT_NO_INV.incrementAndGet();
	}

	public static void onValidationNoInv()      { VAL_NO_INV.incrementAndGet(); }
	public static void onValidationUnsupported(){ VAL_UNSUPPORTED.incrementAndGet(); }

	public static void onValidation(boolean stale, BlockPos pos, int hint, int firstNonEmpty) {
		VAL_TOTAL.incrementAndGet();
		if (stale) {
			VAL_STALE.incrementAndGet();
			if (VAL_LOG_BUDGET.getAndDecrement() > 0) {
				MonitorLog.warn(
					"[hopper-hint] STALE hint at {} hint={} but firstNonEmpty={} (slots [0..{}) non-empty)",
					pos, hint, firstNonEmpty, hint
				);
			}
		}
	}

	private static void maybeReport() {
		long now = System.nanoTime();
		if (now - lastReportNs < REPORT_INTERVAL_NS) return;

		long hits      = HINT_HITS.getAndSet(0);
		long wrapped   = HINT_WRAPPED.getAndSet(0);
		long failed    = HINT_FAILED.getAndSet(0);
		long noInv     = HINT_NO_INV.getAndSet(0);
		long startSum  = HINT_TOTAL_START.getAndSet(0);
		long totalNs   = HINT_TOTAL_NS.getAndSet(0);

		long valTotal  = VAL_TOTAL.getAndSet(0);
		long valStale  = VAL_STALE.getAndSet(0);
		long valNoInv  = VAL_NO_INV.getAndSet(0);
		long valUnsup  = VAL_UNSUPPORTED.getAndSet(0);

		boolean hadHint = USE_HINT && (hits + wrapped + failed) > 0;
		boolean hadVal  = VALIDATE && (valTotal + valNoInv + valUnsup) > 0;
		if (!hadHint && !hadVal) return;
		lastReportNs = now;

		if (hadHint) {
			long calls = hits + wrapped + failed;
			double avgStartIdx = hits > 0 ? (double) startSum / hits : 0.0;
			double usPerCall   = totalNs / 1_000.0 / Math.max(1L, calls);
			MonitorLog.info(
				"[hopper-hint] hint  calls={} hit={} wrap={} fail={} noInv={} avgStartIdx={} usPerCall={}",
				calls, hits, wrapped, failed, noInv,
				String.format("%.2f", avgStartIdx),
				String.format("%.2f", usPerCall)
			);
		}
		if (hadVal) {
			MonitorLog.info(
				"[hopper-hint] valid total={} stale={} ({}%) noInv={} unsupported={}",
				valTotal, valStale,
				String.format("%.3f", 100.0 * valStale / Math.max(1L, valTotal)),
				valNoInv, valUnsup
			);
		}
	}
}
