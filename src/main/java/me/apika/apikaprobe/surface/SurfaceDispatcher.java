package me.apika.apikaprobe.surface;

import me.apika.apikaprobe.bridge.ExampleMod;
import net.minecraft.block.BlockState;
import net.minecraft.util.math.BlockPos;

/**
 * Batched surface-rule dispatcher.
 *
 * <p>The Uber-model swap: vanilla's {@code SurfaceSystem.buildSurface}
 * runs its outer loop normally (heightmap walk, per-Y stoneAbove/water
 * tracking, biome/noise context updates). When it would call
 * {@code rule.tryApply(x, y, z)}, the validator mixin's redirect
 * intercepts and routes here instead. We capture the per-position
 * context into pre-allocated primitive arrays and return null — vanilla
 * skips its own {@code setBlock} call (which only fires on non-null).
 *
 * <p>After {@code buildSurface} completes, {@link #flushChunk} fires:
 * one JNI batch dispatch through {@link SurfaceBatchHandoff}, then
 * walks the captured (x, y, z) array and writes each non-null result
 * back to the chunk. One JNI call per chunk replaces ~16K per-call
 * Java tryApply walks.
 *
 * <p>The simple per-call dispatch in {@link SurfaceValidator#tryDispatchEvaluator}
 * is retained for diagnostic A/B but is not used in this batched path
 * (it has the 15× regression we measured in the simple-swap experiment).
 *
 * <p>Per-thread state — chunkgen runs on multiple worker threads and
 * each one holds its own {@link DispatchState}. The "active" flag
 * gates whether the redirect treats this thread's calls as enqueueable.
 *
 * <p>Toggle: {@code /ferrite surface dispatch on|off|status}. Default
 * OFF. Requires a tree installed via {@code /ferrite surface validate}
 * before it does anything.
 */
public final class SurfaceDispatcher {

	private SurfaceDispatcher() {}

	public static volatile boolean ENABLED = false;

	/** Pre-allocated capacity per worker thread. Overworld chunks have
	 *  at most 16×16×384 = 98K positions in theory; in practice the
	 *  default-block-only filter keeps it well under 32K. Overflow
	 *  beyond capacity falls through to vanilla for the overflowing
	 *  positions (logged once per session). */
	private static final int CAPACITY = 65_536;

	private static final ThreadLocal<DispatchState> STATE =
			ThreadLocal.withInitial(DispatchState::new);

	private static volatile long batchesProcessed = 0;
	private static volatile long positionsProcessed = 0;
	private static volatile long overflowCount = 0;
	private static volatile long lastReportNs = System.nanoTime();
	private static final long REPORT_INTERVAL_NS = 5_000_000_000L;

	/**
	 * Per-thread enqueue-and-flush state. All arrays sized once to
	 * {@link #CAPACITY}; nothing allocates per chunk after warmup.
	 */
	static final class DispatchState {
		boolean active;
		Object protoChunk;          // ChunkAccess at runtime
		CompiledRuleTree tree;
		int count;

		final int[] xs              = new int[CAPACITY];
		final int[] ys              = new int[CAPACITY];
		final int[] zs              = new int[CAPACITY];
		final int[] runDepths       = new int[CAPACITY];
		final int[] stoneAboves     = new int[CAPACITY];
		final int[] stoneBelows     = new int[CAPACITY];
		final int[] fluidHeights    = new int[CAPACITY];
		final int[] surfaceHeights  = new int[CAPACITY];
		final boolean[] isColds     = new boolean[CAPACITY];
		final double[] secondaryDepths = new double[CAPACITY];
		final String[] biomes       = new String[CAPACITY];
		double[] noiseValues;       // sized lazily once tree is known: CAPACITY × channelCount

		final SurfaceBatchHandoff handoff = new SurfaceBatchHandoff();

		// Allocated lazily on first chunk where SurfaceHeightmapValidator is ON.
		// Entries parallel xs/ys/zs; null means no write happened at index i.
		BlockState[] writtenStates = null;

		// Per-column reduction scratch for the batched heightmap update.
		// Reused per chunk; sized to 16×16 = 256 columns.
		final int[] colHighestY = new int[256];
		final BlockState[] colState = new BlockState[256];

		void resetForChunk(Object protoChunk, CompiledRuleTree tree) {
			this.protoChunk = protoChunk;
			this.tree = tree;
			this.count = 0;
			int channels = tree.noiseChannelTable().length;
			int needed = CAPACITY * Math.max(1, channels);
			if (noiseValues == null || noiseValues.length < needed) {
				noiseValues = new double[needed];
			}
		}
	}

	// --- Lifecycle ----------------------------------------------------------

