package me.apika.apikaprobe.monitor;

import java.util.concurrent.atomic.AtomicLong;

import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class HopperSlotMonitor {
	private static final Logger LOGGER = LoggerFactory.getLogger("ferrite");
	private static final long REPORT_INTERVAL_NS = 5_000_000_000L;

	private static final ThreadLocal<int[]>  INSERT_ATTEMPTS  = ThreadLocal.withInitial(() -> new int[]{0});
	private static final ThreadLocal<long[]> INSERT_START_NS  = ThreadLocal.withInitial(() -> new long[]{0L});
	private static final ThreadLocal<int[]>  EXTRACT_ATTEMPTS = ThreadLocal.withInitial(() -> new int[]{0});
	private static final ThreadLocal<long[]> EXTRACT_START_NS = ThreadLocal.withInitial(() -> new long[]{0L});

	private static final AtomicLong INSERT_CALLS         = new AtomicLong();
	private static final AtomicLong INSERT_TOTAL_ATT     = new AtomicLong();
	private static final AtomicLong INSERT_SUCC_AT_0     = new AtomicLong();
	private static final AtomicLong INSERT_SUCC_AFTER    = new AtomicLong();
	private static final AtomicLong INSERT_WASTED_ATT    = new AtomicLong();
	private static final AtomicLong INSERT_FAIL_CALLS    = new AtomicLong();
	private static final AtomicLong INSERT_FAIL_ATT      = new AtomicLong();
	private static final AtomicLong INSERT_TOTAL_NS      = new AtomicLong();

	private static final AtomicLong EXTRACT_CALLS        = new AtomicLong();
	private static final AtomicLong EXTRACT_TOTAL_ATT    = new AtomicLong();
	private static final AtomicLong EXTRACT_SUCC_AT_0    = new AtomicLong();
	private static final AtomicLong EXTRACT_SUCC_AFTER   = new AtomicLong();
	private static final AtomicLong EXTRACT_WASTED_ATT   = new AtomicLong();
	private static final AtomicLong EXTRACT_FAIL_CALLS   = new AtomicLong();
	private static final AtomicLong EXTRACT_FAIL_ATT     = new AtomicLong();
	private static final AtomicLong EXTRACT_TOTAL_NS     = new AtomicLong();

	private static volatile long lastReportNs = System.nanoTime();

	private HopperSlotMonitor() {}

	public static void register() {
		ServerTickEvents.END_SERVER_TICK.register(server -> maybeReport());
	}

	public static void onInsertBegin() {
		INSERT_ATTEMPTS.get()[0] = 0;
		INSERT_START_NS.get()[0] = System.nanoTime();
	}
	public static void onInsertAttempt() { INSERT_ATTEMPTS.get()[0]++; }

	public static void onInsertEnd(boolean success) {
		int n = INSERT_ATTEMPTS.get()[0];
		if (n == 0) return;
		long elapsed = System.nanoTime() - INSERT_START_NS.get()[0];
		INSERT_CALLS.incrementAndGet();
		INSERT_TOTAL_ATT.addAndGet(n);
		INSERT_TOTAL_NS.addAndGet(elapsed);
		if (success) {
			if (n == 1) {
				INSERT_SUCC_AT_0.incrementAndGet();
			} else {
				INSERT_SUCC_AFTER.incrementAndGet();
				INSERT_WASTED_ATT.addAndGet(n - 1);
			}
		} else {
			INSERT_FAIL_CALLS.incrementAndGet();
			INSERT_FAIL_ATT.addAndGet(n);
		}
	}

	public static void onExtractBegin() {
		EXTRACT_ATTEMPTS.get()[0] = 0;
		EXTRACT_START_NS.get()[0] = System.nanoTime();
	}
	public static void onExtractAttempt() { EXTRACT_ATTEMPTS.get()[0]++; }

	public static void onExtractEnd(boolean success) {
		int n = EXTRACT_ATTEMPTS.get()[0];
		if (n == 0) return;
		long elapsed = System.nanoTime() - EXTRACT_START_NS.get()[0];
		EXTRACT_CALLS.incrementAndGet();
		EXTRACT_TOTAL_ATT.addAndGet(n);
		EXTRACT_TOTAL_NS.addAndGet(elapsed);
		if (success) {
			if (n == 1) {
				EXTRACT_SUCC_AT_0.incrementAndGet();
			} else {
				EXTRACT_SUCC_AFTER.incrementAndGet();
				EXTRACT_WASTED_ATT.addAndGet(n - 1);
			}
		} else {
			EXTRACT_FAIL_CALLS.incrementAndGet();
			EXTRACT_FAIL_ATT.addAndGet(n);
		}
	}

	private static void maybeReport() {
		long now = System.nanoTime();
		if (now - lastReportNs < REPORT_INTERVAL_NS) return;

		long iCalls   = INSERT_CALLS.getAndSet(0);
		long iAtt     = INSERT_TOTAL_ATT.getAndSet(0);
		long iAt0     = INSERT_SUCC_AT_0.getAndSet(0);
		long iAfter   = INSERT_SUCC_AFTER.getAndSet(0);
		long iWasted  = INSERT_WASTED_ATT.getAndSet(0);
		long iFCalls  = INSERT_FAIL_CALLS.getAndSet(0);
		long iFAtt    = INSERT_FAIL_ATT.getAndSet(0);
		long iNs      = INSERT_TOTAL_NS.getAndSet(0);

		long eCalls   = EXTRACT_CALLS.getAndSet(0);
		long eAtt     = EXTRACT_TOTAL_ATT.getAndSet(0);
		long eAt0     = EXTRACT_SUCC_AT_0.getAndSet(0);
		long eAfter   = EXTRACT_SUCC_AFTER.getAndSet(0);
		long eWasted  = EXTRACT_WASTED_ATT.getAndSet(0);
		long eFCalls  = EXTRACT_FAIL_CALLS.getAndSet(0);
		long eFAtt    = EXTRACT_FAIL_ATT.getAndSet(0);
		long eNs      = EXTRACT_TOTAL_NS.getAndSet(0);

		if (iCalls == 0L && eCalls == 0L) return;
		lastReportNs = now;

		if (iCalls > 0L) {
			double usPerCall    = iNs / 1_000.0 / iCalls;
			double nsPerAtt     = iAtt > 0L ? (double) iNs / iAtt : 0.0;
			LOGGER.info(
				"[hopper-slot] insert  calls={} succ@0={} succ>0={} fail={} avgAtt={} wasted={} ({}/call) failAtt={} usPerCall={} nsPerAtt={}",
				iCalls, iAt0, iAfter, iFCalls,
				String.format("%.2f", (double) iAtt / iCalls),
				iWasted,
				String.format("%.2f", (double) iWasted / iCalls),
				iFAtt,
				String.format("%.2f", usPerCall),
				String.format("%.0f", nsPerAtt)
			);
		}
		if (eCalls > 0L) {
			double usPerCall    = eNs / 1_000.0 / eCalls;
			double nsPerAtt     = eAtt > 0L ? (double) eNs / eAtt : 0.0;
			LOGGER.info(
				"[hopper-slot] extract calls={} succ@0={} succ>0={} fail={} avgAtt={} wasted={} ({}/call) failAtt={} usPerCall={} nsPerAtt={}",
				eCalls, eAt0, eAfter, eFCalls,
				String.format("%.2f", (double) eAtt / eCalls),
				eWasted,
				String.format("%.2f", (double) eWasted / eCalls),
				eFAtt,
				String.format("%.2f", usPerCall),
				String.format("%.0f", nsPerAtt)
			);
		}
	}
}
