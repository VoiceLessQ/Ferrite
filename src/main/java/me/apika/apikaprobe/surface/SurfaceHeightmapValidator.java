package me.apika.apikaprobe.surface;

import java.util.Arrays;
import java.util.concurrent.atomic.AtomicLong;

import me.apika.apikaprobe.bridge.ExampleMod;

import net.minecraft.block.BlockState;
import net.minecraft.world.Heightmap;
import net.minecraft.world.chunk.Chunk;

/**
 * Step 1 of the batched heightmap update: a parity validator that
 * runs the proposed Path B (per-column trackUpdate at highest changed
 * Y) alongside the production Path A (vanilla per-write
 * {@code ProtoChunk.setBlockState} which fires {@code trackUpdate}
 * per write per heightmap type), and diffs the resulting heightmap
 * arrays cell-by-cell.
 *
 * <h3>How it works</h3>
 * Driven by {@link SurfaceDispatcher#flushChunk()}. When
 * {@link #ENABLED} is true:
 * <ol>
 * <li>Before the write loop fires, {@code flushChunk} captures
 *     pre-flush snapshots of {@code WORLD_SURFACE_WG} and
 *     {@code OCEAN_FLOOR_WG} via {@link #snapshot}.</li>
 * <li>The production Path A runs unchanged, populating heightmaps
 *     per-write via vanilla's {@code trackUpdate}.</li>
 * <li>{@link #validate} captures the post-A heightmap arrays,
 *     temporarily restores the pre-flush snapshot, applies Path B's
 *     batched updates (one {@code trackUpdate} per column at the
 *     highest changed Y per heightmap type), captures Path B's
 *     result, then restores Path A so the chunk stays correct for
 *     downstream phases.</li>
 * <li>Cells are diffed via {@link Heightmap#get(int, int)}; any
 *     mismatch is counted by heightmap type and chunks-with-any-mismatch
 *     are tracked.</li>
 * </ol>
 *
 * <h3>Pass criterion</h3>
 * 100% match across 1000+ chunks of varied biomes (overworld + ocean
 * + badlands + frozen) before flipping the dispatcher to use Path B
 * in production.
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

		// Per-column reduction: highest Y written and the state at that Y.
		// 256 columns max; index = (localX << 4) | localZ.
		int[] colHighestY = new int[256];
		BlockState[] colState = new BlockState[256];
		Arrays.fill(colHighestY, Integer.MIN_VALUE);
		int columnsWithWrites = 0;
		for (int i = 0; i < n; i++) {
			BlockState bs = writtenStates[i];
			if (bs == null) continue;
			int idx = ((xs[i] & 15) << 4) | (zs[i] & 15);
			if (ys[i] > colHighestY[idx]) {
				if (colHighestY[idx] == Integer.MIN_VALUE) columnsWithWrites++;
				colHighestY[idx] = ys[i];
				colState[idx] = bs;
			}
		}

		long mmWSWG = diffOne(chunk, hmWS, Heightmap.Type.WORLD_SURFACE_WG, preWSWG, colHighestY, colState);
		long mmOFWG = diffOne(chunk, hmOF, Heightmap.Type.OCEAN_FLOOR_WG, preOFWG, colHighestY, colState);

		chunksValidated.incrementAndGet();
		if (mmWSWG > 0 || mmOFWG > 0) {
			chunksMismatch.incrementAndGet();
			cellMismatchesWSWG.addAndGet(mmWSWG);
			cellMismatchesOFWG.addAndGet(mmOFWG);
			if (loggedMismatchSamples.getAndIncrement() < MAX_MISMATCH_LOG_LINES) {
				ExampleMod.LOGGER.warn(
					"[surface-heightmap-parity] chunk {} cols={} mismatches WSWG={} OFWG={}",
					chunk.getPos(), columnsWithWrites, mmWSWG, mmOFWG);
			}
		}
		maybeReport();
	}

	/** Capture Path A's cells, restore pre-snapshot, apply Path B's
	 *  per-column updates, capture Path B's cells, restore Path A,
	 *  return cell-level mismatch count. Returns 0 if heightmap or
	 *  snapshot is null. */
	private static long diffOne(Chunk chunk, Heightmap hm, Heightmap.Type type,
			long[] preSnapshot, int[] colHighestY, BlockState[] colState) {
		if (hm == null || preSnapshot == null) return 0;

		// Capture Path A.
		int[] pathACells = readCells(hm);
		long[] postA = hm.asLongArray().clone();

		// Restore pre-snapshot, apply Path B.
		hm.setTo(chunk, type, preSnapshot);
		for (int colIdx = 0; colIdx < 256; colIdx++) {
			if (colHighestY[colIdx] == Integer.MIN_VALUE) continue;
			int localX = (colIdx >> 4) & 15;
			int localZ = colIdx & 15;
			hm.trackUpdate(localX, colHighestY[colIdx], localZ, colState[colIdx]);
		}
		int[] pathBCells = readCells(hm);

		// Restore Path A so the chunk stays correct for downstream phases.
		hm.setTo(chunk, type, postA);

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
