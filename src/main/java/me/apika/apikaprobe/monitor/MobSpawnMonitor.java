package me.apika.apikaprobe.monitor;

import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;

/**
 * Natural mob spawn cost monitor, server thread only.
 *
 *   census   : NaturalSpawner.createState, the once-per-tick entity
 *              iteration (chunk lookup + biome read + cap maps).
 *   attempts : NaturalSpawner.spawnCategoryForChunk, one call per
 *              eligible (chunk, category) pair.
 *   spawns   : SpawnState.afterSpawn, mobs actually placed.
 *
 * Gate 1 question: census ms + attempt ms combined per tick. Plain
 * longs (thisTickPathNs pattern), 5 s window drained from
 * END_SERVER_TICK, line printed only when a census ran.
 */
public final class MobSpawnMonitor {
	private static final long REPORT_INTERVAL_NS = 5_000_000_000L;

	private static long censusStart;
	private static long censusCount;
	private static long censusTotalNs;
	private static long censusMaxNs;

	private static long attemptStart;
	private static long attemptCount;
	private static long attemptTotalNs;
	private static long attemptMaxNs;

	private static long spawnCount;

	private static long lastReportNs = System.nanoTime();

	private MobSpawnMonitor() {}

	public static void register() {
		ServerTickEvents.END_SERVER_TICK.register(server -> maybeReport());
	}

	public static void onCensusStart() {
		censusStart = System.nanoTime();
	}

	public static void onCensusEnd() {
		if (censusStart == 0L) {
			return;
		}
		long duration = System.nanoTime() - censusStart;
		censusStart = 0L;
		censusCount++;
		censusTotalNs += duration;
		censusMaxNs = Math.max(censusMaxNs, duration);
	}

	public static void onAttemptStart() {
		attemptStart = System.nanoTime();
	}

	public static void onAttemptEnd() {
		if (attemptStart == 0L) {
			return;
		}
		long duration = System.nanoTime() - attemptStart;
		attemptStart = 0L;
		attemptCount++;
		attemptTotalNs += duration;
		attemptMaxNs = Math.max(attemptMaxNs, duration);
	}

	public static void onSpawn() {
		spawnCount++;
	}

	private static void maybeReport() {
		long now = System.nanoTime();
		if (now - lastReportNs < REPORT_INTERVAL_NS) {
			return;
		}

		long cCount = censusCount;
		long cTotal = censusTotalNs;
		long cMax = censusMaxNs;
		long aCount = attemptCount;
		long aTotal = attemptTotalNs;
		long aMax = attemptMaxNs;
		long spawns = spawnCount;

		censusCount = 0L;
		censusTotalNs = 0L;
		censusMaxNs = 0L;
		attemptCount = 0L;
		attemptTotalNs = 0L;
		attemptMaxNs = 0L;
		spawnCount = 0L;
		lastReportNs = now;

		if (cCount == 0L) {
			return;
		}

		MonitorLog.info("[mob-spawn] census: n={} avg={} ms max={} ms total={} ms  attempts: n={} avg={} us max={} us total={} ms  spawns={}",
				cCount,
				formatMs(cCount == 0L ? 0L : cTotal / cCount),
				formatMs(cMax),
				formatMs(cTotal),
				aCount,
				formatUs(aCount == 0L ? 0L : aTotal / aCount),
				formatUs(aMax),
				formatMs(aTotal),
				spawns);
	}

	private static String formatMs(long nanos) {
		return String.format("%.2f", nanos / 1_000_000.0);
	}

	private static String formatUs(long nanos) {
		return String.format("%.1f", nanos / 1_000.0);
	}
}
