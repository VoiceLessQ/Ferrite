package me.apika.apikaprobe.monitor;

import java.util.concurrent.atomic.AtomicLong;

import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Splits GoalSelector cost into three buckets per tick:
 *
 *   tick()        — full call: goalCleanup + goalUpdate + tickGoals(true)
 *   tickGoals(true)  — running-goal tick pass, called from inside tick()
 *   tickGoals(false) — running-goal tick pass, called directly on alternate ticks
 *
 * Probes both goalSelector and targetSelector (both are GoalSelector instances).
 *
 * Computed field:
 *   controls = tick_total - tickGoalsTrue - tickGoalsFalse
 *            = goalCleanup + goalUpdate pass cost
 *            = canStart() + shouldContinue() iteration overhead
 *
 * (Not moveControl/lookControl/jumpControl — those live in tickNewAi after
 * the selector calls. Cross-reference tickNewAi avg from MovementInternalsMonitor
 * and subtract goalSelector total + navigator to isolate controls cost.)
 */
public final class GoalSelectorMonitor {
	private static final Logger LOGGER = LoggerFactory.getLogger("ferrite");
	private static final long REPORT_INTERVAL_NS = 5_000_000_000L;

	private static final ThreadLocal<Long> TICK_START         = ThreadLocal.withInitial(() -> 0L);
	private static final ThreadLocal<Long> TICK_GOALS_T_START = ThreadLocal.withInitial(() -> 0L);
	private static final ThreadLocal<Long> TICK_GOALS_F_START = ThreadLocal.withInitial(() -> 0L);

	private static long thisTickCallCount   = 0L;
	private static long thisTickTotalNs     = 0L;
	private static long thisTickGoalsTrueNs  = 0L;
	private static long thisTickGoalsFalseNs = 0L;

	private static final AtomicLong TOTAL_CALLS        = new AtomicLong();
	private static final AtomicLong TOTAL_NS           = new AtomicLong();
	private static final AtomicLong TOTAL_GOALS_TRUE_NS  = new AtomicLong();
	private static final AtomicLong TOTAL_GOALS_FALSE_NS = new AtomicLong();
	private static final AtomicLong TICK_COUNT         = new AtomicLong();

	private static volatile long lastReportNs = System.nanoTime();

	private GoalSelectorMonitor() {}

	public static void register() {
		ServerTickEvents.END_SERVER_TICK.register(server -> onServerTickEnd());
	}

	// --- Hooks ---------------------------------------------------------------

	public static void onTickBegin() {
		TICK_START.set(System.nanoTime());
	}

	public static void onTickEnd() {
		long start = TICK_START.get();
		if (start == 0L) return;
		TICK_START.set(0L);
		thisTickCallCount++;
		thisTickTotalNs += System.nanoTime() - start;
	}

	public static void onTickGoalsBegin(boolean tickAll) {
		if (tickAll) {
			TICK_GOALS_T_START.set(System.nanoTime());
		} else {
			TICK_GOALS_F_START.set(System.nanoTime());
		}
	}

	public static void onTickGoalsEnd(boolean tickAll) {
		if (tickAll) {
			long start = TICK_GOALS_T_START.get();
			if (start == 0L) return;
			TICK_GOALS_T_START.set(0L);
			thisTickGoalsTrueNs += System.nanoTime() - start;
		} else {
			long start = TICK_GOALS_F_START.get();
			if (start == 0L) return;
			TICK_GOALS_F_START.set(0L);
			thisTickGoalsFalseNs += System.nanoTime() - start;
		}
	}

	// --- Internals -----------------------------------------------------------

	private static void onServerTickEnd() {
		if (thisTickCallCount > 0L) {
			TOTAL_CALLS.addAndGet(thisTickCallCount);
			TOTAL_NS.addAndGet(thisTickTotalNs);
			TOTAL_GOALS_TRUE_NS.addAndGet(thisTickGoalsTrueNs);
			TOTAL_GOALS_FALSE_NS.addAndGet(thisTickGoalsFalseNs);
		}
		thisTickCallCount    = 0L;
		thisTickTotalNs      = 0L;
		thisTickGoalsTrueNs  = 0L;
		thisTickGoalsFalseNs = 0L;
		TICK_COUNT.incrementAndGet();
		maybeReport();
	}

	private static void maybeReport() {
		long now = System.nanoTime();
		if (now - lastReportNs < REPORT_INTERVAL_NS) return;

		long ticks       = TICK_COUNT.getAndSet(0L);
		long calls       = TOTAL_CALLS.getAndSet(0L);
		long totalNs     = TOTAL_NS.getAndSet(0L);
		long trueNs      = TOTAL_GOALS_TRUE_NS.getAndSet(0L);
		long falseNs     = TOTAL_GOALS_FALSE_NS.getAndSet(0L);
		lastReportNs = now;

		if (ticks == 0L || calls == 0L) return;

		double callsPerTick   = (double) calls / ticks;
		double totalMsPerTick = totalNs / 1_000_000.0 / ticks;
		double avgUsPerCall   = totalNs / 1_000.0 / calls;
		double trueMsPerTick  = trueNs  / 1_000_000.0 / ticks;
		double falseMsPerTick = falseNs / 1_000_000.0 / ticks;
		// controls = goalCleanup + goalUpdate passes (canStart/shouldContinue iteration)
		// = tick() total minus the tickGoals portions it contains
		double controlsMsPerTick = totalMsPerTick - trueMsPerTick - falseMsPerTick;

		LOGGER.info(
			"[goal-selector] calls={}/tick total={}ms avg={}us/call  tickGoals(true)={}ms/tick tickGoals(false)={}ms/tick  controls={}ms/tick  n={} ticks",
			String.format("%.1f", callsPerTick),
			String.format("%.3f", totalMsPerTick),
			String.format("%.2f", avgUsPerCall),
			String.format("%.3f", trueMsPerTick),
			String.format("%.3f", falseMsPerTick),
			String.format("%.3f", controlsMsPerTick),
			ticks
		);
	}
}
