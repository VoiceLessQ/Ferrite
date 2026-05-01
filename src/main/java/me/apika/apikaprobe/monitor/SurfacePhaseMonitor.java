package me.apika.apikaprobe.monitor;

import java.util.concurrent.atomic.AtomicLong;

import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Splits SurfaceRules.buildSurface's per-chunk cost into seven buckets
 * so we can decide whether a Rust port of the surface-rule evaluator is
 * worth the architectural lift.
 *
 *   [0] tryApply       — SurfaceRules$BlockStateRule.tryApply(x,y,z)
 *                        (the hot rule-tree walk; 16K-65K calls per chunk)
 *   [1] blockRead      — BlockColumn.getBlock(y)
 *   [2] blockWrite     — BlockColumn.setBlock(y, state)
 *   [3] contextUpdate  — MaterialRuleContext.initHorizontalContext /
 *                        initVerticalContext
 *   [4] biomeLookup    — BiomeManager.getBiome(pos)
 *   [5] heightmap      — Chunk.sampleHeightmap(type, x, z)
 *        other          — buildSurface_total - sum(above)
 *
 * Accumulated per-chunk, logged every 5 seconds with per-chunk averages.
 * Same AtomicLong + ThreadLocal start + window-reset pattern as
 * MovementInternalsMonitor.
 *
 * Chunk generation runs off-thread (worker pool), so ThreadLocal-based
 * start timestamps are per-worker — matches how the existing chunkgen
 * monitors measure.
 */
public final class SurfacePhaseMonitor {
	private static final Logger LOGGER = LoggerFactory.getLogger("ferrite");
	private static final long REPORT_INTERVAL_NS = 5_000_000_000L;

	private static final int PHASE_TRY_APPLY      = 0;
	private static final int PHASE_BLOCK_READ     = 1;
	private static final int PHASE_BLOCK_WRITE    = 2;
	private static final int PHASE_CTX_UPDATE     = 3;
	private static final int PHASE_BIOME_LOOKUP   = 4;
	private static final int PHASE_HEIGHTMAP      = 5;
	private static final int PHASE_COUNT          = 6;

	// Per-worker thread-local scratch for nested-call-safe timing.
	private static final ThreadLocal<long[]> PHASE_START =
			ThreadLocal.withInitial(() -> new long[PHASE_COUNT]);

	// Total across all chunks since last report.
	private static final AtomicLong[] TOTAL_NS = new AtomicLong[PHASE_COUNT];
	// Max single-chunk contribution to any given phase, for spike visibility.
	private static final AtomicLong[] MAX_PER_CHUNK_NS = new AtomicLong[PHASE_COUNT];
	// Whole-buildSurface envelope.
	private static final AtomicLong BUILD_TOTAL_NS = new AtomicLong();
	private static final AtomicLong BUILD_MAX_NS = new AtomicLong();
	private static final AtomicLong CHUNK_COUNT = new AtomicLong();

	// Per-chunk accumulators (ThreadLocal so overlapping worker chunks
	// don't cross-contaminate).
	private static final ThreadLocal<long[]> THIS_CHUNK_NS =
			ThreadLocal.withInitial(() -> new long[PHASE_COUNT]);
	private static final ThreadLocal<Long> BUILD_START_NS =
			ThreadLocal.withInitial(() -> 0L);

	static {
		for (int i = 0; i < PHASE_COUNT; i++) {
			TOTAL_NS[i] = new AtomicLong();
			MAX_PER_CHUNK_NS[i] = new AtomicLong();
		}
	}

	private static volatile long lastReportNs = System.nanoTime();

	private SurfacePhaseMonitor() {}

	public static void register() {
		// Drive reporting off the server tick — same cadence as the other monitors.
		ServerTickEvents.END_SERVER_TICK.register(server -> maybeReport());
	}

	// --- buildSurface envelope ---------------------------------------------

	public static void onBuildSurfaceBegin() {
		long[] chunkBuckets = THIS_CHUNK_NS.get();
		for (int i = 0; i < PHASE_COUNT; i++) chunkBuckets[i] = 0L;
		BUILD_START_NS.set(System.nanoTime());
	}

	public static void onBuildSurfaceEnd() {
		long start = BUILD_START_NS.get();
		if (start == 0L) return;
		BUILD_START_NS.set(0L);
		long duration = System.nanoTime() - start;

		BUILD_TOTAL_NS.addAndGet(duration);
		final long snap = duration;
		BUILD_MAX_NS.updateAndGet(prev -> Math.max(prev, snap));
		CHUNK_COUNT.incrementAndGet();

		// Flush this chunk's per-phase accumulators into the window totals.
		long[] chunkBuckets = THIS_CHUNK_NS.get();
		for (int i = 0; i < PHASE_COUNT; i++) {
			long v = chunkBuckets[i];
			if (v > 0L) {
				TOTAL_NS[i].addAndGet(v);
				final long snapV = v;
				MAX_PER_CHUNK_NS[i].updateAndGet(prev -> Math.max(prev, snapV));
			}
			chunkBuckets[i] = 0L;
		}
	}

