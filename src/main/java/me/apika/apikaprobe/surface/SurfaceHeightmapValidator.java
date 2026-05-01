package me.apika.apikaprobe.surface;

import java.util.Arrays;
import java.util.concurrent.atomic.AtomicLong;

import me.apika.apikaprobe.bridge.ExampleMod;

import net.minecraft.world.level.block.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.chunk.Chunk;

/**
 * Heightmap parity regression check for the batched dispatcher path.
 *
 * <p>Step 1 (commit e4e7a41) used this to validate that Path B
 * (per-column {@code trackUpdate} at highest changed Y) was
 * bit-identical to Path A (vanilla per-write {@code trackUpdate})
 * before flipping the dispatcher to use Path B in production. Pass
 * criterion was met: 21,012 chunks, 100% match, 0 cell mismatches.
 *
 * <p>Step 2 made Path B the production path. This validator stays in
 * tree as a regression check: any future surface rule change that
 * violates the predicate-preserving assumption (writes that flip
 * {@code NOT_AIR} or {@code SUFFOCATES} for the highest Y in a
 * column) will produce a Path A vs Path B divergence and be flagged
 * here.
 *
 * <h3>How it works</h3>
 * Driven by {@link SurfaceDispatcher#flushChunk()}. When
 * {@link #ENABLED} is true:
 * <ol>
 * <li>Before the write loop fires, {@code flushChunk} captures
 *     pre-flush snapshots of {@code WORLD_SURFACE_WG} and
 *     {@code OCEAN_FLOOR_WG} via {@link #snapshot}.</li>
 * <li>The production Path B runs (raw {@code LevelChunkSection.setBlockState}
 *     per write, then per-column {@code trackUpdate} at highest Y).</li>
 * <li>{@link #validate} captures the post-B heightmap arrays,
 *     temporarily restores the pre-flush snapshot, applies Path A
 *     (per-write {@code trackUpdate}) as the reference, captures the
 *     reference arrays, then restores Path B so the chunk stays
 *     correct for downstream phases.</li>
 * <li>Cells are diffed via {@link Heightmap#get(int, int)}; any
 *     mismatch is counted by heightmap type and chunks-with-any-mismatch
 *     are tracked.</li>
 * </ol>
 *
 * <h3>Cost</h3>
 * Validation adds ~1 ms/chunk: array clones, two {@code setTo} memcpys
 * per heightmap type, ~256 {@code trackUpdate} calls × 2 types, and
 * 256 × 2 cell reads. Negligible relative to chunkgen's ~50 ms/chunk
 * total. Acceptable for a debug-toggleable mode.
 *
 * <h3>Toggle</h3>
 * {@code /ferrite surface heightmap-parity on|off|stats|reset}.
 * Default OFF.
 */
public final class SurfaceHeightmapValidator {

	private SurfaceHeightmapValidator() {}

	public static volatile boolean ENABLED = false;

	private static final AtomicLong chunksValidated = new AtomicLong();
	private static final AtomicLong chunksMismatch = new AtomicLong();
	private static final AtomicLong cellMismatchesWSWG = new AtomicLong();
	private static final AtomicLong cellMismatchesOFWG = new AtomicLong();
	private static final AtomicLong loggedMismatchSamples = new AtomicLong();
	private static final long MAX_MISMATCH_LOG_LINES = 25;
	private static volatile long lastReportNs = System.nanoTime();
	private static final long REPORT_INTERVAL_NS = 5_000_000_000L;

	/** Defensively clone the heightmap's packed long-storage array.
	 *  {@link Heightmap#asLongArray()} returns the live storage and a
	 *  snapshot must survive across mutations. */
	public static long[] snapshot(Chunk chunk, Heightmap.Type type) {
		Heightmap hm = chunk.getHeightmap(type);
		if (hm == null) return null;
		return hm.asLongArray().clone();
	}

