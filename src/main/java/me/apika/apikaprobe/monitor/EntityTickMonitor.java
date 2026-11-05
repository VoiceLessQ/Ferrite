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
 * Entity ticks may run on worker threads, so shared accumulators are atomic.
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
	// long[] rather than boxed Long/Integer: this fires per entity per tick,
	// and Long boxing at that rate is measurable young-gen churn.
	// [0] = start ns (0 = no sample in flight), [1] = category.
	private static final ThreadLocal<long[]> TICK_STATE = ThreadLocal.withInitial(() -> new long[2]);
	private static final ThreadLocal<net.minecraft.world.entity.EntityType<?>> TICK_TYPE =
			ThreadLocal.withInitial(() -> null);

	// Misc-bucket breakdown for the window. Entity tick workers update these
	// counters concurrently; the server thread drains the map at report time.
	// Exists because a field report (PR #8, Pi 4B) showed misc spiking to
	// 47.8 ms with no way to tell WHICH entity type was responsible.
	private static final ConcurrentTickStats<net.minecraft.world.entity.EntityType<?>> TICK_STATS =
			new ConcurrentTickStats<>(CAT_COUNT);
	/** Emit the misc-top breakdown line when the window's worst misc tick exceeds this. */
	private static final long MISC_TOP_THRESHOLD_NS = 5_000_000L;

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
		// Collect gate: when monitor logging is off, skip the nanoTime and
		// ThreadLocal work entirely. The start sentinel keeps a mid-window
		// toggle from producing a torn sample.
		if (!MonitorLog.ENABLED) return;
		long[] state = TICK_STATE.get();
		int cat = categorize(entity);
		state[1] = cat;
		if (cat == CAT_MISC) TICK_TYPE.set(entity.getType());
		state[0] = System.nanoTime();
	}

	public static void onEntityTickEnd() {
		long[] state = TICK_STATE.get();
		long start = state[0];
		if (start == 0L) {
			return;
		}
		state[0] = 0L;
		int cat = (int) state[1];
		if (cat < 0 || cat >= CAT_COUNT) {
			return;
		}
		long duration = System.nanoTime() - start;
		net.minecraft.world.entity.EntityType<?> type = null;
		if (cat == CAT_MISC) {
			type = TICK_TYPE.get();
			TICK_TYPE.set(null);
		}
		TICK_STATS.record(cat, duration, type);
	}

	private static int categorize(Entity entity) {
		// Item check first — items live in MobCategory.MISC alongside unrelated
		// things (falling blocks, projectiles). We want them bucketed separately.
		if (entity instanceof ItemEntity) {
			return CAT_ITEM;
		}
		MobCategory group = entity.getType().getCategory();
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
			long thisTick = TICK_STATS.drainCategory(i);
			if (thisTick > 0L) {
				TOTAL_NS[i].addAndGet(thisTick);
				final long snapshot = thisTick;
				MAX_TICK_NS[i].updateAndGet(prev -> Math.max(prev, snapshot));
			}
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
		MonitorLog.info(sb.toString());

		// Misc breakdown: name the top offenders when the misc bucket had a
		// hot tick this window, then reset for the next window either way.
		if (!TICK_STATS.byType().isEmpty()) {
			if (maxes[CAT_MISC] >= MISC_TOP_THRESHOLD_NS) {
				java.util.List<java.util.Map.Entry<net.minecraft.world.entity.EntityType<?>, ConcurrentTickStats.PerTypeStats>> top =
						new java.util.ArrayList<>(TICK_STATS.byType().entrySet());
				top.sort((a, b) -> Long.compare(b.getValue().totalNanos.sum(), a.getValue().totalNanos.sum()));
				StringBuilder mb = new StringBuilder("[entity-tick] misc-top:");
				int limit = Math.min(3, top.size());
				for (int i = 0; i < limit; i++) {
					ConcurrentTickStats.PerTypeStats v = top.get(i).getValue();
					mb.append(' ')
					  .append(net.minecraft.world.entity.EntityType.getKey(top.get(i).getKey()).getPath())
					  .append(" total=").append(formatMs(v.totalNanos.sum()))
					  .append(" maxSingle=").append(formatMs(v.maxNanos.get()))
					  .append(" n=").append(v.count.sum());
				}
				MonitorLog.info(mb.toString());
			}
			TICK_STATS.byType().clear();
		}
	}

	private static String formatMs(long nanos) {
		return String.format("%.2f", nanos / 1_000_000.0) + "ms";
	}
}
