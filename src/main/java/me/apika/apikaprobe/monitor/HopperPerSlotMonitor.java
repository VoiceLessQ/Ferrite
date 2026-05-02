package me.apika.apikaprobe.monitor;

import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicLongArray;

import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.util.math.BlockPos;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import me.apika.apikaprobe.hopper.PerSlotFireConfig;

public final class HopperPerSlotMonitor {
	private static final Logger LOGGER = LoggerFactory.getLogger("ferrite");
	private static final long REPORT_INTERVAL_NS = 5_000_000_000L;

	private static final AtomicLong FIRES         = new AtomicLong();
	private static final AtomicLong ITEMS_MOVED   = new AtomicLong();
	private static final AtomicLong NO_READY_SLOT = new AtomicLong();

	private static final AtomicLong TICK_VIOLATIONS   = new AtomicLong();
	private static final AtomicLong STAGGER_COLLAPSES = new AtomicLong();
	private static final AtomicLong LANE_HITS         = new AtomicLong();
	private static final AtomicLong LANE_FALLBACKS    = new AtomicLong();
	private static final AtomicLong COOLDOWN_VIOLATIONS = new AtomicLong();
	private static final AtomicLongArray SUM_BY_SLOT   = new AtomicLongArray(5);
	private static final AtomicLongArray COUNT_BY_SLOT = new AtomicLongArray(5);
	private static final AtomicLongArray MIN_BY_SLOT   = new AtomicLongArray(new long[]{Long.MAX_VALUE, Long.MAX_VALUE, Long.MAX_VALUE, Long.MAX_VALUE, Long.MAX_VALUE});
	private static final AtomicLongArray MAX_BY_SLOT   = new AtomicLongArray(5);
	private static final AtomicLong VAL_LOG_BUDGET    = new AtomicLong(20L);

	private static volatile long lastReportNs = System.nanoTime();

	private HopperPerSlotMonitor() {}

	public static void register() {
		ServerTickEvents.END_SERVER_TICK.register(server -> maybeReport());
		MonitorLog.info("[hopper-perslot] init enable={} validate={}",
			PerSlotFireConfig.ENABLE, PerSlotFireConfig.VALIDATE);
	}

	public static void onFire(int slot, int itemsMoved) {
		FIRES.incrementAndGet();
		if (itemsMoved > 0) ITEMS_MOVED.addAndGet(itemsMoved);
	}

	public static void onNoReadySlot() {
		NO_READY_SLOT.incrementAndGet();
	}

	public static void onTickViolation(BlockPos pos, int delta) {
		TICK_VIOLATIONS.incrementAndGet();
		if (VAL_LOG_BUDGET.getAndDecrement() > 0) {
			MonitorLog.warn("[hopper-perslot] TICK VIOLATION at {} delta={} (>1 item moved in single tick)", pos, delta);
		}
	}

	public static void onLaneHit() { LANE_HITS.incrementAndGet(); }
	public static void onLaneFallback() { LANE_FALLBACKS.incrementAndGet(); }

	public static void onSlotInterval(BlockPos pos, int slot, long delta) {
		if (slot < 0 || slot >= 5) return;
		SUM_BY_SLOT.addAndGet(slot, delta);
		COUNT_BY_SLOT.incrementAndGet(slot);
		long min;
		do { min = MIN_BY_SLOT.get(slot); if (delta >= min) break; } while (!MIN_BY_SLOT.compareAndSet(slot, min, delta));
		long max;
		do { max = MAX_BY_SLOT.get(slot); if (delta <= max) break; } while (!MAX_BY_SLOT.compareAndSet(slot, max, delta));
		if (delta < 8L) {
			COOLDOWN_VIOLATIONS.incrementAndGet();
			if (VAL_LOG_BUDGET.getAndDecrement() > 0) {
				MonitorLog.warn("[hopper-perslot] COOLDOWN VIOLATION at {} slot={} delta={} ticks (<8)", pos, slot, delta);
			}
		}
	}

	public static void onStaggerCollapse(BlockPos pos, int[] cooldowns) {
		STAGGER_COLLAPSES.incrementAndGet();
		if (VAL_LOG_BUDGET.getAndDecrement() > 0) {
			MonitorLog.warn("[hopper-perslot] STAGGER COLLAPSE at {} cooldowns={}", pos, java.util.Arrays.toString(cooldowns));
		}
	}

	private static void maybeReport() {
		long now = System.nanoTime();
		if (now - lastReportNs < REPORT_INTERVAL_NS) return;

		long fires    = FIRES.getAndSet(0);
		long items    = ITEMS_MOVED.getAndSet(0);
		long noReady  = NO_READY_SLOT.getAndSet(0);
		long tickV    = TICK_VIOLATIONS.getAndSet(0);
		long staggerC = STAGGER_COLLAPSES.getAndSet(0);
		long laneH    = LANE_HITS.getAndSet(0);
		long laneF    = LANE_FALLBACKS.getAndSet(0);
		long cdV      = COOLDOWN_VIOLATIONS.getAndSet(0);

		long[] sumS   = new long[5];
		long[] cntS   = new long[5];
		long[] minS   = new long[5];
		long[] maxS   = new long[5];
		for (int i = 0; i < 5; i++) {
			sumS[i] = SUM_BY_SLOT.getAndSet(i, 0L);
			cntS[i] = COUNT_BY_SLOT.getAndSet(i, 0L);
			minS[i] = MIN_BY_SLOT.getAndSet(i, Long.MAX_VALUE);
			maxS[i] = MAX_BY_SLOT.getAndSet(i, 0L);
		}

		if (fires == 0L && noReady == 0L && laneH == 0L && laneF == 0L) return;
		lastReportNs = now;

		MonitorLog.info(
			"[hopper-perslot] fires={} items={} noReady={} tickViolations={} staggerCollapses={} laneHits={} laneFallbacks={} cooldownViolations={}",
			fires, items, noReady, tickV, staggerC, laneH, laneF, cdV
		);
		StringBuilder sb = new StringBuilder("[hopper-perslot] per-slot intervals:");
		for (int i = 0; i < 5; i++) {
			double avg = cntS[i] > 0 ? (double) sumS[i] / cntS[i] : 0.0;
			long min = minS[i] == Long.MAX_VALUE ? 0 : minS[i];
			sb.append(" s").append(i).append("[avg=").append(String.format("%.2f", avg))
			  .append(" min=").append(min)
			  .append(" max=").append(maxS[i])
			  .append(" n=").append(cntS[i]).append("]");
		}
		MonitorLog.info(sb.toString());
	}
}