	/**
	 * Called from the buildSurface @Inject HEAD. If dispatch is enabled
	 * and a tree is installed, opens a per-thread batch and arms the
	 * tryApply redirect to enqueue rather than evaluate inline.
	 */
	public static void beginChunk(Object protoChunk) {
		if (!ENABLED) return;
		CompiledRuleTree tree = SurfaceValidator.cachedTreeOrNull();
		if (tree == null) return;
		DispatchState st = STATE.get();
		st.resetForChunk(protoChunk, tree);
		// Reset the per-column cache so this chunk starts clean.
		// Validator's column cache is what eliminates the per-Y reflective
		// re-reads — see SurfaceValidator.ColumnCache for rationale.
		SurfaceValidator.resetColumnCache(tree.noiseChannelTable().length);
		st.active = true;
	}

	/** True if the current thread is in an active dispatch batch. The
	 *  validator's tryApply redirect checks this to route enqueue vs
	 *  vanilla. */
	public static boolean batchActive() {
		return ENABLED && STATE.get().active;
	}

	/**
	 * Append one captured position to the batch. Returns true if
	 * enqueued (vanilla should treat as "rule returned null, skip
	 * setBlock"); false if overflow (caller should let vanilla's
	 * tryApply run as fallback).
	 */
	public static boolean enqueue(int x, int y, int z,
			String biome, int runDepth, int stoneAbove, int stoneBelow,
			int fluidHeight, boolean isCold, int surfaceHeight,
			double secondaryDepth, double[] noiseSlice) {
		DispatchState st = STATE.get();
		if (!st.active) return false;
		int idx = st.count;
		if (idx >= CAPACITY) {
			overflowCount++;
			return false;
		}
		st.xs[idx] = x;
		st.ys[idx] = y;
		st.zs[idx] = z;
		st.biomes[idx] = biome;
		st.runDepths[idx] = runDepth;
		st.stoneAboves[idx] = stoneAbove;
		st.stoneBelows[idx] = stoneBelow;
		st.fluidHeights[idx] = fluidHeight;
		st.surfaceHeights[idx] = surfaceHeight;
		st.isColds[idx] = isCold;
		st.secondaryDepths[idx] = secondaryDepth;
		// Noise: copy into the shared per-thread slab at idx × channelCount.
		int channels = st.tree.noiseChannelTable().length;
		if (channels > 0 && noiseSlice != null) {
			int dstBase = idx * channels;
			int n = Math.min(channels, noiseSlice.length);
			System.arraycopy(noiseSlice, 0, st.noiseValues, dstBase, n);
		}
		st.count = idx + 1;
		return true;
	}

