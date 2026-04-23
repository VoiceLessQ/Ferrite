package me.apika.apikaprobe.surface;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

import me.apika.apikaprobe.RustBridge;

/**
 * Reusable per-chunk batched handoff to Rust's surface evaluator.
 *
 * <p>One JNI call per chunk replaces ~1k–60k single-column calls. The
 * input buffers are allocated once per handoff instance (typically per
 * worker thread or per chunk-gen pipeline) and reused across batches —
 * zero allocation per chunk after the first.
 *
 * <p>Buffer layout matches {@code surface_jni.rs::evaluateSurfaceRuleBatch}:
 * parallel arrays of per-column scalars, plus two variable-stride
 * buffers for biome match bits and noise values.
 *
 * <p>Lifecycle:
 * <pre>
 *   SurfaceBatchHandoff h = new SurfaceBatchHandoff();
 *   h.setTree(compiledTree);              // once per world load
 *   h.beginBatch(columnCount);             // once per chunk
 *   for (int c = 0; c &lt; columnCount; c++) {
 *       h.packColumn(c, columnContext);
 *   }
 *   h.dispatch();                          // single JNI call
 *   for (int c = 0; c &lt; columnCount; c++) {
 *       Object state = h.readResult(c);    // null = no rule matched
 *   }
 * </pre>
 *
 * <p>Not thread-safe — one handoff instance per thread. Intended use:
 * {@code ThreadLocal<SurfaceBatchHandoff>} on the chunk-gen worker.
 */
public final class SurfaceBatchHandoff {

	// Per-column scalar buffers. Capacity grows on demand.
	private ByteBuffer blockYs;
	private ByteBuffer runDepths;
	private ByteBuffer stoneAbove;
	private ByteBuffer stoneBelow;
	private ByteBuffer fluidHeights;
	private ByteBuffer surfaceHeights;
	private ByteBuffer flags;
	private ByteBuffer secondaryDepths;

	// Variable-stride buffers (sized by columnCount × structureCount).
	private ByteBuffer biomeMatchBits;
	private ByteBuffer noiseValues;

	// Per-column world-space (x, z). Required by Rust OP_VERT_GRADIENT
	// for the per-block PRNG roll. Sized to columnCount * 4.
	private ByteBuffer xs;
	private ByteBuffer zs;

	// Output: i32 × columnCount.
	private ByteBuffer results;

	// Tree-derived state, set by setTree(). Held as direct ByteBuffer
	// (bytecode is per-tree constant — pack once, reuse per chunk).
	private CompiledRuleTree tree;
	private ByteBuffer bytecodeBuf;
	private int biomeSetCount;
	private int noiseChannelCount;

	private int currentColumnCount;
	private int currentNoiseStride; // = noiseChannelCount per column

	public void setTree(CompiledRuleTree tree) {
		this.tree = tree;
		byte[] bc = tree.bytecode();
		this.bytecodeBuf = direct(bc.length);
		this.bytecodeBuf.put(bc).flip();
		this.biomeSetCount = tree.biomeSetTable().length;
		this.noiseChannelCount = tree.noiseChannelTable().length;
	}

	public CompiledRuleTree tree() { return tree; }

	/**
	 * Reset position pointers and (re)size buffers for a new batch.
	 * Re-uses existing direct memory when capacity is sufficient.
	 */
	public void beginBatch(int columnCount) {
		this.currentColumnCount = columnCount;
		this.currentNoiseStride = noiseChannelCount;

		blockYs        = ensureCapacity(blockYs,        columnCount * 4);
		runDepths      = ensureCapacity(runDepths,      columnCount * 4);
		stoneAbove     = ensureCapacity(stoneAbove,     columnCount * 4);
		stoneBelow     = ensureCapacity(stoneBelow,     columnCount * 4);
		fluidHeights   = ensureCapacity(fluidHeights,   columnCount * 4);
		surfaceHeights = ensureCapacity(surfaceHeights, columnCount * 4);
		flags          = ensureCapacity(flags,          columnCount);
		secondaryDepths= ensureCapacity(secondaryDepths,columnCount * 8);
		biomeMatchBits = ensureCapacity(biomeMatchBits, columnCount * Math.max(1, biomeSetCount));
		noiseValues    = ensureCapacity(noiseValues,    columnCount * Math.max(1, noiseChannelCount) * 8);
		xs             = ensureCapacity(xs,             columnCount * 4);
		zs             = ensureCapacity(zs,             columnCount * 4);
		results        = ensureCapacity(results,        columnCount * 4);

		blockYs.clear();
		runDepths.clear();
		stoneAbove.clear();
		stoneBelow.clear();
		fluidHeights.clear();
		surfaceHeights.clear();
		flags.clear();
		secondaryDepths.clear();
		biomeMatchBits.clear();
		noiseValues.clear();
		xs.clear();
		zs.clear();
		results.clear();
	}

