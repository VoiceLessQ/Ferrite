package me.apika.apikaprobe.monitor;

import java.util.EnumMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicLongArray;

import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;

import org.jspecify.annotations.Nullable;

import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.entity.npc.villager.AbstractVillager;
import net.minecraft.world.level.pathfinder.Path;

/**
 * Pathfinding instrumentation. Hooks {@code PathFinder.findPath} entry/exit
 * to capture per-call latency, the mob category that requested the path,
 * the path length returned, and whether the request succeeded.
 *
 * Per-category split (VILLAGER, HOSTILE, PASSIVE, OTHER) lets the trading
 * hall vs mob farm baselines surface separately. The latency histogram
 * reuses {@link LatencyHistogram}; path length uses a separate manual-
 * bucket histogram (cascade-size pattern from RedstonePhaseMonitor)
 * because lengths are small integers, not log2-ns.
 *
 * Failure rate (null path) per category is the cache-invalidation canary:
 * high failure with high recalc is a workload that re-paths the same
 * impossible route, which a walkability cache would not help. Low failure
 * with high recalc is the cache-friendly case.
 */
public final class NavigationMonitor {
	private static final long REPORT_INTERVAL_NS = 5_000_000_000L;

	public enum Category { VILLAGER, HOSTILE, PASSIVE, OTHER }

	private static final ThreadLocal<long[]> START_NS = ThreadLocal.withInitial(() -> new long[1]);

	// Path length buckets (node count). Failure paths are not recorded here;
	// they go in TOTAL_FAILURES_BY_CAT instead.
	private static final int[] LEN_BUCKET_UPPER = {4, 8, 16, 32, 64, 128, Integer.MAX_VALUE};
	private static final String[] LEN_BUCKET_LABEL = {"1-4", "5-8", "9-16", "17-32", "33-64", "65-128", "129+"};
	private static final int N_LEN_BUCKETS = LEN_BUCKET_UPPER.length;

	private static final EnumMap<Category, LatencyHistogram> COST = new EnumMap<>(Category.class);
	private static final EnumMap<Category, AtomicLong> TOTAL_PATHS = new EnumMap<>(Category.class);
	private static final EnumMap<Category, AtomicLong> TOTAL_FAILURES = new EnumMap<>(Category.class);
	private static final EnumMap<Category, AtomicLongArray> LEN_BUCKETS = new EnumMap<>(Category.class);
	static {
		for (Category cat : Category.values()) {
			COST.put(cat, new LatencyHistogram());
			TOTAL_PATHS.put(cat, new AtomicLong());
			TOTAL_FAILURES.put(cat, new AtomicLong());
			LEN_BUCKETS.put(cat, new AtomicLongArray(N_LEN_BUCKETS));
		}
	}

	// Per-tick aggregate path cost across all categories. thisTickPathNs is
	// non-atomic because pathfinding all happens on the server thread; matches
	// the MoveControlMonitor / LookControlMonitor pattern. Drained once per
	// server tick into TICK_PATH_COST.
	private static long thisTickPathNs = 0L;
	private static final LatencyHistogram TICK_PATH_COST = new LatencyHistogram();

	private static final AtomicLong TICK_COUNT = new AtomicLong();
	private static volatile long lastReportNs = System.nanoTime();

	private NavigationMonitor() {}

	public static void register() {
		ServerTickEvents.END_SERVER_TICK.register(server -> {
			if (thisTickPathNs > 0L) TICK_PATH_COST.record(thisTickPathNs);
			thisTickPathNs = 0L;
			TICK_COUNT.incrementAndGet();
			maybeReport();
		});
	}

	public static void onFindPathBegin() {
		START_NS.get()[0] = System.nanoTime();
	}

	public static void onFindPathEnd(Mob entity, @Nullable Path result) {
		long start = START_NS.get()[0];
		if (start == 0L) return;
		START_NS.get()[0] = 0L;
		long duration = System.nanoTime() - start;
		thisTickPathNs += duration;
		Category cat = classify(entity);
		COST.get(cat).record(duration);
		TOTAL_PATHS.get(cat).incrementAndGet();
		if (result == null) {
			TOTAL_FAILURES.get(cat).incrementAndGet();
		} else {
			LEN_BUCKETS.get(cat).incrementAndGet(lengthBucket(result.getNodeCount()));
		}
	}

	private static Category classify(Mob entity) {
		if (entity instanceof AbstractVillager) return Category.VILLAGER;
		MobCategory mc = entity.getType().getCategory();
		if (mc == MobCategory.MONSTER) return Category.HOSTILE;
		if (mc == MobCategory.MISC) return Category.OTHER;
		return Category.PASSIVE;
	}

	private static int lengthBucket(int len) {
		for (int i = 0; i < N_LEN_BUCKETS; i++) {
			if (len <= LEN_BUCKET_UPPER[i]) return i;
		}
		return N_LEN_BUCKETS - 1;
	}

	private static void maybeReport() {
		long now = System.nanoTime();
		if (now - lastReportNs < REPORT_INTERVAL_NS) return;
		long ticks = TICK_COUNT.getAndSet(0L);
		LatencyHistogram.Snapshot tickSnap = TICK_PATH_COST.drain();
		lastReportNs = now;
		if (ticks == 0L) return;

		if (!tickSnap.isEmpty()) {
			MonitorLog.info("[nav-tick] tick-cost: {}  ticks={}", tickSnap.formatLine(), ticks);
		}

		for (Category cat : Category.values()) {
			long paths = TOTAL_PATHS.get(cat).getAndSet(0L);
			long fails = TOTAL_FAILURES.get(cat).getAndSet(0L);
			LatencyHistogram.Snapshot snap = COST.get(cat).drain();
			AtomicLongArray lens = LEN_BUCKETS.get(cat);
			long[] lenCounts = new long[N_LEN_BUCKETS];
			for (int i = 0; i < N_LEN_BUCKETS; i++) {
				lenCounts[i] = lens.getAndSet(i, 0L);
			}

			if (paths == 0L) continue;

			double pathsPerTick = (double) paths / ticks;
			double failPct = 100.0 * fails / paths;

			StringBuilder lenStr = new StringBuilder("len=[");
			boolean first = true;
			for (int i = 0; i < N_LEN_BUCKETS; i++) {
				if (lenCounts[i] == 0L) continue;
				if (!first) lenStr.append(' ');
				first = false;
				lenStr.append(LEN_BUCKET_LABEL[i]).append(':').append(lenCounts[i]);
			}
			lenStr.append(']');

			MonitorLog.info(
				"[nav-{}] paths={}/tick (fail={})  cost: {}  {}  ticks={}",
				cat.name().toLowerCase(),
				String.format("%.2f", pathsPerTick),
				String.format("%.1f%%", failPct),
				snap.formatLine(),
				lenStr.toString(),
				ticks
			);
		}
	}
}
