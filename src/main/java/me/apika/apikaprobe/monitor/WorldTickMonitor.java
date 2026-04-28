package me.apika.apikaprobe.monitor;

import java.util.concurrent.atomic.AtomicLong;

import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Server-side tick-cost monitor.
 *
 *   blockentities : time inside World.tickBlockEntities (called once per
 *                   world tick, iterates internally). Measured per-call,
 *                   one sample per tick.
 *   entities      : time inside ServerWorld.tickEntity (called per entity
 *                   per tick). Individual call durations are summed into
 *                   a per-tick running total, pushed to the window at
 *                   END_SERVER_TICK so the reported "entities" average is
 *                   ms-per-tick, not ms-per-entity.
 *
 * Server tick runs single-threaded, but accumulators use AtomicLong to
 * match the pattern used by ChunkGenMonitor and stay safe if MC ever
 * parallelizes.
 */
public final class WorldTickMonitor {
	private static final Logger LOGGER = LoggerFactory.getLogger("ferrite");

	private static final long REPORT_INTERVAL_NS = 5_000_000_000L;

	private static final ThreadLocal<Long> BLOCK_ENTITY_START = ThreadLocal.withInitial(() -> 0L);
	private static final ThreadLocal<Long> ENTITY_START = ThreadLocal.withInitial(() -> 0L);

	// Per-tick running sum of entity durations. Reset on END_SERVER_TICK,
	// then pushed to the window accumulators as one sample.
	private static final AtomicLong ENTITY_THIS_TICK_NS = new AtomicLong();

	// 5-second window accumulators.
	private static final AtomicLong TICK_COUNT = new AtomicLong();
	private static final AtomicLong BE_TOTAL_NS = new AtomicLong();
	private static final AtomicLong BE_MAX_NS = new AtomicLong();
	private static final AtomicLong ENT_TOTAL_NS = new AtomicLong();
	private static final AtomicLong ENT_MAX_NS = new AtomicLong();

	private static volatile long lastReportNs = System.nanoTime();

	private WorldTickMonitor() {}

	public static void register() {
		ServerTickEvents.END_SERVER_TICK.register(server -> onServerTickEnd());
	}

	// --- Phase hooks --------------------------------------------------------

	public static void onBlockEntitiesBegin() {
		BLOCK_ENTITY_START.set(System.nanoTime());
	}

	public static void onBlockEntitiesEnd() {
		long start = BLOCK_ENTITY_START.get();
		if (start == 0L) {
			return;
		}
		BLOCK_ENTITY_START.set(0L);
		long duration = System.nanoTime() - start;
		BE_TOTAL_NS.addAndGet(duration);
		BE_MAX_NS.updateAndGet(prev -> Math.max(prev, duration));
	}

	public static void onEntityBegin() {
		ENTITY_START.set(System.nanoTime());
	}

	/**
	 * Live read of current cumulative entity+blockEntity nanos for the
	 * active 5-second window. Does not modify state — caller takes its
	 * own snapshot and computes deltas. Called by ServerTickPhaseMonitor
	 * from its END_SERVER_TICK handler before WorldTickMonitor's own
	 * handler resets these totals. Registration order in ExampleMod
	 * enforces "ServerTickPhaseMonitor first".
	 */
	public static long getEntityPlusBlockEntityNs() {
		return ENT_TOTAL_NS.get() + BE_TOTAL_NS.get();
	}

	public static void onEntityEnd() {
		long start = ENTITY_START.get();
		if (start == 0L) {
			return;
		}
		ENTITY_START.set(0L);
		long duration = System.nanoTime() - start;
		ENTITY_THIS_TICK_NS.addAndGet(duration);
	}

	// --- Internals ----------------------------------------------------------

	private static void onServerTickEnd() {
		long entityTotal = ENTITY_THIS_TICK_NS.getAndSet(0L);
		if (entityTotal > 0L) {
			ENT_TOTAL_NS.addAndGet(entityTotal);
			final long finalTotal = entityTotal;
			ENT_MAX_NS.updateAndGet(prev -> Math.max(prev, finalTotal));
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
		long beTotal = BE_TOTAL_NS.getAndSet(0L);
		long beMax = BE_MAX_NS.getAndSet(0L);
		long entTotal = ENT_TOTAL_NS.getAndSet(0L);
		long entMax = ENT_MAX_NS.getAndSet(0L);

		lastReportNs = now;

		if (ticks == 0L) {
			return;
		}

		LOGGER.info("[worldtick] blockentities: avg={} ms max={} ms  entities: avg={} ms max={} ms  n={} ticks",
				formatMs(beTotal / ticks),
				formatMs(beMax),
				formatMs(entTotal / ticks),
				formatMs(entMax),
				ticks);
	}

	private static String formatMs(long nanos) {
		return String.format("%.2f", nanos / 1_000_000.0);
	}
}