	// --- Per-phase hooks ---------------------------------------------------
	// Each pair records nanos into the per-worker THIS_CHUNK_NS[phase] slot.
	// Begin stores start, End computes duration and adds to the accumulator.

	public static void onTryApplyBegin()      { beginPhase(PHASE_TRY_APPLY); }
	public static void onTryApplyEnd()        { endPhase(PHASE_TRY_APPLY); }

	public static void onBlockReadBegin()     { beginPhase(PHASE_BLOCK_READ); }
	public static void onBlockReadEnd()       { endPhase(PHASE_BLOCK_READ); }

	public static void onBlockWriteBegin()    { beginPhase(PHASE_BLOCK_WRITE); }
	public static void onBlockWriteEnd()      { endPhase(PHASE_BLOCK_WRITE); }

	public static void onCtxUpdateBegin()     { beginPhase(PHASE_CTX_UPDATE); }
	public static void onCtxUpdateEnd()       { endPhase(PHASE_CTX_UPDATE); }

	public static void onBiomeLookupBegin()   { beginPhase(PHASE_BIOME_LOOKUP); }
	public static void onBiomeLookupEnd()     { endPhase(PHASE_BIOME_LOOKUP); }

	public static void onHeightmapBegin()     { beginPhase(PHASE_HEIGHTMAP); }
	public static void onHeightmapEnd()       { endPhase(PHASE_HEIGHTMAP); }

	// --- Internals ---------------------------------------------------------

	private static void beginPhase(int phase) {
		PHASE_START.get()[phase] = System.nanoTime();
	}

	private static void endPhase(int phase) {
		long[] starts = PHASE_START.get();
		long start = starts[phase];
		if (start == 0L) return;
		starts[phase] = 0L;
		long duration = System.nanoTime() - start;
		THIS_CHUNK_NS.get()[phase] += duration;
	}

	private static void maybeReport() {
		long now = System.nanoTime();
		if (now - lastReportNs < REPORT_INTERVAL_NS) return;
		lastReportNs = now;

		long chunks = CHUNK_COUNT.getAndSet(0L);
		long buildTotal = BUILD_TOTAL_NS.getAndSet(0L);
		long buildMax = BUILD_MAX_NS.getAndSet(0L);
		long[] phaseTotals = new long[PHASE_COUNT];
		long[] phaseMax = new long[PHASE_COUNT];
		for (int i = 0; i < PHASE_COUNT; i++) {
			phaseTotals[i] = TOTAL_NS[i].getAndSet(0L);
			phaseMax[i] = MAX_PER_CHUNK_NS[i].getAndSet(0L);
		}

		if (chunks == 0L) return;

		long accounted = 0L;
		for (int i = 0; i < PHASE_COUNT; i++) accounted += phaseTotals[i];
		long otherTotal = Math.max(0L, buildTotal - accounted);

		LOGGER.info(
			"[surface-phase] build: avg={} max={}  tryApply: avg={} max={}  "
			+ "blockRead: avg={} max={}  blockWrite: avg={} max={}  "
			+ "ctxUpdate: avg={} max={}  biomeLookup: avg={} max={}  "
			+ "heightmap: avg={} max={}  other: avg={}  n={} chunks",
			formatMs(buildTotal / chunks),
			formatMs(buildMax),
			formatMs(phaseTotals[PHASE_TRY_APPLY] / chunks),
			formatMs(phaseMax[PHASE_TRY_APPLY]),
			formatMs(phaseTotals[PHASE_BLOCK_READ] / chunks),
			formatMs(phaseMax[PHASE_BLOCK_READ]),
			formatMs(phaseTotals[PHASE_BLOCK_WRITE] / chunks),
			formatMs(phaseMax[PHASE_BLOCK_WRITE]),
			formatMs(phaseTotals[PHASE_CTX_UPDATE] / chunks),
			formatMs(phaseMax[PHASE_CTX_UPDATE]),
			formatMs(phaseTotals[PHASE_BIOME_LOOKUP] / chunks),
			formatMs(phaseMax[PHASE_BIOME_LOOKUP]),
			formatMs(phaseTotals[PHASE_HEIGHTMAP] / chunks),
			formatMs(phaseMax[PHASE_HEIGHTMAP]),
			formatMs(otherTotal / chunks),
			chunks
		);
	}

	private static String formatMs(long nanos) {
		return String.format("%.3f", nanos / 1_000_000.0) + "ms";
	}
}