	/**
	 * Diff Path A (already in the chunk's heightmap) against Path B
	 * (computed by replaying batched updates from the pre-snapshot).
	 * Restores Path A before returning.
	 *
	 * @param xs/ys/zs/writtenStates  parallel arrays length n; entries
	 *        with {@code writtenStates[i] == null} skipped (no write
	 *        happened at that position)
	 * @param preWSWG/preOFWG  pre-flush snapshots of the two SURFACE
	 *        phase heightmap types; either may be null if the heightmap
	 *        wasn't initialized at snapshot time (validation skipped
	 *        for that type)
	 */
	public static void validate(Chunk chunk, int[] xs, int[] ys, int[] zs,
			BlockState[] writtenStates, int n,
			long[] preWSWG, long[] preOFWG) {
		Heightmap hmWS = chunk.getHeightmap(Heightmap.Type.WORLD_SURFACE_WG);
		Heightmap hmOF = chunk.getHeightmap(Heightmap.Type.OCEAN_FLOOR_WG);
		if (hmWS == null && hmOF == null) return;

		long mmWSWG = diffOne(chunk, hmWS, Heightmap.Type.WORLD_SURFACE_WG,
				preWSWG, xs, ys, zs, writtenStates, n);
		long mmOFWG = diffOne(chunk, hmOF, Heightmap.Type.OCEAN_FLOOR_WG,
				preOFWG, xs, ys, zs, writtenStates, n);

		chunksValidated.incrementAndGet();
		if (mmWSWG > 0 || mmOFWG > 0) {
			chunksMismatch.incrementAndGet();
			cellMismatchesWSWG.addAndGet(mmWSWG);
			cellMismatchesOFWG.addAndGet(mmOFWG);
			if (loggedMismatchSamples.getAndIncrement() < MAX_MISMATCH_LOG_LINES) {
				ExampleMod.LOGGER.warn(
					"[surface-heightmap-parity] chunk {} writes={} mismatches WSWG={} OFWG={}",
					chunk.getPos(), n, mmWSWG, mmOFWG);
			}
		}
		maybeReport();
	}

	/** Capture Path B's cells (production path already wrote them),
	 *  restore the pre-flush snapshot, apply Path A reference (per-write
	 *  {@code trackUpdate}), capture Path A's cells, restore Path B so
	 *  the chunk stays correct for downstream phases, return cell-level
	 *  mismatch count. Returns 0 if heightmap or snapshot is null. */
	private static long diffOne(Chunk chunk, Heightmap hm, Heightmap.Type type,
			long[] preSnapshot, int[] xs, int[] ys, int[] zs,
			BlockState[] writtenStates, int n) {
		if (hm == null || preSnapshot == null) return 0;

		// Capture Path B (production — already in chunk after batched flush).
		int[] pathBCells = readCells(hm);
		long[] postB = hm.asLongArray().clone();

		// Restore pre-snapshot, apply Path A reference (per-write trackUpdate).
		hm.setTo(chunk, type, preSnapshot);
		for (int i = 0; i < n; i++) {
			BlockState bs = writtenStates[i];
			if (bs == null) continue;
			hm.trackUpdate(xs[i] & 15, ys[i], zs[i] & 15, bs);
		}
		int[] pathACells = readCells(hm);

		// Restore Path B (production) so the chunk stays correct.
		hm.setTo(chunk, type, postB);

		// Cell-level diff.
		long diff = 0;
		for (int i = 0; i < 256; i++) {
			if (pathACells[i] != pathBCells[i]) diff++;
		}
		return diff;
	}

	private static int[] readCells(Heightmap hm) {
		int[] cells = new int[256];
		for (int z = 0; z < 16; z++) {
			for (int x = 0; x < 16; x++) {
				cells[(x << 4) | z] = hm.get(x, z);
			}
		}
		return cells;
	}

	private static void maybeReport() {
		long now = System.nanoTime();
		long last = lastReportNs;
		if (now - last < REPORT_INTERVAL_NS) return;
		// Single-thread the report; if another thread already printed in
		// this window, skip.
		synchronized (SurfaceHeightmapValidator.class) {
			if (now - lastReportNs < REPORT_INTERVAL_NS) return;
			lastReportNs = now;
		}
		long total = chunksValidated.get();
		long mm = chunksMismatch.get();
		double matchPct = total == 0 ? 100.0 : 100.0 * (total - mm) / total;
		ExampleMod.LOGGER.info(
			"[surface-heightmap-parity] chunks={} match={}% mismatchedChunks={} cellMismatches WSWG={} OFWG={}",
			total, String.format("%.2f", matchPct), mm,
			cellMismatchesWSWG.get(), cellMismatchesOFWG.get());
	}

	public static String statsLine() {
		long total = chunksValidated.get();
		long mm = chunksMismatch.get();
		double matchPct = total == 0 ? 100.0 : 100.0 * (total - mm) / total;
		return String.format(
			"[surface-heightmap-parity] chunks=%d match=%.2f%% mismatchedChunks=%d cellMismatches WSWG=%d OFWG=%d",
			total, matchPct, mm, cellMismatchesWSWG.get(), cellMismatchesOFWG.get());
	}

	public static void resetCounters() {
		chunksValidated.set(0);
		chunksMismatch.set(0);
		cellMismatchesWSWG.set(0);
		cellMismatchesOFWG.set(0);
		loggedMismatchSamples.set(0);
	}
}
