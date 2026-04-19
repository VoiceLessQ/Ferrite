package me.apika.apikaprobe;

import java.util.concurrent.atomic.AtomicLong;

import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Top-level server-tick composition. Three buckets plus computed other:
 *
 *   scheduledTicks — WorldTickScheduler.tick (blockTicks + fluidTicks, merged)
 *   chunkTick      — ChunkManager.tick (random-tick loop, chunk updates, etc.)
 *   other          — total - scheduledTicks - chunkTick
 *                    - WorldTickMonitor.entities+blockEntities
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
	private static final AtomicLong SCHEDULED_TICKS_NS = new AtomicLong();
	private static final AtomicLong CHUNK_TICK_NS = new AtomicLong();
	private static final AtomicLong TICK_COUNT = new AtomicLong();

	// Snapshot of WorldTickMonitor's cumulative entity+blockEntity nanos
	// taken at the last report. We subtract this from the current live read
	// to get the delta attributable to our current window.
	private static long lastEntityPlusBeSnapshotNs = 0L;

	private static volatile long lastReportNs = System.nanoTime();

	// --- Phase hooks (called from ServerTickPhaseMixin) --------------------

	private static final ThreadLocal<Long> SCHEDULED_START =
			ThreadLocal.withInitial(() -> 0L);
	private static final ThreadLocal<Long> CHUNK_START =
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

	public static void onScheduledTicksBegin() {
		SCHEDULED_START.set(System.nanoTime());
	}

	public static void onScheduledTicksEnd() {
		long start = SCHEDULED_START.get();
		if (start == 0L) return;
		SCHEDULED_START.set(0L);
		SCHEDULED_TICKS_NS.addAndGet(System.nanoTime() - start);
	}

	public static void onChunkTickBegin() {
		CHUNK_START.set(System.nanoTime());
	}

	public static void onChunkTickEnd() {
		long start = CHUNK_START.get();
		if (start == 0L) return;
		CHUNK_START.set(0L);
		CHUNK_TICK_NS.addAndGet(System.nanoTime() - start);
	}

	// --- Report -------------------------------------------------------------

	private static void maybeReport() {
		long now = System.nanoTime();
		if (now - lastReportNs < REPORT_INTERVAL_NS) return;
		lastReportNs = now;

		long ticks = TICK_COUNT.getAndSet(0L);
		long total = TOTAL_NS.getAndSet(0L);
		long maxTick = MAX_TICK_NS.getAndSet(0L);
		long scheduled = SCHEDULED_TICKS_NS.getAndSet(0L);
		long chunkTick = CHUNK_TICK_NS.getAndSet(0L);

		// Live-read from WorldTickMonitor (must fire before it resets,
		// guaranteed by registration order).
		long entityPlusBeLive = WorldTickMonitor.getEntityPlusBlockEntityNs();
		long entityPlusBe = Math.max(0L, entityPlusBeLive - lastEntityPlusBeSnapshotNs);
		lastEntityPlusBeSnapshotNs = entityPlusBeLive;

		if (ticks == 0L) return;

		long accounted = scheduled + chunkTick + entityPlusBe;
		long other = Math.max(0L, total - accounted);

		LOGGER.info(
			"[server-tick-phase] total: avg={} max={}  scheduledTicks: avg={}  chunkTick: avg={}  "
			+ "entities+be: avg={}  other: avg={}  n={} ticks",
			formatMs(total / ticks),
			formatMs(maxTick),
			formatMs(scheduled / ticks),
			formatMs(chunkTick / ticks),
			formatMs(entityPlusBe / ticks),
			formatMs(other / ticks),
			ticks
		);
	}

	private static String formatMs(long nanos) {
		return String.format("%.2f", nanos / 1_000_000.0) + "ms";
	}
}