	/**
	 * Set this column's world-space (x, z). Required alongside
	 * {@link #packColumn} for batches that contain VerticalGradient
	 * rules — Rust's OP_VERT_GRADIENT calls
	 * {@code XoroshiroPositionalRandomFactory.at(x, y, z).nextFloat()}
	 * which needs the (x, y, z) triple. The y comes from the column's
	 * blockY (already packed by packColumn).
	 */
	public void packColumnXZ(int col, int x, int z) {
		xs.putInt(col * 4, x);
		zs.putInt(col * 4, z);
	}

	/**
	 * Write one column's input into the parallel arrays at index {@code col}.
	 * Caller must have called {@link #beginBatch(int)} first.
	 */
	public void packColumn(int col, ColumnContext ctx) {
		blockYs.putInt(col * 4, ctx.blockY());
		runDepths.putInt(col * 4, ctx.runDepth());
		stoneAbove.putInt(col * 4, ctx.stoneDepthAbove());
		stoneBelow.putInt(col * 4, ctx.stoneDepthBelow());
		fluidHeights.putInt(col * 4, ctx.fluidHeight());
		surfaceHeights.putInt(col * 4, ctx.surfaceHeight());
		secondaryDepths.putDouble(col * 8, ctx.secondaryDepth());
		flags.put(col, (byte) ((ctx.isCold() ? 0x01 : 0) | (ctx.isSteep() ? 0x02 : 0)));

		// Biome match bits — one byte per pool entry, 1 if column biome ∈ entry.
		String biome = ctx.biomeName();
		java.util.List<String>[] table = tree.biomeSetTable();
		int bitsBase = col * biomeSetCount;
		for (int i = 0; i < biomeSetCount; i++) {
			biomeMatchBits.put(bitsBase + i, (byte) (table[i].contains(biome) ? 1 : 0));
		}

		// Noise values — pre-sampled by caller, packed contiguously.
		double[] noise = ctx.noiseValues();
		int noiseBase = col * currentNoiseStride * 8;
		int noiseLen = Math.min(noise == null ? 0 : noise.length, currentNoiseStride);
		for (int i = 0; i < noiseLen; i++) {
			noiseValues.putDouble(noiseBase + i * 8, noise[i]);
		}
	}

	/**
	 * Single JNI call. Fills {@link #results} in place.
	 */
	public void dispatch() {
		if (!RustBridge.NATIVE_AVAILABLE || tree == null) {
			// Fill results with -1 sentinel so readResult returns null per column.
			for (int i = 0; i < currentColumnCount; i++) {
				results.putInt(i * 4, -1);
			}
			return;
		}
		// Per-tree factory seeds buffer, set on the validator side from
		// the splitter cache. Null/0-count → Rust falls back to midpoint
		// for OP_VERT_GRADIENT (legacy path; matches the spike behavior
		// for trees without VerticalGradient rules).
		java.nio.ByteBuffer factorySeedsBuf = SurfaceValidator.cachedFactorySeedsBuf();
		int factorySeedCount = SurfaceValidator.cachedFactorySeedCount();

		RustBridge.evaluateSurfaceRuleBatch(
				bytecodeBuf, tree.bytecode().length,
				biomeSetCount, noiseChannelCount,
				biomeMatchBits, blockYs, runDepths,
				stoneAbove, stoneBelow, fluidHeights,
				surfaceHeights, flags, secondaryDepths,
				noiseValues, xs, zs,
				factorySeedsBuf, factorySeedCount,
				results, currentColumnCount);
	}

	/**
	 * Resolve one column's result back to the blockstate object via the
	 * tree's blockstate table, or null if no rule matched.
	 */
	public Object readResult(int col) {
		int id = results.getInt(col * 4);
		Object[] table = tree.blockstateTable();
		if (id < 0 || id >= table.length) return null;
		return table[id];
	}

	// --- helpers ---------------------------------------------------------

	private static ByteBuffer direct(int bytes) {
		return ByteBuffer.allocateDirect(Math.max(1, bytes)).order(ByteOrder.LITTLE_ENDIAN);
	}

	private static ByteBuffer ensureCapacity(ByteBuffer existing, int neededBytes) {
		if (existing != null && existing.capacity() >= neededBytes) {
			return existing;
		}
		// Grow with headroom (1.5x) so repeated grows don't thrash on
		// gradual chunkgen workload increases.
		int newCap = Math.max(neededBytes, (existing == null ? 0 : existing.capacity()) * 3 / 2);
		return direct(newCap);
	}
}
