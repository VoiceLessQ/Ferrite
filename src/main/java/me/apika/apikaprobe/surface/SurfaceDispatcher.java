package me.apika.apikaprobe.surface;

import me.apika.apikaprobe.ExampleMod;
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
	private static final int CAPACITY = 32_768;

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

		int channels = tree.noiseChannelTable().length;
		double[] noiseSlab = st.noiseValues;
		// Build per-position ColumnContext objects only at flush time
		// so the hot enqueue path stays allocation-free. The packColumn
		// API takes ColumnContext today; a future optimization can add
		// a packColumnRaw overload to skip this allocation pass.
		double[] noisePerCol = channels > 0 ? new double[channels] : new double[0];
		for (int i = 0; i < n; i++) {
			if (channels > 0) {
				System.arraycopy(noiseSlab, i * channels, noisePerCol, 0, channels);
			}
			ColumnContext ctx = new ColumnContext(
					st.biomes[i], st.ys[i],
					st.runDepths[i], st.stoneAboves[i], st.stoneBelows[i],
					st.fluidHeights[i], st.isColds[i], false, // isSteep placeholder
					st.surfaceHeights[i], st.secondaryDepths[i],
					noisePerCol);
			handoff.packColumn(i, ctx);
			// Pack world-space (x, z) so Rust's OP_VERT_GRADIENT can do
			// the per-block PRNG roll. Without this, Rust falls back to
			// midpoint at (0, y, 0) which would deterministically misroll.
			handoff.packColumnXZ(i, st.xs[i], st.zs[i]);
		}

		handoff.dispatch();

		// Walk results, write to chunk via reflection (protoChunk is
		// ChunkAccess in mojmap / Chunk in yarn).
		java.lang.reflect.Method setBlockStateMethod = SurfaceValidator.cachedSetBlockStateMethod(st.protoChunk);
		boolean needsExtraArg = SurfaceValidator.cachedSetBlockStateNeedsExtraArg();
		Class<?> extraType = needsExtraArg ? setBlockStateMethod.getParameterTypes()[2] : null;
		BlockPos.Mutable pos = new BlockPos.Mutable();
		int written = 0;
		for (int i = 0; i < n; i++) {
			Object result = handoff.readResult(i);
			if (!(result instanceof BlockState bs)) continue;
			pos.set(st.xs[i], st.ys[i], st.zs[i]);
			try {
				if (!needsExtraArg) {
					// Vanilla's BlockColumn writes via 2-arg form on this path.
					setBlockStateMethod.invoke(st.protoChunk, pos, bs);
				} else if (extraType == int.class) {
					setBlockStateMethod.invoke(st.protoChunk, pos, bs, 0);
				} else {
					setBlockStateMethod.invoke(st.protoChunk, pos, bs, false);
				}
				written++;
			} catch (ReflectiveOperationException | RuntimeException e) {
				// Best-effort write; if it fails, vanilla's terrain block stays.
			}
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
