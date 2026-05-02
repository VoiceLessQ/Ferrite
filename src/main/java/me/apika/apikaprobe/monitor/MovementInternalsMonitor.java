package me.apika.apikaprobe.monitor;

import java.util.concurrent.atomic.AtomicLong;

import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Splits tickMovement's internals into three measurable buckets, plus
 * a computed "other" bucket for whatever's left.
 *
 *   [0] cramming        — LivingEntity.tickCramming() — mob-vs-mob push-away
 *   [1] blockCollision  — Entity.tickBlockCollision() — block collision
 *   [2] navigator       — EntityNavigation.tick() — path step execution
 *   [3] move            — Entity.move(MovementType,Vec3d) — voxel-shape sweep
 *   [4] travel          — LivingEntity.travel(Vec3d) — velocity/input (wraps gravity+move)
 *   [5] gravity         — Entity.applyGravity() — gravity term
 *        other          — computed: movement_self − (sum of above)
 *
 * Nesting note: travel() internally calls applyGravity() and move(). This
 * means travel's number overlaps with gravity and move. When reading the
 * log, travel ≈ gravity + move + (travel's own non-delegated work); subtracting
 * all three from movement_self under-counts other. If travel is large,
 * the "net travel-only" cost = travel − gravity − move.
 *
 * Must register BEFORE MonsterPhaseMonitor so its END_SERVER_TICK
 * listener fires first and reads MonsterPhaseMonitor.getMovementSelfNs()
 * before that monitor resets its own counters.
 *
 * Same AtomicLong + ThreadLocal per-phase start + per-tick accumulator
 * + 5-second window pattern as MonsterPhaseMonitor.
 */
public final class MovementInternalsMonitor {
	private static final Logger LOGGER = LoggerFactory.getLogger("ferrite");
	private static final long REPORT_INTERVAL_NS = 5_000_000_000L;

	private static final int PHASE_CRAMMING = 0;
	private static final int PHASE_BLOCK_COLLISION = 1;
	private static final int PHASE_NAVIGATOR = 2;
	private static final int PHASE_MOVE = 3;
	private static final int PHASE_TRAVEL = 4;
	private static final int PHASE_GRAVITY = 5;
	private static final int PHASE_ADJUST_COLLISIONS = 6;
	private static final int PHASE_HAND_SWING = 7;
	private static final int PHASE_TICK_NEW_AI = 8;
	private static final int PHASE_COUNT = 9;

	private static final ThreadLocal<long[]> PHASE_START = ThreadLocal.withInitial(() -> new long[PHASE_COUNT]);
	private static final long[] THIS_TICK_NS = new long[PHASE_COUNT];

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

	private MovementInternalsMonitor() {}

	public static void register() {
		ServerTickEvents.END_SERVER_TICK.register(server -> onServerTickEnd());
	}

	// --- Phase hooks --------------------------------------------------------

	public static void onCrammingBegin() {
		PHASE_START.get()[PHASE_CRAMMING] = System.nanoTime();
	}

	public static void onCrammingEnd() {
		recordPhaseEnd(PHASE_CRAMMING);
	}

	public static void onBlockCollisionBegin() {
		PHASE_START.get()[PHASE_BLOCK_COLLISION] = System.nanoTime();
	}

	public static void onBlockCollisionEnd() {
		recordPhaseEnd(PHASE_BLOCK_COLLISION);
	}

	public static void onNavigatorBegin() {
		PHASE_START.get()[PHASE_NAVIGATOR] = System.nanoTime();
	}

	public static void onNavigatorEnd() {
		recordPhaseEnd(PHASE_NAVIGATOR);
	}

	public static void onMoveBegin() {
		PHASE_START.get()[PHASE_MOVE] = System.nanoTime();
	}

	public static void onMoveEnd() {
		recordPhaseEnd(PHASE_MOVE);
	}

	public static void onTravelBegin() {
		PHASE_START.get()[PHASE_TRAVEL] = System.nanoTime();
	}

	public static void onTravelEnd() {
		recordPhaseEnd(PHASE_TRAVEL);
	}

	public static void onGravityBegin() {
		PHASE_START.get()[PHASE_GRAVITY] = System.nanoTime();
	}

	public static void onGravityEnd() {
		recordPhaseEnd(PHASE_GRAVITY);
	}

	public static void onAdjustCollisionsBegin() {
		PHASE_START.get()[PHASE_ADJUST_COLLISIONS] = System.nanoTime();
	}

	public static void onAdjustCollisionsEnd() {
		recordPhaseEnd(PHASE_ADJUST_COLLISIONS);
	}

	public static void onHandSwingBegin() {
		PHASE_START.get()[PHASE_HAND_SWING] = System.nanoTime();
	}

	public static void onHandSwingEnd() {
		recordPhaseEnd(PHASE_HAND_SWING);
	}

	public static void onTickNewAiBegin() {
		PHASE_START.get()[PHASE_TICK_NEW_AI] = System.nanoTime();
	}

	public static void onTickNewAiEnd() {
		recordPhaseEnd(PHASE_TICK_NEW_AI);
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

		// Read movement_self from MonsterPhaseMonitor BEFORE either monitor
		// resets. Both monitors use the same 5-second interval and register
		// order guarantees this fires first.
		long movementSelfNs = MonsterPhaseMonitor.getMovementSelfNs();

		long ticks = TICK_COUNT.getAndSet(0L);
		long crammingTotal = TOTAL_NS[PHASE_CRAMMING].getAndSet(0L);
		long crammingMax = MAX_TICK_NS[PHASE_CRAMMING].getAndSet(0L);
		long collisionTotal = TOTAL_NS[PHASE_BLOCK_COLLISION].getAndSet(0L);
		long collisionMax = MAX_TICK_NS[PHASE_BLOCK_COLLISION].getAndSet(0L);
		long navTotal = TOTAL_NS[PHASE_NAVIGATOR].getAndSet(0L);
		long navMax = MAX_TICK_NS[PHASE_NAVIGATOR].getAndSet(0L);
		long moveTotal = TOTAL_NS[PHASE_MOVE].getAndSet(0L);
		long moveMax = MAX_TICK_NS[PHASE_MOVE].getAndSet(0L);
		long travelTotal = TOTAL_NS[PHASE_TRAVEL].getAndSet(0L);
		long travelMax = MAX_TICK_NS[PHASE_TRAVEL].getAndSet(0L);
		long gravityTotal = TOTAL_NS[PHASE_GRAVITY].getAndSet(0L);
		long gravityMax = MAX_TICK_NS[PHASE_GRAVITY].getAndSet(0L);
		long adjustTotal = TOTAL_NS[PHASE_ADJUST_COLLISIONS].getAndSet(0L);
		long adjustMax = MAX_TICK_NS[PHASE_ADJUST_COLLISIONS].getAndSet(0L);
		long handSwingTotal = TOTAL_NS[PHASE_HAND_SWING].getAndSet(0L);
		long handSwingMax = MAX_TICK_NS[PHASE_HAND_SWING].getAndSet(0L);
		long tickNewAiTotal = TOTAL_NS[PHASE_TICK_NEW_AI].getAndSet(0L);
		long tickNewAiMax = MAX_TICK_NS[PHASE_TICK_NEW_AI].getAndSet(0L);

		lastReportNs = now;

		if (ticks == 0L) {
			return;
		}

		// other = movement_self − (cramming + blockCollision + navigator + travel + adjustColl + handSwing)
		// move and gravity fire inside travel's probe window — already counted inside
		// travelTotal, so excluded to avoid double-deduction.
		// tickNewAi contains navigator (already counted) so it is NOT subtracted from
		// other; it is reported as an informational breakdown of the AI block.
		long accountedTotal = crammingTotal + collisionTotal + navTotal + travelTotal + adjustTotal + handSwingTotal;
		long otherTotal = Math.max(0L, movementSelfNs - accountedTotal);

		MonitorLog.info("[movement-internals] cramming: avg={} max={}  blockCollision: avg={} max={}  navigator: avg={} max={}  move: avg={} max={}  adjustColl: avg={} max={}  travel: avg={} max={}  gravity: avg={} max={}  handSwing: avg={} max={}  tickNewAi: avg={} max={}  other: avg={}  n={} ticks",
				formatMs(crammingTotal / ticks),
				formatMs(crammingMax),
				formatMs(collisionTotal / ticks),
				formatMs(collisionMax),
				formatMs(navTotal / ticks),
				formatMs(navMax),
				formatMs(moveTotal / ticks),
				formatMs(moveMax),
				formatMs(adjustTotal / ticks),
				formatMs(adjustMax),
				formatMs(travelTotal / ticks),
				formatMs(travelMax),
				formatMs(gravityTotal / ticks),
				formatMs(gravityMax),
				formatMs(handSwingTotal / ticks),
				formatMs(handSwingMax),
				formatMs(tickNewAiTotal / ticks),
				formatMs(tickNewAiMax),
				formatMs(otherTotal / ticks),
				ticks);
	}

	private static String formatMs(long nanos) {
		return String.format("%.2f", nanos / 1_000_000.0) + "ms";
	}
}
