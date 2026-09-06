package me.apika.apikaprobe.monitor;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

import net.minecraft.core.Registry;
import net.minecraft.world.level.levelgen.placement.PlacedFeature;

import me.apika.apikaprobe.monitor.FerriteDispatcherProbe.Stats;

// Per-PlacedFeature wall time and placed/no-op counts inside applyBiomeDecoration; default off.
public final class FeatureTiming {

	private FeatureTiming() {}

	public static volatile boolean ENABLED = Boolean.parseBoolean(
			System.getProperty("ferrite.featureProbe", "false"));

	private static final class Row {
		final Stats stats = new Stats();
		final AtomicLong placed = new AtomicLong();
	}

	private static final ConcurrentHashMap<PlacedFeature, Row> byFeature = new ConcurrentHashMap<>();
	private static final ThreadLocal<long[]> START = ThreadLocal.withInitial(() -> new long[1]);

	public static void begin() {
		if (!ENABLED) return;
		START.get()[0] = System.nanoTime();
	}

	public static void end(PlacedFeature feature, boolean placed) {
		if (!ENABLED) return;
		long start = START.get()[0];
		if (start == 0) return;
		Row row = byFeature.computeIfAbsent(feature, k -> new Row());
		row.stats.record(System.nanoTime() - start);
		if (placed) row.placed.incrementAndGet();
	}

	public static void reset() {
		byFeature.clear();
	}

	public static String report(Registry<PlacedFeature> registry, int top) {
		if (byFeature.isEmpty()) {
			return String.format("[ferrite/feature-probe] enabled=%s no samples yet", ENABLED);
		}
		long totalNanos = 0, totalCalls = 0, totalPlaced = 0;
		List<Map.Entry<PlacedFeature, Row>> rows = new ArrayList<>(byFeature.entrySet());
		for (Map.Entry<PlacedFeature, Row> e : rows) {
			totalNanos += e.getValue().stats.sumNanos.get();
			totalCalls += e.getValue().stats.count.get();
			totalPlaced += e.getValue().placed.get();
		}
		rows.sort(Comparator.comparingLong((Map.Entry<PlacedFeature, Row> e) -> e.getValue().stats.sumNanos.get()).reversed());
		long noopNanos = 0;
		for (Map.Entry<PlacedFeature, Row> e : rows) {
			Row r = e.getValue();
			if (r.placed.get() == 0) noopNanos += r.stats.sumNanos.get();
		}
		StringBuilder sb = new StringBuilder();
		sb.append(String.format("[ferrite/feature-probe] enabled=%s features=%d calls=%d placed=%d (%.1f%%) total=%.1fms never-placed-features=%.1f%% of time",
				ENABLED, rows.size(), totalCalls, totalPlaced,
				totalCalls == 0 ? 0.0 : 100.0 * totalPlaced / totalCalls,
				totalNanos / 1_000_000.0,
				totalNanos == 0 ? 0.0 : 100.0 * noopNanos / totalNanos));
		int shown = 0;
		double cumulative = 0;
		for (Map.Entry<PlacedFeature, Row> e : rows) {
			if (shown++ >= top) break;
			Row r = e.getValue();
			long n = r.stats.count.get();
			double share = totalNanos == 0 ? 0 : 100.0 * r.stats.sumNanos.get() / totalNanos;
			cumulative += share;
			String name = registry.getResourceKey(e.getKey()).map(k -> k.identifier().toString()).orElse("?");
			sb.append(String.format("\n  %-44s n=%-6d mean=%8.1fus p99=%8.1fus placed=%5.1f%% share=%5.1f%% cum=%5.1f%%",
					name, n,
					r.stats.sumNanos.get() / (double) n / 1_000.0,
					r.stats.percentileNanos(0.99) / 1_000.0,
					100.0 * r.placed.get() / n, share, cumulative));
		}
		return sb.toString();
	}
}
