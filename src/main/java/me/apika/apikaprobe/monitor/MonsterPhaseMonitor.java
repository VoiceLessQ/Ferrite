package me.apika.apikaprobe.monitor;

import java.util.concurrent.atomic.AtomicLong;

import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Splits per-mob tick cost into three phases:
 *
 *   [0] baseTick    — Entity.baseTick() — common base (air, fire, freeze)
 *   [1] movement    — LivingEntity.tickMovement() — physics + mob AI
 *                     (this measurement INCLUDES mobTick because vanilla
 *                     calls mobTick from inside tickMovement's body)
 *   [2] mobTick     — MobEntity.mobTick(ServerWorld) — AI goals / pathfinding
 *
 * Because tickMovement nests mobTick, the three numbers don't sum to
 * total. The report therefore also computes a "self" field:
 *     movement self = movement_total − mobTick_total
 * …which is pure physics cost, excluding AI.
 *
 * Fires for ALL MobEntity instances (monsters and creatures). In our
 * stress-test scenarios monsters dominate 88%/11%, so the numbers are
 * effectively monster-phase anyway.
 *
 * Server tick is single-threaded; window accumulators use AtomicLong
 * for consistency with the other monitors.
 */
public final class MonsterPhaseMonitor {
	private static final Logger LOGGER = LoggerFactory.getLogger("ferrite");
	private static final long REPORT_INTERVAL_NS = 5_000_000_000L;

	private static final int PHASE_BASE = 0;
	private static final int PHASE_MOVEMENT = 1;
	private static final int PHASE_MOBTICK = 2;
	private static final int PHASE_COUNT = 3;

	// Per-thread start timestamps, one per phase.
	private static final ThreadLocal<long[]> PHASE_START = ThreadLocal.withInitial(() -> new long[PHASE_COUNT]);

	// Per-tick running sums per phase (server-thread owned, reset each tick).
	private static final long[] THIS_TICK_NS = new long[PHASE_COUNT];

	// Window accumulators per phase.
	private static final AtomicLong[] TOTAL_NS = new AtomicLong[PHASE_COUNT];
	private static final AtomicLong[] MAX_TICK_NS = new AtomicLong[PHASE_COUNT];
	private static final AtomicLong TICK_COUNT = new AtomicLong();

	static {
		for (int i = 0; i < PHASE_COUNT; i++) {
			TOTAL_NS[i] = new AtomicLong();
			MAX_TICK_NS[i] = new AtomicLong();
		}
	}

	private static volatile long lastReportNs = System.nanoTime();

	private MonsterPhaseMonitor() {}

	public static void register() {
		ServerTickEvents.END_SERVER_TICK.register(server -> onServerTickEnd());
	}

	// --- Phase hooks --------------------------------------------------------

	public static void onBaseTickBegin() {
		PHASE_START.get()[PHASE_BASE] = System.nanoTime();
	}

	public static void onBaseTickEnd() {
		recordPhaseEnd(PHASE_BASE);
	}

	public static void onMovementBegin() {
		PHASE_START.get()[PHASE_MOVEMENT] = System.nanoTime();
	}

	public static void onMovementEnd() {
		recordPhaseEnd(PHASE_MOVEMENT);
	}

	public static void onMobTickBegin() {
		PHASE_START.get()[PHASE_MOBTICK] = System.nanoTime();
	}

	public static void onMobTickEnd() {
		recordPhaseEnd(PHASE_MOBTICK);
	}

	/**
	 * Live read of movement-self cost for cross-monitor use
	 * (MovementInternalsMonitor reads this at report time to compute
	 * the "other" bucket = movement_self − sum(its own phases)).
	 *
	 * Must be called BEFORE this monitor's own report-and-reset fires
	 * this tick. Registration order in ExampleMod ensures that.
	 */
	public static long getMovementSelfNs() {
		return Math.max(0L, TOTAL_NS[PHASE_MOVEMENT].get() - TOTAL_NS[PHASE_MOBTICK].get());
	}

	// --- Internals ----------------------------------------------------------

	private static void recordPhaseEnd(int phase) {
		long[] starts = PHASE_START.get();
		long start = starts[phase];
		if (start == 0L) {
			return;
		}
		starts[phase] = 0L;
		long duration = System.nanoTime() - start;
		THIS_TICK_NS[phase] += duration;
	}

	private static void onServerTickEnd() {
		for (int i = 0; i < PHASE_COUNT; i++) {
			long thisTick = THIS_TICK_NS[i];
			if (thisTick > 0L) {
				TOTAL_NS[i].addAndGet(thisTick);
				final long snapshot = thisTick;
				MAX_TICK_NS[i].updateAndGet(prev -> Math.max(prev, snapshot));
			}
			THIS_TICK_NS[i] = 0L;
		}
		TICK_COUNT.incrementAndGet();
		maybeReport();
	}

	private static void maybeReport() {
		long now = System.nanoTime();
		if (now - lastReportNs < REPORT_INTERVAL_NS) {
			return;
		}

		long ticks = TICK_COUNT.getAndSet(0L);
		long baseTotal = TOTAL_NS[PHASE_BASE].getAndSet(0L);
		long baseMax = MAX_TICK_NS[PHASE_BASE].getAndSet(0L);
		long moveTotal = TOTAL_NS[PHASE_MOVEMENT].getAndSet(0L);
		long moveMax = MAX_TICK_NS[PHASE_MOVEMENT].getAndSet(0L);
		long aiTotal = TOTAL_NS[PHASE_MOBTICK].getAndSet(0L);
		long aiMax = MAX_TICK_NS[PHASE_MOBTICK].getAndSet(0L);

		lastReportNs = now;

		if (ticks == 0L) {
			return;
		}

		// Computed: pure-physics cost = tickMovement total minus mobTick total.
		// Per-tick max-self isn't meaningful (the max-movement tick may differ
		// from the max-mobTick tick), so we only report self as an avg.
		long moveSelfTotal = Math.max(0L, moveTotal - aiTotal);

		MonitorLog.info("[monster-phase] baseTick: avg={} max={}  movement: avg={} max={} (self={})  mobTick: avg={} max={}  n={} ticks",
				formatMs(baseTotal / ticks),
				formatMs(baseMax),
				formatMs(moveTotal / ticks),
				formatMs(moveMax),
				formatMs(moveSelfTotal / ticks),
				formatMs(aiTotal / ticks),
				formatMs(aiMax),
				ticks);
	}

	private static String formatMs(long nanos) {
		return String.format("%.2f", nanos / 1_000_000.0) + "ms";
	}
}
