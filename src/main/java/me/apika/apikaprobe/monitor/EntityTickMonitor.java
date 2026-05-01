package me.apika.apikaprobe.monitor;

import java.util.concurrent.atomic.AtomicLong;

import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.MobCategory;

/**
 * Buckets per-entity tick cost by category. Complements WorldTickMonitor
 * (which measures the total) by telling us which category is eating the
 * budget. Same 5-second window pattern as the other monitors.
 *
 * Categories:
 *   [0] MONSTER   — MobCategory.MONSTER
 *   [1] CREATURE  — CREATURE + AMBIENT + all water-group variants
 *   [2] ITEM      — instanceof ItemEntity (checked before spawn group —
 *                   items share the MISC spawn group with other things
 *                   we don't want counted together)
 *   [3] MISC      — everything else: projectiles, XP orbs, falling blocks,
 *                   item frames, hanging entities, etc.
 *
 * Metrics per category:
 *   avg ms / tick  — total-ns-in-category / tick-count
 *   max ms / tick  — worst single-tick sum for that category
 *
 * Server tick is single-threaded, but window accumulators use AtomicLong
 * to match the pattern used by WorldTickMonitor.
 */
public final class EntityTickMonitor {
	private static final Logger LOGGER = LoggerFactory.getLogger("ferrite");
	private static final long REPORT_INTERVAL_NS = 5_000_000_000L;

	private static final int CAT_MONSTER = 0;
	private static final int CAT_CREATURE = 1;
	private static final int CAT_ITEM = 2;
	private static final int CAT_MISC = 3;
	private static final int CAT_COUNT = 4;
	private static final String[] CAT_NAMES = {"monster", "creature", "item", "misc"};

	// Per-entity tick timer (server-thread, but ThreadLocal for safety).
	private static final ThreadLocal<Long> TICK_START = ThreadLocal.withInitial(() -> 0L);
	private static final ThreadLocal<Integer> TICK_CATEGORY = ThreadLocal.withInitial(() -> -1);

	// Per-tick running sums — one per category. Plain longs, server-thread-owned.
	private static final long[] THIS_TICK_NS = new long[CAT_COUNT];

	// Window accumulators.
	private static final AtomicLong[] TOTAL_NS = new AtomicLong[CAT_COUNT];
	private static final AtomicLong[] MAX_TICK_NS = new AtomicLong[CAT_COUNT];
	private static final AtomicLong TICK_COUNT = new AtomicLong();

	static {
		for (int i = 0; i < CAT_COUNT; i++) {
			TOTAL_NS[i] = new AtomicLong();
			MAX_TICK_NS[i] = new AtomicLong();
		}
	}

	private static volatile long lastReportNs = System.nanoTime();

	private EntityTickMonitor() {}

	public static void register() {
		ServerTickEvents.END_SERVER_TICK.register(server -> onServerTickEnd());
	}

	public static void onEntityTickBegin(Entity entity) {
		TICK_CATEGORY.set(categorize(entity));
		TICK_START.set(System.nanoTime());
	}

	public static void onEntityTickEnd() {
		long start = TICK_START.get();
		if (start == 0L) {
			return;
		}
		TICK_START.set(0L);
		int cat = TICK_CATEGORY.get();
		TICK_CATEGORY.set(-1);
		if (cat < 0 || cat >= CAT_COUNT) {
			return;
		}
		long duration = System.nanoTime() - start;
		THIS_TICK_NS[cat] += duration;
	}

	private static int categorize(Entity entity) {
		// Item check first — items live in MobCategory.MISC alongside unrelated
		// things (falling blocks, projectiles). We want them bucketed separately.
		if (entity instanceof ItemEntity) {
			return CAT_ITEM;
		}
		MobCategory group = entity.getType().getSpawnGroup();
		if (group == MobCategory.MONSTER) {
			return CAT_MONSTER;
		}
		if (group == MobCategory.CREATURE
				|| group == MobCategory.AMBIENT
				|| group == MobCategory.AXOLOTLS
				|| group == MobCategory.WATER_CREATURE
				|| group == MobCategory.WATER_AMBIENT
				|| group == MobCategory.UNDERGROUND_WATER_CREATURE) {
			return CAT_CREATURE;
		}
		return CAT_MISC;
	}

	private static void onServerTickEnd() {
		for (int i = 0; i < CAT_COUNT; i++) {
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
		long[] totals = new long[CAT_COUNT];
		long[] maxes = new long[CAT_COUNT];
		for (int i = 0; i < CAT_COUNT; i++) {
			totals[i] = TOTAL_NS[i].getAndSet(0L);
			maxes[i] = MAX_TICK_NS[i].getAndSet(0L);
		}

		lastReportNs = now;

		if (ticks == 0L) {
			return;
		}

		StringBuilder sb = new StringBuilder("[entity-tick]");
		for (int i = 0; i < CAT_COUNT; i++) {
			sb.append(' ')
			  .append(CAT_NAMES[i])
			  .append(": avg=")
			  .append(formatMs(totals[i] / ticks))
			  .append(" max=")
			  .append(formatMs(maxes[i]));
		}
		sb.append("  n=").append(ticks).append(" ticks");
		LOGGER.info(sb.toString());
	}

	private static String formatMs(long nanos) {
		return String.format("%.2f", nanos / 1_000_000.0) + "ms";
	}
}
