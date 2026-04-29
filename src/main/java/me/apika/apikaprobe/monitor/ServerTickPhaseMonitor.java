package me.apika.apikaprobe.monitor;

import java.util.concurrent.atomic.AtomicLong;

import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Top-level server-tick composition. Three buckets plus computed other:
 *
 *   scheduledTicks — WorldTickScheduler.tick (blockTicks + fluidTicks, summed
 *                    from the split block/fluid accumulators below)
 *   chunkTick      — ChunkManager.tick (random-tick loop, chunk updates, etc.)
 *   other          — total - scheduledTicks - chunkTick
 *                    - WorldTickMonitor.entities+blockEntities
 *
 * Block and fluid scheduledTicks are also reported separately as
 * [block-tick] and [fluid-tick] with per-call counts. The split was
 * added when fluid ticks were proposed as a port target — keeping them
 * bundled hid whether vanilla was actually bottlenecked there.
 *
 * Total envelope comes from Fabric's START/END_SERVER_TICK events rather
 * than a mixin — avoids touching bytecode for work that's naturally event-
 * driven and fires once per server tick instead of once per world.
 *
 * Must register BEFORE WorldTickMonitor so its END_SERVER_TICK handler
 * fires first and reads WorldTickMonitor.getEntityPlusBlockEntityNs()
 * before that monitor resets its own cumulative counters.
 */
public final class ServerTickPhaseMonitor {
	private static final Logger LOGGER = LoggerFactory.getLogger("ferrite");
	private static final long REPORT_INTERVAL_NS = 5_000_000_000L;

	// --- Envelope (Fabric-event driven) ------------------------------------
	private static volatile long tickStartNs = 0L;

	// --- Per-tick phase running sums (reset at END_SERVER_TICK) ------------
	private static final AtomicLong TOTAL_NS = new AtomicLong();
	private static final AtomicLong MAX_TICK_NS = new AtomicLong();
	private static final AtomicLong BLOCK_TICKS_NS = new AtomicLong();
	private static final AtomicLong FLUID_TICKS_NS = new AtomicLong();
	private static final AtomicLong BLOCK_TICK_COUNT = new AtomicLong();
	private static final AtomicLong FLUID_TICK_COUNT = new AtomicLong();
	private static final AtomicLong TICK_COUNT = new AtomicLong();

	private static volatile long lastReportNs = System.nanoTime();

	// --- Phase hooks (called from ServerTickPhaseMixin) --------------------
	// Separate ThreadLocals so a future parallel-world dispatch doesn't
	// collide; today the server tick is single-threaded and these always
	// pair sequentially (block first, then fluid).

	private static final ThreadLocal<Long> BLOCK_START =
			ThreadLocal.withInitial(() -> 0L);
	private static final ThreadLocal<Long> FLUID_START =
			ThreadLocal.withInitial(() -> 0L);

	private ServerTickPhaseMonitor() {}

	public static void register() {
		ServerTickEvents.START_SERVER_TICK.register(server -> {
			tickStartNs = System.nanoTime();
		});
		ServerTickEvents.END_SERVER_TICK.register(server -> {
			long start = tickStartNs;
			if (start != 0L) {
				long duration = System.nanoTime() - start;
				TOTAL_NS.addAndGet(duration);
				final long snap = duration;
				MAX_TICK_NS.updateAndGet(prev -> Math.max(prev, snap));
			}
			TICK_COUNT.incrementAndGet();
			maybeReport();
		});
	}

	public static void onBlockTicksBegin() {
		BLOCK_START.set(System.nanoTime());
	}

	public static void onBlockTicksEnd() {
		long start = BLOCK_START.get();
		if (start == 0L) return;
		BLOCK_START.set(0L);
		BLOCK_TICKS_NS.addAndGet(System.nanoTime() - start);
	}

	public static void onFluidTicksBegin() {
		FLUID_START.set(System.nanoTime());
	}

	public static void onFluidTicksEnd() {
		long start = FLUID_START.get();
		if (start == 0L) return;
		FLUID_START.set(0L);
		FLUID_TICKS_NS.addAndGet(System.nanoTime() - start);
	}

	public static void incBlockTickCount() {
		BLOCK_TICK_COUNT.incrementAndGet();
	}

	public static void incFluidTickCount() {
		FLUID_TICK_COUNT.incrementAndGet();
	}

	// --- Report -------------------------------------------------------------

	private static void maybeReport() {
		long now = System.nanoTime();
		if (now - lastReportNs < REPORT_INTERVAL_NS) return;
		lastReportNs = now;

		long ticks = TICK_COUNT.getAndSet(0L);
		long total = TOTAL_NS.getAndSet(0L);
		long maxTick = MAX_TICK_NS.getAndSet(0L);
		long blockNs = BLOCK_TICKS_NS.getAndSet(0L);
		long fluidNs = FLUID_TICKS_NS.getAndSet(0L);
		long blockCount = BLOCK_TICK_COUNT.getAndSet(0L);
		long fluidCount = FLUID_TICK_COUNT.getAndSet(0L);
		long scheduled = blockNs + fluidNs;

		// WorldTickMonitor also uses a 5s window accumulator with the same
		// REPORT_INTERVAL_NS. Our handler registers first, so we read its
		// cumulative value before it resets in its own END_SERVER_TICK
		// handler later in the same tick event. The value at read time IS
		// the current window's accumulation — no delta needed.
		long entityPlusBe = WorldTickMonitor.getEntityPlusBlockEntityNs();

		if (ticks == 0L) return;

		long accounted = scheduled + entityPlusBe;
		long other = Math.max(0L, total - accounted);

		// "other" is dominated by ServerChunkManager.tick (~3.4 ms on
		// measured load) plus the small housekeeping phases (~0.5 ms).
		// Not split out via @Inject — see ServerTickPhaseMixin comment
		// for why (JIT deoptimization from @Inject on the hot tick method).
		LOGGER.info(
			"[server-tick-phase] total: avg={} max={}  scheduledTicks: avg={}  "
			+ "entities+be: avg={}  other: avg={}  n={} ticks",
			formatMs(total / ticks),
			formatMs(maxTick),
			formatMs(scheduled / ticks),
			formatMs(entityPlusBe / ticks),
			formatMs(other / ticks),
			ticks
		);

		LOGGER.info(
			"[block-tick] ticks={} total={} avg={}/tick",
			blockCount,
			formatMs(blockNs),
			formatPerTick(blockNs, blockCount)
		);
		LOGGER.info(
			"[fluid-tick] ticks={} total={} avg={}/tick",
			fluidCount,
			formatMs(fluidNs),
			formatPerTick(fluidNs, fluidCount)
		);
	}

	private static String formatMs(long nanos) {
		return String.format("%.2f", nanos / 1_000_000.0) + "ms";
	}

	private static String formatPerTick(long nanos, long count) {
		if (count == 0L) return "n/a";
		double perTickUs = nanos / (double) count / 1_000.0;
		return String.format("%.2fµs", perTickUs);
	}
}