	/**
	 * Called from the buildSurface @Inject RETURN. Single JNI dispatch,
	 * walk results, write each non-null block back to the chunk. Then
	 * disarm the per-thread batch.
	 */
	public static void flushChunk() {
		DispatchState st = STATE.get();
		if (!st.active) return;
		st.active = false;
		int n = st.count;
		if (n == 0) return;

		CompiledRuleTree tree = st.tree;
		SurfaceBatchHandoff handoff = st.handoff;
		handoff.setTree(tree);
		handoff.beginBatch(n);

		// Bulk-pack the entire batch in one call. Replaces the allocation-
		// heavy per-column ColumnContext loop with ~10 direct array copies
		// via IntBuffer.put(int[]) / DoubleBuffer.put(double[]).
		handoff.packBulk(
				st.xs, st.ys, st.zs,
				st.runDepths, st.stoneAboves, st.stoneBelows,
				st.fluidHeights, st.surfaceHeights,
				st.secondaryDepths, st.isColds,
				st.biomes, st.noiseValues,
				n);

		handoff.dispatch();

		// Step 2: batched write path. Replaces per-write
		// ProtoChunk.setBlockState (which fires Heightmap.trackUpdate per
		// write × 2 types = ~32K calls/chunk during SURFACE phase) with
		// raw ChunkSection.setBlockState + per-column heightmap batch
		// (~512 trackUpdate calls/chunk). Validated bit-identical against
		// per-write trackUpdate across 21,012 chunks (commit e4e7a41 +
		// SurfaceHeightmapValidator).
		//
		// Three relevant observations from the source audit:
		//   - SurfaceBuilder.buildSurface's redirect target returns null
		//     in batch mode; vanilla's setBlockState is therefore never
		//     called for any enqueued position. flushChunk's writes are
		//     the *only* writes for these positions.
		//   - During SURFACE phase, status < INITIALIZE_LIGHT, so
		//     ProtoChunk.setBlockState's lighting branch is dead — safe
		//     to skip by going straight to ChunkSection.
		//   - Surface rule writes are predicate-preserving (always non-
		//     air, always suffocating), so per-column trackUpdate at the
		//     highest changed Y produces bit-identical heightmap state
		//     to per-write trackUpdate.

		net.minecraft.world.chunk.Chunk c = (net.minecraft.world.chunk.Chunk) st.protoChunk;

		// Heightmap parity validator (Step 1 — kept as regression check).
		// When ON, snapshot pre-flush heightmaps and record per-write
		// states; the post-flush validate() call diffs Path A (per-write
		// trackUpdate, simulated as reference) against Path B (the
		// batched path that actually ran in production).
		boolean validating = SurfaceHeightmapValidator.ENABLED;
		long[] preWSWG = null;
		long[] preOFWG = null;
		if (validating) {
			preWSWG = SurfaceHeightmapValidator.snapshot(c, net.minecraft.world.Heightmap.Type.WORLD_SURFACE_WG);
			preOFWG = SurfaceHeightmapValidator.snapshot(c, net.minecraft.world.Heightmap.Type.OCEAN_FLOOR_WG);
			if (st.writtenStates == null) {
				st.writtenStates = new BlockState[CAPACITY];
			}
			java.util.Arrays.fill(st.writtenStates, 0, n, null);
		}

		// Lazy heightmap init: replicates ProtoChunk.setBlockState's
		// missing-types check. By SURFACE phase the NOISE-phase writes
		// have populated both, so this is normally a no-op (2 cheap
		// hasHeightmap calls). Required for safety when the first surface
		// write would have triggered creation in vanilla.
		java.util.EnumSet<net.minecraft.world.Heightmap.Type> missingTypes = null;
		if (!c.hasHeightmap(net.minecraft.world.Heightmap.Type.WORLD_SURFACE_WG)) {
			missingTypes = java.util.EnumSet.of(net.minecraft.world.Heightmap.Type.WORLD_SURFACE_WG);
		}
		if (!c.hasHeightmap(net.minecraft.world.Heightmap.Type.OCEAN_FLOOR_WG)) {
			if (missingTypes == null) missingTypes = java.util.EnumSet.noneOf(net.minecraft.world.Heightmap.Type.class);
			missingTypes.add(net.minecraft.world.Heightmap.Type.OCEAN_FLOOR_WG);
		}
		if (missingTypes != null) net.minecraft.world.Heightmap.populateHeightmaps(c, missingTypes);

		net.minecraft.world.Heightmap hmWS = c.getHeightmap(net.minecraft.world.Heightmap.Type.WORLD_SURFACE_WG);
		net.minecraft.world.Heightmap hmOF = c.getHeightmap(net.minecraft.world.Heightmap.Type.OCEAN_FLOOR_WG);

		net.minecraft.world.chunk.ChunkSection[] sections = c.getSectionArray();

		// Per-column reduction: highest Y written and the state at that Y.
		// Index = (localX << 4) | localZ; 256 columns max.
		int[] colHighestY = st.colHighestY;
		BlockState[] colState = st.colState;
		java.util.Arrays.fill(colHighestY, Integer.MIN_VALUE);

		int written = 0;
		for (int i = 0; i < n; i++) {
			Object result = handoff.readResult(i);
			if (!(result instanceof BlockState bs)) continue;

			int wx = st.xs[i], wy = st.ys[i], wz = st.zs[i];
			if (c.isOutOfHeightLimit(wy)) continue; // matches vanilla's early return

			net.minecraft.world.chunk.ChunkSection section = sections[c.getSectionIndex(wy)];
			int localX = wx & 15;
			int localY = wy & 15;
			int localZ = wz & 15;
			section.setBlockState(localX, localY, localZ, bs);
			written++;

			int colIdx = (localX << 4) | localZ;
			if (wy > colHighestY[colIdx]) {
				colHighestY[colIdx] = wy;
				colState[colIdx] = bs;
			}

			if (validating) st.writtenStates[i] = bs;
		}

		// Per-column heightmap update — once per column per type at the
		// highest changed Y. Replaces ~32K trackUpdate calls with ~512.
		for (int colIdx = 0; colIdx < 256; colIdx++) {
			int highestY = colHighestY[colIdx];
			if (highestY == Integer.MIN_VALUE) continue;
			int localX = (colIdx >> 4) & 15;
			int localZ = colIdx & 15;
			BlockState topState = colState[colIdx];
			hmWS.trackUpdate(localX, highestY, localZ, topState);
			hmOF.trackUpdate(localX, highestY, localZ, topState);
		}

		if (validating) {
			SurfaceHeightmapValidator.validate(c, st.xs, st.ys, st.zs,
					st.writtenStates, n, preWSWG, preOFWG);
		}

		batchesProcessed++;
		positionsProcessed += n;
		maybeReportDiag(written);

		// Reset count for next chunk on this thread.
		st.count = 0;
	}

	private static synchronized void maybeReportDiag(int writtenLast) {
		long now = System.nanoTime();
		if (now - lastReportNs < REPORT_INTERVAL_NS) return;
		lastReportNs = now;
		ExampleMod.LOGGER.info(
			"[surface-dispatch] batches={} positions={} overflow={} lastBatchWritten={}",
			batchesProcessed, positionsProcessed, overflowCount, writtenLast);
	}
}
