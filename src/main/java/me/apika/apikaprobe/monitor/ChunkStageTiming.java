package me.apika.apikaprobe.monitor;

import java.util.concurrent.ConcurrentHashMap;

import me.apika.apikaprobe.monitor.FerriteDispatcherProbe.Stats;

// Wall time per inline chunk stage, cumulative until reset; default off (-Dferrite.stageProbe=true).
public final class ChunkStageTiming {

	private ChunkStageTiming() {}

	public static volatile boolean ENABLED = Boolean.parseBoolean(
			System.getProperty("ferrite.stageProbe", "false"));

	// Stages that return completedFuture: their whole cost is serial.
	public static final String[] SERIAL_STAGES = {
			"generateStructureStarts", "generateStructureReferences", "generateSurface",
			"generateCarvers", "generateFeatures", "generateSpawn" };

	// Async stages: only the synchronous handoff part is measured.
	public static final String[] HANDOFF_STAGES = { "generateBiomes", "generateNoise" };

	private static final ConcurrentHashMap<String, Stats> byStage = new ConcurrentHashMap<>();
	private static final ThreadLocal<long[]> START = ThreadLocal.withInitial(() -> new long[1]);

	public static void begin() {
		if (!ENABLED) return;
		START.get()[0] = System.nanoTime();
	}

	public static void end(String stage) {
		if (!ENABLED) return;
		long start = START.get()[0];
		if (start == 0) return;
		byStage.computeIfAbsent(stage, k -> new Stats()).record(System.nanoTime() - start);
		START.get()[0] = 0;
	}

	public static void reset() {
		byStage.clear();
	}

	public static String report() {
		if (byStage.isEmpty()) {
			return String.format("[ferrite/stage-probe] enabled=%s no samples yet", ENABLED);
		}
		double serialSumMs = 0;
		for (String stage : SERIAL_STAGES) serialSumMs += meanMs(stage);
		StringBuilder sb = new StringBuilder();
		sb.append(String.format("[ferrite/stage-probe] enabled=%s serialSum=%.3fms/chunk predicted=%.1f chunks/s",
				ENABLED, serialSumMs, serialSumMs > 0 ? 1000.0 / serialSumMs : 0.0));
		for (String stage : SERIAL_STAGES) appendLine(sb, stage, serialSumMs);
		for (String stage : HANDOFF_STAGES) appendLine(sb, stage, 0);
		return sb.toString();
	}

	private static void appendLine(StringBuilder sb, String stage, double serialSumMs) {
		Stats s = byStage.get(stage);
		if (s == null || s.count.get() == 0) return;
		long count = s.count.get();
		double meanMs = s.sumNanos.get() / (double) count / 1_000_000.0;
		sb.append(String.format("\n  %-28s n=%-7d mean=%8.3fms p50=%8.3fms p99=%8.3fms max=%8.3fms",
				stage, count, meanMs,
				s.percentileNanos(0.50) / 1_000_000.0,
				s.percentileNanos(0.99) / 1_000_000.0,
				s.maxNanos.get() / 1_000_000.0));
		if (serialSumMs > 0) sb.append(String.format(" share=%5.1f%%", 100.0 * meanMs / serialSumMs));
		else sb.append(" (handoff only)");
	}

	private static double meanMs(String stage) {
		Stats s = byStage.get(stage);
		if (s == null) return 0;
		long count = s.count.get();
		return count == 0 ? 0 : s.sumNanos.get() / (double) count / 1_000_000.0;
	}
}
