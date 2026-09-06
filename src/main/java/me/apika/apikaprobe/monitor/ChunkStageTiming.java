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
			"generateStructureStarts", "generateStructureReferences",
			"generateFeatures", "generateSpawn" };

	// Async stages: only the synchronous handoff part is measured.
	public static final String[] HANDOFF_STAGES = { "generateBiomes", "buildTerrain" };

	// Pool-side work inside buildTerrain's async lambda (26.3), timed on the worker thread.
	public static final String[] POOL_STAGES = { "doFill", "sampleVolume", "interp.sampleVolume",
			"perlin.addToVolume", "smeared.addToVolume", "buildSurface", "generateCarvers" };

	// Point queries nested inside a serial stage; n over the stage's n gives calls per chunk.
	public static final String[] POINT_STAGES = { "iterateNoiseColumn" };

	private static final ConcurrentHashMap<String, Stats> byStage = new ConcurrentHashMap<>();
	// Per-thread start stack so nested timers (sampleVolume inside doFill) do not clobber each other.
	private static final ThreadLocal<long[]> START = ThreadLocal.withInitial(() -> new long[9]);

	public static void begin() {
		if (!ENABLED) return;
		long[] st = START.get();
		int depth = (int) st[0];
		if (depth >= 8) return;
		st[1 + depth] = System.nanoTime();
		st[0] = depth + 1;
	}

	public static void end(String stage) {
		if (!ENABLED) return;
		long[] st = START.get();
		int depth = (int) st[0];
		if (depth == 0) return;
		st[0] = depth - 1;
		byStage.computeIfAbsent(stage, k -> new Stats()).record(System.nanoTime() - st[depth]);
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
		for (String stage : POOL_STAGES) appendLine(sb, stage, 0);
		for (String stage : POINT_STAGES) appendLine(sb, stage, 0);
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
		else if (isPoolStage(stage)) sb.append(" (pool thread)");
		else if (isPointStage(stage)) sb.append(" (per call, inside generateStructureStarts)");
		else sb.append(" (handoff only)");
	}

	private static boolean isPoolStage(String stage) {
		for (String p : POOL_STAGES) if (p.equals(stage)) return true;
		return false;
	}

	private static boolean isPointStage(String stage) {
		for (String p : POINT_STAGES) if (p.equals(stage)) return true;
		return false;
	}

	private static double meanMs(String stage) {
		Stats s = byStage.get(stage);
		if (s == null) return 0;
		long count = s.count.get();
		return count == 0 ? 0 : s.sumNanos.get() / (double) count / 1_000_000.0;
	}
}
