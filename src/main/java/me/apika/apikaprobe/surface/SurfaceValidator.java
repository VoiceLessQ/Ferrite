package me.apika.apikaprobe.surface;

import java.util.concurrent.atomic.AtomicLong;

import me.apika.apikaprobe.bridge.ExampleMod;

/**
 * Live diff-and-log validator for the bytecode evaluator.
 *
 * Hooked into {@code SurfaceBuilder.buildSurface} via
 * {@code SurfaceValidatorMixin}. When enabled, every Nth call to the
 * top-level {@code BlockStateRule.tryApply(x, y, z)} runs the
 * compiled-bytecode evaluator with the same column inputs, diffs the
 * result against vanilla's, and logs mismatches.
 *
 * <h3>Sampling</h3>
 * 1-in-{@link #SAMPLE_EVERY_N} sampling (default 1000). The hot loop
 * fires 16K-65K times per chunk per PROFILING.md, so per-call
 * validation would tank chunkgen. Sampling at this rate yields
 * thousands of comparisons per minute of active play — enough to
 * surface systematic mismatches without measurable overhead.
 *
 * <h3>Spike scope</h3>
 * Context extraction from {@code MaterialRuleContext} is best-effort
 * reflection. A handful of fields are placeholders (isCold, isSteep,
 * surfaceHeight) — those will mismatch in their respective conditions
 * and that's intentional. The diff lines tell us what to fix next.
 *
 * <h3>Lifecycle</h3>
 * Tree is installed via {@code /ferrite surface validate} (compiles
 * the active world's surface rule and stores it here). When
 * {@link #cachedTree} is non-null, the mixin is live. {@code /ferrite
 * surface validate-off} clears it.
 */
public final class SurfaceValidator {

	private SurfaceValidator() {}

	private static final int SAMPLE_EVERY_N = 1000;
	private static final long REPORT_INTERVAL_TICKS = 100; // 5 seconds @ 20 TPS

	private static volatile CompiledRuleTree cachedTree = null;

	/** When true, the next mismatch the validator sees triggers a full
	 *  opcode trace dump from the Java evaluator, then this flag clears
	 *  itself. Set via /ferrite surface trace-next. */
	public static volatile boolean traceNextMismatch = false;

	/** One-shot diagnostic flag — fires on the first sample after install
	 *  to verify reflective field/method access against the live
	 *  MaterialRuleContext. Logs whether each read succeeded and what
	 *  value it returned. Resets on uninstall/install. */
	private static final java.util.concurrent.atomic.AtomicBoolean diagDumped =
			new java.util.concurrent.atomic.AtomicBoolean(false);

	/** Cache of {@code DoublePerlinNoiseSampler} references aligned with
	 *  {@link CompiledRuleTree#noiseChannelTable()}. Populated lazily on
	 *  the first sample after install — needs the live MaterialRuleContext
	 *  to walk to NoiseConfig.getOrCreateSampler. Stored as Object[] so
	 *  this file doesn't need yarn type imports. Null entries indicate a
	 *  channel that failed to resolve (sample reads 0.0, logged once). */
	private static volatile Object[] cachedSamplers = null;

	/** Direct ByteBuffer of (seedLo, seedHi) pairs extracted from each
	 *  cached splitter, parallel to {@link CompiledRuleTree#randomNameTable()}.
	 *  Built once at first dispatch flush; passed to Rust per JNI call so
	 *  the Rust evaluator can build {@code XoroshiroPositionalRandomFactory}
	 *  without per-call seed marshalling. Layout: 2 × N i64 little-endian
	 *  = 16N bytes. */
	private static volatile java.nio.ByteBuffer cachedFactorySeedsBuf = null;
	private static volatile int cachedFactorySeedCount = 0;

	/** Cache of {@code RandomSplitter} references aligned with
	 *  {@link CompiledRuleTree#randomNameTable()}. Same lifecycle and
	 *  reset semantics as {@link #cachedSamplers}. Drives the per-block
	 *  PRNG inside OP_VERT_GRADIENT (bedrock floor + deepslate transition).
	 *  Null entries fall back to the evaluator's midpoint approximation
	 *  for that one rule. */
	private static volatile Object[] cachedSplitters = null;
	/** Cached reflective Method handles, set together with cachedSplitters
	 *  so the per-sample call only does two virtual dispatches. */
	private static volatile java.lang.reflect.Method splitterSplitMethod = null;
	private static volatile java.lang.reflect.Method randomNextFloatMethod = null;
	/** Closure passed to the evaluator. Built once per install. */
	private static volatile SurfaceRuleEvaluator.VerticalGradientSampler cachedSamplerClosure = null;

	/** Captured per-thread MaterialRuleContext receiver, set by the
	 *  initVerticalContext redirect just before tryApply fires. */
	private static final ThreadLocal<Object> threadCtxRef = new ThreadLocal<>();
	/** Captured per-thread vertical-state ints (blockY, fluidHeight,
	 *  stoneDepthBelow, stoneDepthAbove, runDepth, secondaryDepth). */
	private static final ThreadLocal<int[]> threadVertState = new ThreadLocal<>();

	public static void captureVerticalContext(Object ctx, int blockY, int fluidHeight,
			int stoneDepthBelow, int stoneDepthAbove, int runDepth, int sixth) {
		threadCtxRef.set(ctx);
		threadVertState.set(new int[]{blockY, fluidHeight, stoneDepthBelow, stoneDepthAbove, runDepth, sixth});
	}

	private static final AtomicLong sampleCounter = new AtomicLong();
	private static final AtomicLong totalSamples = new AtomicLong();
	private static final AtomicLong matches = new AtomicLong();
	private static final AtomicLong mismatches = new AtomicLong();
	private static final AtomicLong nullVanilla = new AtomicLong();
	private static final AtomicLong evalNull = new AtomicLong();
	private static final AtomicLong contextBuildFails = new AtomicLong();
	// Three-way diff counters (Java vs Rust). Independent of vanilla diff.
	private static final AtomicLong rustSamples = new AtomicLong();
	private static final AtomicLong rustJavaAgreement = new AtomicLong();
	private static final AtomicLong rustJavaDivergence = new AtomicLong();
	private static long lastReportTick = 0;

	public static boolean isEnabled() {
		return cachedTree != null;
	}

	/** Accessor for the dispatcher — returns the installed compiled tree
	 *  or null if no /ferrite surface validate has been issued. */
	public static CompiledRuleTree cachedTreeOrNull() {
		return cachedTree;
	}

	/** Returns the per-thread MaterialRuleContext captured by the
	 *  initVerticalContext redirect at the start of this Y iteration.
	 *  Null if no capture has happened on this thread yet. Used by
	 *  the dispatcher to read context fields. */
	public static Object capturedLiveCtx() {
		return threadCtxRef.get();
	}

	// ============================================================
	// Per-column cache for the batched dispatcher (Opt B).
	//
	// In vanilla SurfaceRules.Context, several fields are set in
	// updateXZ() (per-column) and stay constant across all Ys in that
	// column — runDepth (yarn name; mojmap surfaceDepth), secondaryDepth
	// (mojmap getSurfaceSecondary), and minSurfaceLevel (mojmap, lazy
	// per-XZ memoized). Vanilla's NoiseThresholdConditionSource also
	// hardcodes y=0 for noise sampling, so noise channels are
	// effectively 2D and per-(x, z) only.
	//
	// Without this cache, the dispatch fast path re-reads all of these
	// per Y — ~70K redundant reflective calls per chunk on a typical
	// overworld walk. With the cache, each column does ~4 reflective
	// reads + 7 noise samples once on its first Y, then every
	// subsequent Y reads from primitive arrays.
	//
	// Reset by SurfaceDispatcher.beginChunk via resetColumnCache.
	// 16×16 = 256 column slots per chunk.
	// ============================================================
	static final class ColumnCache {
		final boolean[] valid             = new boolean[256];
		final int[]     runDepths         = new int[256];
		final double[]  secondaryDepths   = new double[256];
		final int[]     surfaceHeights    = new int[256];
		double[]        noiseSlab         = null; // 256 × channelCount; sized lazily
		int             channelCount      = 0;

		void reset(int channels) {
			java.util.Arrays.fill(valid, false);
			if (noiseSlab == null || channelCount != channels) {
				noiseSlab = new double[256 * Math.max(1, channels)];
				channelCount = channels;
			}
		}
	}

	private static final ThreadLocal<ColumnCache> columnCache =
			ThreadLocal.withInitial(ColumnCache::new);

	/** Per-thread scratch noise array reused across enqueue calls so
	 *  the dispatch path doesn't allocate a fresh double[] per (x, y, z). */
	private static final ThreadLocal<double[]> noiseScratch =
			ThreadLocal.withInitial(() -> new double[16]);

	/** Called from {@link SurfaceDispatcher#beginChunk} so each chunk
	 *  starts with a clean per-thread cache. */
	public static void resetColumnCache(int channelCount) {
		columnCache.get().reset(channelCount);
	}

	// ============================================================
	// Fast-path context extraction for the batched dispatcher.
	//
	// Opt A architecture: replace per-call Method.invoke chains with
	// either (a) direct typed Java calls when the receiver type is
	// public yarn-mapped, or (b) MethodHandle.invokeExact when the
	// receiver is package-private (MaterialRuleContext).
	//
	// Per-call cost dropped from ~1.5us (Method.invoke chains, ~7-10
	// reflective hops) to ~130ns (1 Field.get + ~5 direct virtual
	// calls + 1 MethodHandle.invokeExact). On 17K positions per chunk
	// that's ~25ms saved.
	//
	// Direct-call path uses public yarn types: Supplier, RegistryEntry,
	// RegistryKey, Optional, Biome, BlockPos. Field reads still use
	// java.lang.reflect.Field because the field lives on a package-
	// private class.
	// ============================================================
	private static volatile java.lang.reflect.Field fldRunDepth = null;
	private static volatile java.lang.reflect.Field fldBiomeSupplier = null;

	/** MethodHandles for the package-private MaterialRuleContext methods.
	 *  Resolved at first dispatch, asType'd to take Object as receiver
	 *  so the invoke site doesn't need to know the concrete ctx class. */
	private static volatile java.lang.invoke.MethodHandle mhGetSecondaryDepth = null;
	private static volatile java.lang.invoke.MethodHandle mhEstimateSurfaceHeight = null;
	private static volatile java.lang.invoke.MethodHandle mhGetSeaLevel = null;
	/** Field getters as MethodHandles so the per-(x,y,z) read is a
	 *  direct invokeExact (~30ns) instead of Field.getInt/get (~200ns). */
	private static volatile java.lang.invoke.MethodHandle mhRunDepth = null;
	private static volatile java.lang.invoke.MethodHandle mhBiomeSupplier = null;

	private static void ensureFastReaderHandles(Object liveCtx) {
		if (fldRunDepth != null) return; // already resolved
		try {
			Class<?> ctxClass = liveCtx.getClass();
			java.lang.reflect.Field rd = findFieldDeep(ctxClass, "runDepth");
			if (rd != null) { rd.setAccessible(true); fldRunDepth = rd; }
			java.lang.reflect.Field bs = findSupplierFieldClass(ctxClass);
			if (bs != null) { bs.setAccessible(true); fldBiomeSupplier = bs; }

			// Field-getter MethodHandles — fast-path replacement for the
			// Field.getInt / Field.get calls in the hot per-(x,y,z) loop.
			java.lang.invoke.MethodHandles.Lookup lookup0 =
					java.lang.invoke.MethodHandles.lookup();
			if (rd != null) {
				mhRunDepth = lookup0.unreflectGetter(rd).asType(
						java.lang.invoke.MethodType.methodType(int.class, Object.class));
			}
			if (bs != null) {
				mhBiomeSupplier = lookup0.unreflectGetter(bs).asType(
						java.lang.invoke.MethodType.methodType(Object.class, Object.class));
			}

			// MethodHandles for the 3 ctx methods we call per Y / per column.
			// asType to (Object) → primitive so the call site doesn't need
			// to know the concrete ctx class — invokeExact is still direct.
			java.lang.invoke.MethodHandles.Lookup lookup = java.lang.invoke.MethodHandles.lookup();
			java.lang.invoke.MethodHandle mhSecondary = lookup.unreflect(
					ctxClass.getMethod("getSecondaryDepth"));
			mhGetSecondaryDepth = mhSecondary.asType(
					java.lang.invoke.MethodType.methodType(double.class, Object.class));
			java.lang.invoke.MethodHandle mhSurfaceH = lookup.unreflect(
					ctxClass.getMethod("estimateSurfaceHeight"));
			mhEstimateSurfaceHeight = mhSurfaceH.asType(
					java.lang.invoke.MethodType.methodType(int.class, Object.class));
			java.lang.invoke.MethodHandle mhSea = lookup.unreflect(
					ctxClass.getMethod("getSeaLevel"));
			mhGetSeaLevel = mhSea.asType(
					java.lang.invoke.MethodType.methodType(int.class, Object.class));
		} catch (ReflectiveOperationException e) {
			ExampleMod.LOGGER.warn("[surface-dispatch] fast reader resolve failed: {}", e.toString());
		}
	}

	/** Walk superclass chain looking for a Supplier-typed field. Caches
	 *  the Field object the same way readBiomeName's findSupplierField
	 *  does, but returns the field rather than the value. */
	private static java.lang.reflect.Field findSupplierFieldClass(Class<?> c) {
		while (c != null && c != Object.class) {
			for (java.lang.reflect.Field f : c.getDeclaredFields()) {
				if (java.lang.reflect.Modifier.isStatic(f.getModifiers())) continue;
				if (java.util.function.Supplier.class.isAssignableFrom(f.getType())) {
					return f;
				}
			}
			c = c.getSuperclass();
		}
		return null;
	}

	private static java.lang.reflect.Field findFieldDeep(Class<?> c, String name) {
		while (c != null && c != Object.class) {
			for (java.lang.reflect.Field f : c.getDeclaredFields()) {
				if (f.getName().equals(name)) return f;
			}
			c = c.getSuperclass();
		}
		return null;
	}

	/**
	 * Per-position fast read for the batched dispatcher. Reads everything
	 * needed off the live MaterialRuleContext using cached handles, packs
	 * it into the dispatcher's per-thread arrays. Caller (the tryApply
	 * redirect) hands in the (x, y, z) and the captured stoneAbove/Below/
	 * fluidHeight from the initVerticalContext args.
	 *
	 * @return true if successfully enqueued; false if dispatcher overflowed
	 *         or we couldn't read context (caller should fall through to
	 *         vanilla tryApply for this one position).
	 */
	public static boolean dispatchEnqueue(Object liveCtx, int x, int y, int z) {
		if (liveCtx == null) return false;
		int[] vert = threadVertState.get();
		if (vert == null) return false;
		ensureFastReaderHandles(liveCtx);

		// Vert args, decoded order from initVerticalContext: vert[0]=stoneAbove,
		// vert[1]=stoneBelow, vert[2]=fluidHeight, vert[3..5]=blockX/Y/Z.
		int stoneAbove   = vert[0];
		int stoneBelow   = vert[1];
		int fluidHeight  = vert[2];

		// Per-column cache lookup. First Y in a column does the
		// reflective read + noise sample; subsequent Ys read primitives.
		ColumnCache cc = columnCache.get();
		int cellIdx = ((x & 15) << 4) | (z & 15);
		int channels = cc.channelCount;

		int runDepth;
		double secondary;
		int surfaceH;
		if (cc.valid[cellIdx]) {
			runDepth  = cc.runDepths[cellIdx];
			secondary = cc.secondaryDepths[cellIdx];
			surfaceH  = cc.surfaceHeights[cellIdx];
		} else {
			runDepth  = fastReadIntField(liveCtx);
			secondary = fastReadDoubleMH(liveCtx, mhGetSecondaryDepth);
			surfaceH  = fastReadIntMH(liveCtx, mhEstimateSurfaceHeight, y);

			// Sample noise channels into the cache slab. Vanilla samples
			// at y=0 → identical for every Y in this column.
			if (channels > 0) {
				double[] sampled = sampleNoiseChannels(liveCtx, x, z);
				int copy = Math.min(sampled.length, channels);
				System.arraycopy(sampled, 0, cc.noiseSlab, cellIdx * channels, copy);
			}

			cc.runDepths[cellIdx]       = runDepth;
			cc.secondaryDepths[cellIdx] = secondary;
			cc.surfaceHeights[cellIdx]  = surfaceH;
			cc.valid[cellIdx]           = true;
		}

		// Copy this column's noise slice into the per-thread scratch
		// (dispatcher will copy from here into its own slab anyway).
		double[] noise = noiseScratch.get();
		if (channels > 0 && (noise.length < channels)) {
			noise = new double[channels];
			noiseScratch.set(noise);
		}
		if (channels > 0) {
			System.arraycopy(cc.noiseSlab, cellIdx * channels, noise, 0, channels);
		}

		// Per-Y reads (biome supplier is set in vanilla's updateY, so it can
		// vary by Y for cave-biome boundaries — keep these per-Y for safety).
		String biome   = fastReadBiomeName(liveCtx);
		boolean isCold = fastReadIsCold(liveCtx, x, y, z, biome);

		return SurfaceDispatcher.enqueue(
				x, y, z,
				biome, runDepth, stoneAbove, stoneBelow,
				fluidHeight, isCold, surfaceH, secondary, noise);
	}

	private static int fastReadInt(Object o, java.lang.reflect.Field f) {
		if (f == null) return 0;
		try { return f.getInt(o); } catch (ReflectiveOperationException e) { return 0; }
	}

	/** Fast-path int-field read via MethodHandle.invokeExact (~30ns)
	 *  with a Field.getInt fallback if the MH isn't resolved yet. */
	private static int fastReadIntField(Object o) {
		java.lang.invoke.MethodHandle mh = mhRunDepth;
		if (mh != null) {
			try { return (int) mh.invokeExact(o); }
			catch (Throwable t) { /* fall through */ }
		}
		return fastReadInt(o, fldRunDepth);
	}

	/** Fast-path Object-field read via MethodHandle.invokeExact. */
	private static Object fastReadObjectField(Object o, java.lang.invoke.MethodHandle mh,
			java.lang.reflect.Field fallback) {
		if (mh != null) {
			try { return mh.invokeExact(o); }
			catch (Throwable t) { /* fall through */ }
		}
		if (fallback != null) {
			try { return fallback.get(o); } catch (ReflectiveOperationException ignored) {}
		}
		return null;
	}

	/** Opt A: MethodHandle.invokeExact, asType'd to (Object) -> int.
	 *  ~30ns per call (warm) vs Method.invoke at ~200ns. */
	private static int fastReadIntMH(Object o, java.lang.invoke.MethodHandle mh, int dflt) {
		if (mh == null) return dflt;
		try {
			return (int) mh.invokeExact(o);
		} catch (Throwable t) { return dflt; }
	}

	private static double fastReadDoubleMH(Object o, java.lang.invoke.MethodHandle mh) {
		if (mh == null) return 0.0;
		try {
			return (double) mh.invokeExact(o);
		} catch (Throwable t) { return 0.0; }
	}

	/**
	 * Opt A: read biome name via direct typed Java. The supplier field is
	 * the only reflective bit (its type is package-private). The chain
	 * Supplier → RegistryEntry → RegistryKey → Identifier → toString uses
	 * public yarn classes — direct virtual calls JITed inline.
	 */
	private static String fastReadBiomeName(Object liveCtx) {
		Object supplier = fastReadObjectField(liveCtx, mhBiomeSupplier, fldBiomeSupplier);
		if (!(supplier instanceof java.util.function.Supplier<?> s)) return "unknown";
		try {
			Object entry = s.get();
			if (!(entry instanceof net.minecraft.registry.entry.RegistryEntry<?> regEntry)) return "unknown";
			java.util.Optional<? extends net.minecraft.registry.RegistryKey<?>> keyOpt = regEntry.getKey();
			if (!keyOpt.isPresent()) return "unknown";
			net.minecraft.util.Identifier id = keyOpt.get().getValue();
			return id == null ? "unknown" : id.toString();
		} catch (RuntimeException e) {
			return "unknown";
		}
	}

	/**
	 * Opt A: read isCold via direct typed Java. Same supplier field, then
	 * RegistryEntry.value() → Biome (public yarn). coldEnoughToSnow is a
	 * direct method call. Only getSeaLevel uses MethodHandle.invokeExact
	 * (package-private MaterialRuleContext method).
	 */
	private static boolean fastReadIsCold(Object liveCtx, int x, int y, int z, String biomeName) {
		java.lang.invoke.MethodHandle mhSea = mhGetSeaLevel;
		if (mhSea == null) return false;
		Object supplier = fastReadObjectField(liveCtx, mhBiomeSupplier, fldBiomeSupplier);
		if (!(supplier instanceof java.util.function.Supplier<?> s)) return false;
		try {
			Object entry = s.get();
			if (!(entry instanceof net.minecraft.registry.entry.RegistryEntry<?> regEntry)) return false;
			Object biomeRaw = regEntry.value();
			if (!(biomeRaw instanceof net.minecraft.world.biome.Biome biome)) return false;
			net.minecraft.util.math.BlockPos pos = new net.minecraft.util.math.BlockPos(x, y, z);
			int seaLevel = (int) mhSea.invokeExact(liveCtx);
			// Yarn 1.21.11: Biome.isCold(BlockPos, int) → boolean.
			// Mojmap name is coldEnoughToSnow — same method.
			return biome.isCold(pos, seaLevel);
		} catch (Throwable t) {
			return false;
		}
	}

	// fastSampleNoise removed — Opt B replaced its single caller with a
	// per-column-cached read. sampleNoiseChannels (the helper it wrapped)
	// is still called directly by dispatchEnqueue on first-Y-per-column
	// to populate the cache slab.

	/** Cached reflective handle for chunk.setBlockState. Vanilla's
	 *  BlockColumn wrapper inside SurfaceSystem.buildSurface uses the
	 *  2-arg form ({@code protoChunk.setBlockState(BlockPos, BlockState)}),
	 *  so we mirror that. ProtoChunk also has a 3-arg flags overload but
	 *  the 2-arg one is what vanilla itself uses on this code path. */
	private static volatile java.lang.reflect.Method cachedSetBlockStateMethod = null;
	private static volatile boolean cachedSetBlockStateExtraArg = false; // false=2-arg, true=3-arg int flags

	public static java.lang.reflect.Method cachedSetBlockStateMethod(Object protoChunk) {
		java.lang.reflect.Method m = cachedSetBlockStateMethod;
		if (m != null) return m;

		// First pass: 2-arg (BlockPos, BlockState) — matches vanilla's
		// BlockColumn write. Walk inherited methods via getMethods().
		for (java.lang.reflect.Method candidate : protoChunk.getClass().getMethods()) {
			if (!candidate.getName().equals("setBlockState")) continue;
			Class<?>[] params = candidate.getParameterTypes();
			if (params.length != 2) continue;
			if (!params[0].getName().endsWith("BlockPos")) continue;
			if (!params[1].getName().endsWith("BlockState")) continue;
			candidate.setAccessible(true);
			cachedSetBlockStateExtraArg = false;
			cachedSetBlockStateMethod = candidate;
			return candidate;
		}

		// Fallback: 3-arg with int flags — Block.NOTIFY_ALL semantics not
		// needed during chunkgen, just pass 0.
		for (java.lang.reflect.Method candidate : protoChunk.getClass().getMethods()) {
			if (!candidate.getName().equals("setBlockState")) continue;
			Class<?>[] params = candidate.getParameterTypes();
			if (params.length != 3) continue;
			if (!params[0].getName().endsWith("BlockPos")) continue;
			if (!params[1].getName().endsWith("BlockState")) continue;
			if (params[2] != int.class && params[2] != boolean.class) continue;
			candidate.setAccessible(true);
			cachedSetBlockStateExtraArg = true;
			cachedSetBlockStateMethod = candidate;
			return candidate;
		}

		throw new IllegalStateException(
			"setBlockState(BlockPos, BlockState[, int|boolean]) not found on " + protoChunk.getClass());
	}

	/** True if the cached setBlockState method needs a third arg (int flags
	 *  or boolean moved). Caller passes 0 / false. */
	public static boolean cachedSetBlockStateNeedsExtraArg() {
		return cachedSetBlockStateExtraArg;
	}

	/** MethodHandle wrapper of the cached setBlockState method.
	 *  Already has the optional third arg bound (0 / false) so the
	 *  call-site signature is always {@code (Object chunk, Object pos,
	 *  Object state) -> void}. Replaces Method.invoke (~300ns)
	 *  with MethodHandle.invokeExact (~30ns) per write. */
	private static volatile java.lang.invoke.MethodHandle mhSetBlockState = null;

	public static java.lang.invoke.MethodHandle mhSetBlockState(Object protoChunk) {
		java.lang.invoke.MethodHandle existing = mhSetBlockState;
		if (existing != null) return existing;
		java.lang.reflect.Method m = cachedSetBlockStateMethod(protoChunk);
		try {
			java.lang.invoke.MethodHandles.Lookup lookup = java.lang.invoke.MethodHandles.lookup();
			java.lang.invoke.MethodHandle mh = lookup.unreflect(m);
			Class<?>[] params = m.getParameterTypes();
			if (params.length == 3) {
				if (params[2] == int.class) {
					mh = java.lang.invoke.MethodHandles.insertArguments(mh, 3, 0);
				} else {
					mh = java.lang.invoke.MethodHandles.insertArguments(mh, 3, false);
				}
			}
			// Drop return value (BlockState) and erase to (Object, Object, Object) -> void.
			mh = mh.asType(java.lang.invoke.MethodType.methodType(
					void.class, Object.class, Object.class, Object.class));
			mhSetBlockState = mh;
			return mh;
		} catch (IllegalAccessException e) {
			throw new IllegalStateException("unreflect setBlockState failed", e);
		}
	}

	public static void install(CompiledRuleTree tree) {
		cachedTree = tree;
		resetStats();
		diagDumped.set(false); // re-arm the one-shot diagnostic dump
		cachedSamplers = null; // invalidate noise-sampler cache (new tree may have different channels)
		cachedSplitters = null; // invalidate gradient-PRNG cache (parallel to samplers)
		cachedSamplerClosure = null;
		splitterSplitMethod = null;
		randomNextFloatMethod = null;
		cachedFactorySeedsBuf = null; // invalidate Rust-side factory seeds buffer
		cachedFactorySeedCount = 0;
		ExampleMod.LOGGER.info("[surface-validate] installed compiled tree (bytecode={} bytes, hasFallback={})",
				tree.bytecode().length, tree.hasFallback());
	}

	public static void uninstall() {
		cachedTree = null;
		ExampleMod.LOGGER.info("[surface-validate] uninstalled — final stats: " + statsLine());
	}

	private static void resetStats() {
		sampleCounter.set(0);
		totalSamples.set(0);
		matches.set(0);
		mismatches.set(0);
		nullVanilla.set(0);
		evalNull.set(0);
		contextBuildFails.set(0);
		rustSamples.set(0);
		rustJavaAgreement.set(0);
		rustJavaDivergence.set(0);
	}

	public static String statsLine() {
		long total = totalSamples.get();
		long m = matches.get();
		long mm = mismatches.get();
		long nv = nullVanilla.get();
		long en = evalNull.get();
		long cf = contextBuildFails.get();
		double rate = total == 0 ? 0.0 : (100.0 * m / total);
		long rs = rustSamples.get();
		long ra = rustJavaAgreement.get();
		long rd = rustJavaDivergence.get();
		double javaRustRate = rs == 0 ? 0.0 : (100.0 * ra / rs);
		return String.format(
				"[surface-validate] samples=%d match=%.1f%% mismatches=%d nullVanilla=%d evalNull=%d ctxBuildFails=%d | rust samples=%d java=rust=%.1f%% divergences=%d",
				total, rate, mm, nv, en, cf, rs, javaRustRate, rd);
	}

	/**
	 * Per-server-tick reporter hook. Logs the stats line every
	 * {@link #REPORT_INTERVAL_TICKS} ticks while the validator is
	 * enabled. Currently unused — wire from a tick mixin if desired.
	 */
	public static void onServerTick(long currentTick) {
		if (!isEnabled()) return;
		if (currentTick - lastReportTick < REPORT_INTERVAL_TICKS) return;
		lastReportTick = currentTick;
		ExampleMod.LOGGER.info(statsLine());
	}

	/**
	 * Dispatch-mode entry point. When {@link SurfaceDispatcher#ENABLED} is
	 * true, the {@code tryApply} mixin calls this first to get the eval
	 * result; only on null does it fall through to vanilla. This is the
	 * "simple" dispatch swap — eval per call, no batching, no JNI per
	 * chunk. Returns null on any setup failure (no tree, no captured
	 * context, evaluator threw) so the caller can safely cascade to
	 * vanilla.
	 *
	 * <p>Re-uses the validator's reflective context build and noise/PRNG
	 * caches. Skips validation overhead (no diff, no stats, no Rust
	 * side-call).
	 */
	public static Object tryDispatchEvaluator(Object surfaceBuilder, int x, int y, int z) {
		CompiledRuleTree tree = cachedTree;
		if (tree == null) return null;
		ColumnContext ctx = buildContext(surfaceBuilder, x, y, z);
		if (ctx == null) return null;
		SurfaceRuleEvaluator.VerticalGradientSampler sampler = ensureGradientSampler(tree);
		try {
			SurfaceRuleEvaluator.setCurrentXZ(x, z);
			return SurfaceRuleEvaluator.evaluate(tree, ctx, sampler);
		} catch (RuntimeException e) {
			return null;
		}
	}

	/**
	 * Called from the mixin redirect on every {@code tryApply} call.
	 * Sampling decision happens here to keep the redirect hot-path tiny.
	 */
	public static void maybeValidate(Object surfaceBuilder, int x, int y, int z, Object vanillaResult) {
		CompiledRuleTree tree = cachedTree;
		if (tree == null) return;
		long counter = sampleCounter.incrementAndGet();
		if (counter % SAMPLE_EVERY_N != 0) return;

		ColumnContext ctx = buildContext(surfaceBuilder, x, y, z);
		if (ctx == null) {
			contextBuildFails.incrementAndGet();
			return;
		}

		// One-shot reflection diagnostic — fires on the first sample after
		// install so we can confirm whether reads against the live
		// MaterialRuleContext are returning real values or silently zeroing.
		if (diagDumped.compareAndSet(false, true)) {
			dumpDiagnostic(ctx, x, y, z);
		}

		// Per-block PRNG sampler — built lazily from the live MaterialRuleContext.
		// Without this, OP_VERT_GRADIENT falls back to midpoint approx and bedrock-floor /
		// deepslate-transition rules produce ~50% wrong answers inside their gradient zones.
		SurfaceRuleEvaluator.VerticalGradientSampler sampler = ensureGradientSampler(tree);

		Object ourResult;
		try {
			SurfaceRuleEvaluator.setCurrentXZ(x, z);
			ourResult = SurfaceRuleEvaluator.evaluate(tree, ctx, sampler);
		} catch (RuntimeException e) {
			ExampleMod.LOGGER.warn("[surface-validate] evaluator threw at ({},{},{}): {}",
					x, y, z, e.toString());
			return;
		}

		// Three-way diff: also run the Rust evaluator if native is loaded.
		// Java is the spec — Rust must agree. Vanilla diff stays the
		// source of truth for "are our condition formulas right?"
		// Pass real (x, z) so Rust's OP_VERT_GRADIENT can do the per-block
		// PRNG roll matching Java. Without this, Rust falls back to (0, 0)
		// and produces deterministic mismatches inside vert-gradient zones.
		Object rustResult = SurfaceRuleEvaluator.evaluateViaRust(tree, ctx, x, z);
		if (rustResult != null || me.apika.apikaprobe.RustBridge.NATIVE_AVAILABLE) {
			rustSamples.incrementAndGet();
			String javaName = stringify(ourResult);
			String rustName = stringify(rustResult);
			if (java.util.Objects.equals(javaName, rustName)) {
				rustJavaAgreement.incrementAndGet();
			} else {
				long rd = rustJavaDivergence.incrementAndGet();
				if (rd <= 50) {
					ExampleMod.LOGGER.warn(
						"[surface-validate] JAVA≠RUST #{} at ({},{},{}) java={} rust={} biome={}",
						rd, x, y, z, javaName, rustName, ctx.biomeName());
				} else if (rd == 51) {
					ExampleMod.LOGGER.warn("[surface-validate] suppressing further JAVA≠RUST lines (>50)");
				}
			}
		}

		totalSamples.incrementAndGet();
		String vName = stringify(vanillaResult);
		String oName = stringify(ourResult);
		if (vanillaResult == null) nullVanilla.incrementAndGet();
		if (ourResult == null) evalNull.incrementAndGet();

		if (java.util.Objects.equals(vName, oName)) {
			matches.incrementAndGet();
		} else {
			mismatches.incrementAndGet();
			// Rate-limit the per-mismatch log line to avoid flooding —
			// log only the first 50, then suppress.
			long mm = mismatches.get();
			if (mm <= 50) {
				ExampleMod.LOGGER.warn(
					"[surface-validate] mismatch #{} at ({},{},{}) vanilla={} eval={} biome={} runDepth={} stoneAbove={} stoneBelow={} fluid={} blockY={}",
					mm, x, y, z, vName, oName,
					ctx.biomeName(), ctx.runDepth(),
					ctx.stoneDepthAbove(), ctx.stoneDepthBelow(),
					ctx.fluidHeight(), ctx.blockY());
			} else if (mm == 51) {
				ExampleMod.LOGGER.warn("[surface-validate] suppressing further mismatch lines (>50)");
			}
			// Trace-on-next-mismatch: dump the full opcode trace from the
			// Java evaluator to surface exactly which condition diverged.
			if (traceNextMismatch) {
				traceNextMismatch = false;
				java.util.List<String> trace = new java.util.ArrayList<>(64);
				Object traced = SurfaceRuleEvaluator.evaluateWithTrace(tree, ctx, trace);
				ExampleMod.LOGGER.warn(
					"[surface-validate] === TRACE at ({},{},{}) vanilla={} eval={} traced={} ===",
					x, y, z, vName, oName, stringify(traced));
				ExampleMod.LOGGER.warn("[surface-validate]   ctx: biome={} blockY={} runDepth={} stoneAbove={} stoneBelow={} fluid={} isCold={} surfH={}",
					ctx.biomeName(), ctx.blockY(), ctx.runDepth(),
					ctx.stoneDepthAbove(), ctx.stoneDepthBelow(),
					ctx.fluidHeight(), ctx.isCold(), ctx.surfaceHeight());
				for (String line : trace) {
					ExampleMod.LOGGER.warn("[surface-validate]   {}", line);
				}
				ExampleMod.LOGGER.warn("[surface-validate] === END TRACE ===");
			}
		}
	}

	private static String stringify(Object blockstate) {
		if (blockstate == null) return "null";
		// Try .getBlock().getRegistryEntry().getKey().getValue() — keep best-effort
		try {
			Object block = blockstate.getClass().getMethod("getBlock").invoke(blockstate);
			if (block != null) {
				try {
					Class<?> registries = Class.forName("net.minecraft.registry.Registries");
					Object reg = registries.getField("BLOCK").get(null);
					for (java.lang.reflect.Method m : reg.getClass().getMethods()) {
						if (m.getName().equals("getId") && m.getParameterCount() == 1) {
							Object id = m.invoke(reg, block);
							if (id != null) return id.toString();
						}
					}
				} catch (ReflectiveOperationException | RuntimeException ignored) {
					// fall through
				}
			}
		} catch (ReflectiveOperationException | RuntimeException ignored) {
			// fall through
		}
		return blockstate.toString();
	}

	/**
	 * Reflectively build a {@link ColumnContext} from the
	 * {@code SurfaceBuilder}'s active {@code MaterialRuleContext}.
	 *
	 * Returns null if any required field can't be resolved — the
	 * sample is then dropped and counted in {@code contextBuildFails}.
	 */
	private static ColumnContext buildContext(Object surfaceBuilder, int x, int y, int z) {
		Object ruleContext = threadCtxRef.get();
		int[] vert = threadVertState.get();
		if (ruleContext == null || vert == null) return null;

		// Captured arg order from initVerticalContext(IIIIII)V, decoded
		// from MaterialRuleContext bytecode:
		//   arg1 (vert[0]) = stoneDepthAbove
		//   arg2 (vert[1]) = stoneDepthBelow
		//   arg3 (vert[2]) = fluidHeight
		//   arg4 (vert[3]) = blockX
		//   arg5 (vert[4]) = blockY
		//   arg6 (vert[5]) = blockZ
		// runDepth is set by initHorizontalContext (sampleRunDepth) and
		// stored as a field on the context — read it via reflection.
		String biome = readBiomeName(ruleContext);
		int stoneDepthAbove = vert[0];
		int stoneDepthBelow = vert[1];
		int fluidHeight = vert[2];
		int blockX = vert[3];
		int blockY = vert[4];
		int blockZ = vert[5];
		int runDepth = readIntField(ruleContext, "runDepth");
		double secondaryDepth = readSecondaryDepth(ruleContext);

		// isCold: vanilla calls biome.value().coldEnoughToSnow(pos, seaLevel).
		// Pull biomeSupplier (already used by readBiomeName), call .get() →
		// RegistryEntry → .value() → coldEnoughToSnow(BlockPos, int).
		boolean isCold = readIsCold(ruleContext, blockX, blockY, blockZ);

		// surfaceHeight: vanilla calls ruleContext.getMinSurfaceLevel() —
		// returns preliminarySurfaceLevel + surfaceDepth - 8. Used by
		// OP_SURFACE (AbovePreliminarySurfaceCondition: blockY >= getMinSurfaceLevel()).
		int surfaceHeight = readMinSurfaceLevel(ruleContext, blockY);

		// Steep: still placeholder. Vanilla calls SurfaceSystem state and
		// heightmap reads that aren't trivially reflective. Two
		// SteepMaterialCondition occurrences in tree — accept as residual.
		boolean isSteep = false;

		// Real noise channel values via the live NoiseConfig (path 2 fix —
		// previously passed all-zero placeholder, which caused
		// OP_NOISE_THRESH conditions to flip in surface-rule cases gated
		// on bedrock floor noise / deep-stone gradient noise).
		double[] noiseChannels = sampleNoiseChannels(ruleContext, blockX, blockZ);

		return new ColumnContext(
				biome, blockY,
				runDepth, stoneDepthAbove, stoneDepthBelow,
				fluidHeight, isCold, isSteep, surfaceHeight,
				secondaryDepth,
				noiseChannels);
	}

	// --- Noise sampling --------------------------------------------------

	/**
	 * Sample every noise channel in the active compiled tree at the given
	 * (x, z) position. Vanilla's NoiseThresholdConditionSource calls
	 * {@code noise.getValue(blockX, 0.0, blockZ)} — y is hardcoded to 0
	 * because surface-rule noise is effectively 2D — so we mirror that.
	 *
	 * <p>The first call after {@link #install} populates {@link
	 * #cachedSamplers} from the live {@code MaterialRuleContext.noiseConfig}.
	 * Subsequent calls are array reads + {@code sample(x, 0, z)} per channel.
	 *
	 * <p>If a sampler can't be resolved (registry miss, mismatched name,
	 * yarn rename), its slot stays null and produces 0.0 — better than
	 * crashing the validator.
	 */
	private static double[] sampleNoiseChannels(Object ruleContext, int blockX, int blockZ) {
		CompiledRuleTree tree = cachedTree;
		if (tree == null) return new double[0];
		String[] channelNames = tree.noiseChannelTable();
		int n = channelNames.length;
		double[] out = new double[n];

		double bx = (double) blockX;
		double bz = (double) blockZ;

		// Fast path: Rust's seed-driven worldgen state. Parity proven
		// bit-exact vs yarn's DoublePerlinNoiseSampler in /ferrite
		// worldgen validate (60/60 noises, diff=0.0) — see
		// docs/SEED_DRIVEN_DISPATCH.md. One JNI per channel replaces
		// one Method.invoke per channel.
		if (me.apika.apikaprobe.RustBridge.NATIVE_AVAILABLE && rustNoiseReady()) {
			java.nio.ByteBuffer[] bufs = ensureRustNameBuffers(channelNames);
			int[] lens = cachedChannelNameLengths;
			boolean allGood = true;
			for (int i = 0; i < n; i++) {
				double v = me.apika.apikaprobe.RustBridge.sampleWorldgenNoise(
						bufs[i], lens[i], bx, 0.0, bz);
				if (Double.isNaN(v)) {
					// Rust doesn't know this channel — fall through to the
					// reflective path for *this* call (don't poison the fast
					// path for other channels next call).
					allGood = false;
					break;
				}
				out[i] = v;
			}
			if (allGood) return out;
		}

		// Slow path: reflective yarn samplers. Used before worldgen state
		// finalizes (early boot, or a mod that skips bootstrap) and as the
		// safety net for channels Rust doesn't have.
		Object[] samplers = cachedSamplers;
		if (samplers == null || samplers.length != n) {
			samplers = buildSamplerCache(ruleContext, channelNames);
			cachedSamplers = samplers;
		}
		for (int i = 0; i < n; i++) {
			Object sampler = samplers[i];
			if (sampler == null) {
				out[i] = 0.0;
				continue;
			}
			try {
				java.lang.reflect.Method m = sampler.getClass().getMethod("sample",
						double.class, double.class, double.class);
				Object v = m.invoke(sampler, bx, 0.0, bz);
				if (v instanceof Double d) out[i] = d;
			} catch (ReflectiveOperationException | RuntimeException ignored) {
				out[i] = 0.0;
			}
		}
		return out;
	}

	/** Cache of direct ByteBuffers holding UTF-8 encoded channel names,
	 *  one per entry in the active tree's {@link CompiledRuleTree#noiseChannelTable()}.
	 *  Allocated lazily and reused across every sample call; Rust only
	 *  reads the buffer contents. */
	private static volatile java.nio.ByteBuffer[] cachedChannelNameBuffers = null;
	private static volatile int[] cachedChannelNameLengths = null;
	private static volatile String[] cachedChannelNameSource = null;

	/** Once finalized, stays finalized for the world's lifetime, so we
	 *  only need to query Rust once. null = unresolved, TRUE = ready,
	 *  FALSE = not finalized (transient at early boot). */
	private static volatile Boolean rustNoiseReady = null;

	private static boolean rustNoiseReady() {
		Boolean cached = rustNoiseReady;
		if (cached != null && cached) return true;
		boolean ready = me.apika.apikaprobe.RustBridge.worldgenNoiseCount() >= 0;
		if (ready) rustNoiseReady = Boolean.TRUE;
		return ready;
	}

	private static java.nio.ByteBuffer[] ensureRustNameBuffers(String[] channelNames) {
		java.nio.ByteBuffer[] cached = cachedChannelNameBuffers;
		if (cached != null && cachedChannelNameSource == channelNames) return cached;
		int n = channelNames.length;
		java.nio.ByteBuffer[] bufs = new java.nio.ByteBuffer[n];
		int[] lens = new int[n];
		for (int i = 0; i < n; i++) {
			byte[] bytes = channelNames[i].getBytes(java.nio.charset.StandardCharsets.UTF_8);
			java.nio.ByteBuffer b = java.nio.ByteBuffer.allocateDirect(bytes.length)
					.order(java.nio.ByteOrder.nativeOrder());
			b.put(bytes);
			b.flip();
			bufs[i] = b;
			lens[i] = bytes.length;
		}
		cachedChannelNameBuffers = bufs;
		cachedChannelNameLengths = lens;
		cachedChannelNameSource = channelNames;
		return bufs;
	}

	/**
	 * Lazy builder for the (seedLo, seedHi) buffer Rust uses to construct
	 * {@code XoroshiroPositionalRandomFactory} for OP_VERT_GRADIENT.
	 * Returns null if no splitter cache yet (first dispatch hasn't fired
	 * to populate it). Reused across all per-call evaluations on this
	 * tree — built once per install.
	 *
	 * <p>Layout: little-endian i64 pairs, one per random_name in
	 * {@link CompiledRuleTree#randomNameTable()}. Slot N at byte offset
	 * 16N is seedLo; offset 16N+8 is seedHi.
	 *
	 * <p>Yarn 1.21.11: vanilla's RandomSplitter impl is
	 * {@code Xoroshiro128PlusPlusRandom$Splitter} with public-field
	 * {@code seedLo}/{@code seedHi}. Reflective read is direct.
	 */
	public static java.nio.ByteBuffer cachedFactorySeedsBuf() {
		java.nio.ByteBuffer buf = cachedFactorySeedsBuf;
		if (buf != null) return buf;
		Object[] splitters = cachedSplitters;
		if (splitters == null) return null; // no splitter cache yet
		buf = java.nio.ByteBuffer.allocateDirect(splitters.length * 16)
				.order(java.nio.ByteOrder.LITTLE_ENDIAN);
		for (int i = 0; i < splitters.length; i++) {
			Object splitter = splitters[i];
			long seedLo = 0L, seedHi = 0L;
			if (splitter != null) {
				try {
					java.lang.reflect.Field flo = findFieldDeep(splitter.getClass(), "seedLo");
					java.lang.reflect.Field fhi = findFieldDeep(splitter.getClass(), "seedHi");
					if (flo != null && fhi != null) {
						flo.setAccessible(true);
						fhi.setAccessible(true);
						seedLo = flo.getLong(splitter);
						seedHi = fhi.getLong(splitter);
					}
				} catch (ReflectiveOperationException ignored) {
					// leave zero — Rust's Xoroshiro will substitute golden+silver
				}
			}
			buf.putLong(i * 16, seedLo);
			buf.putLong(i * 16 + 8, seedHi);
		}
		cachedFactorySeedsBuf = buf;
		cachedFactorySeedCount = splitters.length;
		return buf;
	}

	/** Number of factory seed pairs in {@link #cachedFactorySeedsBuf}.
	 *  Returns 0 before the buffer is built. */
	public static int cachedFactorySeedCount() {
		// Building the buffer also sets the count, so trigger if unset.
		if (cachedFactorySeedsBuf == null) cachedFactorySeedsBuf();
		return cachedFactorySeedCount;
	}

	/**
	 * Build (or return cached) the {@link SurfaceRuleEvaluator.VerticalGradientSampler}
	 * closure for this tree. First call after install resolves every
	 * random_name in {@link CompiledRuleTree#randomNameTable()} via
	 * {@code NoiseConfig.getOrCreateRandomDeriver(Identifier)} and caches
	 * the resulting {@code RandomSplitter} array plus the reflective
	 * Method handles for {@code split(int,int,int)} and {@code nextFloat()}.
	 * Subsequent calls return the cached closure with no allocation.
	 *
	 * <p>If random_name resolution fails for a channel (yarn rename, missing
	 * registry entry, etc.), the slot stays null and the closure returns
	 * 0.5f for that index — keeps the per-block-PRNG branch deterministic
	 * but flagged in mismatches.
	 */
	private static SurfaceRuleEvaluator.VerticalGradientSampler ensureGradientSampler(CompiledRuleTree tree) {
		SurfaceRuleEvaluator.VerticalGradientSampler existing = cachedSamplerClosure;
		if (existing != null) return existing;

		Object liveCtx = threadCtxRef.get();
		if (liveCtx == null) return null; // first sample hasn't captured ctx yet
		String[] names = tree.randomNameTable();
		Object[] splitters = buildSplitterCache(liveCtx, names);
		if (splitters == null) return null;

		// Resolve the per-call reflective methods once, off the first
		// non-null splitter we find. All splitters are RandomSplitter; all
		// Random instances are class_5819. Method handles are stable.
		java.lang.reflect.Method splitMethod = null;
		java.lang.reflect.Method nextFloatMethod = null;
		for (Object s : splitters) {
			if (s == null) continue;
			try {
				splitMethod = s.getClass().getMethod("split", int.class, int.class, int.class);
				Object dummy = splitMethod.invoke(s, 0, 0, 0);
				if (dummy != null) {
					nextFloatMethod = dummy.getClass().getMethod("nextFloat");
				}
				break;
			} catch (ReflectiveOperationException | RuntimeException ignored) {
				// try next slot
			}
		}
		if (splitMethod == null || nextFloatMethod == null) {
			ExampleMod.LOGGER.warn("[surface-validate] could not resolve RandomSplitter.split / Random.nextFloat — gradient sampler disabled");
			return null;
		}

		cachedSplitters = splitters;
		splitterSplitMethod = splitMethod;
		randomNextFloatMethod = nextFloatMethod;

		final java.lang.reflect.Method splitM = splitMethod;
		final java.lang.reflect.Method nextFloatM = nextFloatMethod;
		final Object[] cap = splitters;
		SurfaceRuleEvaluator.VerticalGradientSampler closure = (idx, x, y, z) -> {
			if (idx < 0 || idx >= cap.length) return 0.5f;
			Object splitter = cap[idx];
			if (splitter == null) return 0.5f;
			try {
				Object random = splitM.invoke(splitter, x, y, z);
				Object v = nextFloatM.invoke(random);
				return v instanceof Float f ? f : 0.5f;
			} catch (ReflectiveOperationException | RuntimeException e) {
				return 0.5f;
			}
		};
		cachedSamplerClosure = closure;
		return closure;
	}

	/**
	 * One-time splitter-array build. Parallel to {@link #buildSamplerCache}
	 * but for {@code RandomSplitter} via {@code NoiseConfig.getOrCreateRandomDeriver}.
	 * Returns null on a structural failure (no noiseConfig, missing registry
	 * plumbing); returns an array with null slots for individual unresolved
	 * names.
	 */
	private static Object[] buildSplitterCache(Object ruleContext, String[] randomNames) {
		Object[] splitters = new Object[randomNames.length];
		if (randomNames.length == 0) return splitters;

		Object noiseConfig = readField(ruleContext, "noiseConfig");
		if (noiseConfig == null) {
			ExampleMod.LOGGER.warn(
				"[surface-validate] noiseConfig field not found on {} — vert-gradient PRNG disabled",
				ruleContext.getClass().getName());
			return splitters;
		}

		Class<?> identifierClass;
		java.lang.reflect.Method identifierOf;
		java.lang.reflect.Method getOrCreateRandomDeriver;
		try {
			identifierClass = Class.forName("net.minecraft.util.Identifier");
			identifierOf = identifierClass.getMethod("of", String.class);
			getOrCreateRandomDeriver = noiseConfig.getClass().getMethod("getOrCreateRandomDeriver", identifierClass);
		} catch (ReflectiveOperationException e) {
			ExampleMod.LOGGER.warn(
				"[surface-validate] could not resolve random-deriver API ({}) — vert-gradient PRNG disabled",
				e.getClass().getSimpleName());
			return splitters;
		}

		int resolved = 0;
		for (int i = 0; i < randomNames.length; i++) {
			String name = randomNames[i];
			try {
				Object identifier = identifierOf.invoke(null, name);
				Object splitter = getOrCreateRandomDeriver.invoke(noiseConfig, identifier);
				if (splitter != null) {
					splitters[i] = splitter;
					resolved++;
				} else {
					ExampleMod.LOGGER.warn("[surface-validate] random_name '{}' resolved to null splitter", name);
				}
			} catch (ReflectiveOperationException | RuntimeException e) {
				ExampleMod.LOGGER.warn("[surface-validate] random_name '{}' failed: {}",
					name, e.getCause() == null ? e.getMessage() : e.getCause().getMessage());
			}
		}
		ExampleMod.LOGGER.info(
			"[surface-validate] gradient PRNG cache built: {}/{} random factories resolved",
			resolved, randomNames.length);
		return splitters;
	}

	/**
	 * One-time cache build. For each registry-name string in the tree's
	 * noise channel table, parse it as an {@code Identifier}, wrap it as
	 * a {@code RegistryKey<DoublePerlinNoiseSampler.NoiseParameters>},
	 * then call {@code noiseConfig.getOrCreateSampler(key)}. Failures
	 * (unparseable name, missing registry entry, yarn rename) leave a
	 * null slot and log once per channel.
	 */
	private static Object[] buildSamplerCache(Object ruleContext, String[] channelNames) {
		Object[] samplers = new Object[channelNames.length];

		// Read the noiseConfig field off the live MaterialRuleContext.
		Object noiseConfig = readField(ruleContext, "noiseConfig");
		if (noiseConfig == null) {
			ExampleMod.LOGGER.warn(
				"[surface-validate] noiseConfig field not found on {} — all noise channels will read 0.0",
				ruleContext.getClass().getName());
			return samplers;
		}

		Class<?> identifierClass;
		Class<?> registryKeyClass;
		Class<?> registryKeysClass;
		Object noiseParamsRegistryKey;
		try {
			identifierClass = Class.forName("net.minecraft.util.Identifier");
			registryKeyClass = Class.forName("net.minecraft.registry.RegistryKey");
			registryKeysClass = Class.forName("net.minecraft.registry.RegistryKeys");
			noiseParamsRegistryKey = registryKeysClass.getField("NOISE_PARAMETERS").get(null);
		} catch (ReflectiveOperationException e) {
			ExampleMod.LOGGER.warn(
				"[surface-validate] could not resolve registry-key plumbing ({}) — all noise channels will read 0.0",
				e.getClass().getSimpleName());
			return samplers;
		}

		java.lang.reflect.Method getOrCreateSampler;
		java.lang.reflect.Method registryKeyOf;
		java.lang.reflect.Method identifierOf;
		try {
			// Identifier.of(String) — parses "minecraft:foo" or "namespace:path"
			identifierOf = identifierClass.getMethod("of", String.class);
			// RegistryKey.of(RegistryKey<? extends Registry<E>>, Identifier) → RegistryKey<E>
			registryKeyOf = registryKeyClass.getMethod("of", registryKeyClass, identifierClass);
			// NoiseConfig.getOrCreateSampler(RegistryKey) → DoublePerlinNoiseSampler
			getOrCreateSampler = noiseConfig.getClass().getMethod("getOrCreateSampler", registryKeyClass);
		} catch (ReflectiveOperationException e) {
			ExampleMod.LOGGER.warn(
				"[surface-validate] could not resolve noise sampler API ({}) — all channels read 0.0",
				e.getClass().getSimpleName());
			return samplers;
		}

		int resolved = 0;
		for (int i = 0; i < channelNames.length; i++) {
			String name = channelNames[i];
			try {
				Object identifier = identifierOf.invoke(null, name);
				Object key = registryKeyOf.invoke(null, noiseParamsRegistryKey, identifier);
				Object sampler = getOrCreateSampler.invoke(noiseConfig, key);
				if (sampler != null) {
					samplers[i] = sampler;
					resolved++;
				} else {
					ExampleMod.LOGGER.warn("[surface-validate] noise channel '{}' resolved to null sampler", name);
				}
			} catch (ReflectiveOperationException | RuntimeException e) {
				ExampleMod.LOGGER.warn("[surface-validate] noise channel '{}' failed: {}",
					name, e.getCause() == null ? e.getMessage() : e.getCause().getMessage());
			}
		}
		ExampleMod.LOGGER.info(
			"[surface-validate] noise sampler cache built: {}/{} channels resolved",
			resolved, channelNames.length);
		return samplers;
	}

	/**
	 * Calls {@code biome.value().coldEnoughToSnow(pos, seaLevel)} via
	 * reflection. Vanilla's TemperatureHelperCondition uses this exact
	 * call (Mojang's source line 442). Returns false on any reflection
	 * failure — the OP_TEMPERATURE branch will then mismatch in those
	 * positions but the validator will surface them clearly.
	 */
	private static boolean readIsCold(Object ruleContext, int blockX, int blockY, int blockZ) {
		try {
			Object supplier = findSupplierField(ruleContext);
			if (!(supplier instanceof java.util.function.Supplier<?> s)) return false;
			Object biomeEntry = s.get();
			if (biomeEntry == null) return false;
			Object biomeValue = biomeEntry.getClass().getMethod("value").invoke(biomeEntry);
			if (biomeValue == null) return false;

			int seaLevel = readSeaLevel(ruleContext);
			Object pos = newBlockPos(blockX, blockY, blockZ);
			if (pos == null) return false;

			for (java.lang.reflect.Method m : biomeValue.getClass().getMethods()) {
				if (!m.getName().equals("coldEnoughToSnow") || m.getParameterCount() != 2) continue;
				Object out = m.invoke(biomeValue, pos, seaLevel);
				return out instanceof Boolean b && b;
			}
		} catch (ReflectiveOperationException | RuntimeException ignored) {
			// fall through
		}
		return false;
	}

	private static int readSeaLevel(Object ruleContext) {
		try {
			java.lang.reflect.Method m = ruleContext.getClass().getMethod("getSeaLevel");
			Object v = m.invoke(ruleContext);
			if (v instanceof Integer i) return i;
		} catch (ReflectiveOperationException | RuntimeException ignored) {
			// fall through
		}
		return 63; // overworld default; safe fallback
	}

	private static Object newBlockPos(int x, int y, int z) {
		try {
			Class<?> cls = Class.forName("net.minecraft.util.math.BlockPos");
			return cls.getConstructor(int.class, int.class, int.class).newInstance(x, y, z);
		} catch (ReflectiveOperationException | RuntimeException ignored) {
			return null;
		}
	}

	/**
	 * Calls {@code ruleContext.estimateSurfaceHeight()} via reflection.
	 * Vanilla {@code AbovePreliminarySurfaceCondition} tests
	 * {@code blockY >= getMinSurfaceLevel()} in Mojmap (source line 394);
	 * Yarn 1.21.11 renamed the accessor to {@code estimateSurfaceHeight}
	 * (yarn 1.21.11+build.4 mapping for {@code method_39551}).
	 *
	 * <p><b>Bug history:</b> previously called {@code getMinSurfaceLevel}
	 * which doesn't exist in Yarn → silently fell back to {@code defaultY = blockY},
	 * making {@code OP_SURFACE} always true and causing surface rules
	 * (grass, sand) to fire at deep-stone altitudes. That mis-fire
	 * accounted for the bulk of the 4.7% validator mismatches.
	 */
	private static int readMinSurfaceLevel(Object ruleContext, int defaultY) {
		try {
			java.lang.reflect.Method m = ruleContext.getClass().getMethod("estimateSurfaceHeight");
			Object v = m.invoke(ruleContext);
			if (v instanceof Integer i) return i;
		} catch (ReflectiveOperationException | RuntimeException ignored) {
			// fall through
		}
		return defaultY;
	}

	private static double readSecondaryDepth(Object ruleContext) {
		try {
			java.lang.reflect.Method m = ruleContext.getClass().getMethod("getSecondaryDepth");
			Object v = m.invoke(ruleContext);
			if (v instanceof Double d) return d;
		} catch (ReflectiveOperationException | RuntimeException ignored) {
			// fall through
		}
		return 0.0;
	}

	private static String readBiomeName(Object ruleContext) {
		// 1. Find a Supplier-typed field anywhere on the context.
		Object supplier = findSupplierField(ruleContext);
		if (supplier == null) return "unknown:no-supplier-field";

		Object biomeEntry;
		try {
			biomeEntry = ((java.util.function.Supplier<?>) supplier).get();
		} catch (ClassCastException e) {
			return "unknown:not-supplier:" + supplier.getClass().getSimpleName();
		} catch (RuntimeException e) {
			return "unknown:supplier-threw:" + e.getClass().getSimpleName();
		}
		if (biomeEntry == null) return "unknown:null-entry";

		// 2. Try RegistryEntry.getKey() — may return Optional<RegistryKey> or RegistryKey
		try {
			java.lang.reflect.Method getKey = biomeEntry.getClass().getMethod("getKey");
			Object keyResult = getKey.invoke(biomeEntry);
			if (keyResult instanceof java.util.Optional<?> opt) {
				if (!opt.isPresent()) return "unknown:empty-optional";
				return registryKeyValueString(opt.get());
			}
			if (keyResult != null) {
				return registryKeyValueString(keyResult);
			}
			return "unknown:null-keyresult";
		} catch (NoSuchMethodException e) {
			return "unknown:no-getkey:" + biomeEntry.getClass().getSimpleName();
		} catch (ReflectiveOperationException | RuntimeException e) {
			return "unknown:getkey-threw:" + e.getClass().getSimpleName();
		}
	}

	private static String registryKeyValueString(Object regKey) {
		try {
			Object id = regKey.getClass().getMethod("getValue").invoke(regKey);
			return id == null ? "unknown:null-id" : id.toString();
		} catch (NoSuchMethodException e) {
			return "unknown:no-getvalue:" + regKey.getClass().getSimpleName();
		} catch (ReflectiveOperationException | RuntimeException e) {
			return "unknown:getvalue-threw:" + e.getClass().getSimpleName();
		}
	}

	private static Object findSupplierField(Object o) {
		Class<?> c = o.getClass();
		while (c != null && c != Object.class) {
			for (java.lang.reflect.Field f : c.getDeclaredFields()) {
				if (java.lang.reflect.Modifier.isStatic(f.getModifiers())) continue;
				try {
					f.setAccessible(true);
					Object v = f.get(o);
					if (v instanceof java.util.function.Supplier<?>) return v;
				} catch (ReflectiveOperationException ignored) {
					// skip
				}
			}
			c = c.getSuperclass();
		}
		return null;
	}

	private static Object readField(Object o, String name) {
		Class<?> c = o.getClass();
		while (c != null && c != Object.class) {
			for (java.lang.reflect.Field f : c.getDeclaredFields()) {
				if (!f.getName().equals(name)) continue;
				try {
					f.setAccessible(true);
					return f.get(o);
				} catch (ReflectiveOperationException e) {
					return null;
				}
			}
			c = c.getSuperclass();
		}
		return null;
	}

	/**
	 * One-shot diagnostic. Probes the live {@link
	 * net.minecraft.world.gen.surfacebuilder.MaterialRules.MaterialRuleContext}
	 * (captured per-thread by the initVerticalContext redirect) and reports
	 * whether each reflective read found its target or silently zeroed.
	 *
	 * <p>Decision tree (from the runbook):
	 * <ul>
	 *   <li>If found=false on either runDepth/secondaryDepth → reflection
	 *       is the bug, fix the field/method access.</li>
	 *   <li>If found=true but values look wrong → reads work but something
	 *       else is zeroing them (capture timing, wrong ctx instance).</li>
	 *   <li>If all values look reasonable → reflection is healthy, look
	 *       elsewhere (compiled bytecode, biome-set table, etc.).</li>
	 * </ul>
	 */
	private static void dumpDiagnostic(ColumnContext ctx, int x, int y, int z) {
		Object liveCtx = threadCtxRef.get();

		// Probe runDepth field directly.
		boolean runDepthFound = false;
		int runDepthLive = 0;
		if (liveCtx != null) {
			Class<?> c = liveCtx.getClass();
			outer:
			while (c != null && c != Object.class) {
				for (java.lang.reflect.Field f : c.getDeclaredFields()) {
					if (!f.getName().equals("runDepth")) continue;
					try {
						f.setAccessible(true);
						runDepthLive = f.getInt(liveCtx);
						runDepthFound = true;
					} catch (ReflectiveOperationException ignored) {
						// fall through, leave found=false
					}
					break outer;
				}
				c = c.getSuperclass();
			}
		}

		// Probe getSecondaryDepth() method directly.
		boolean secondaryDepthFound = false;
		double secondaryDepthLive = 0.0;
		if (liveCtx != null) {
			try {
				java.lang.reflect.Method m = liveCtx.getClass().getMethod("getSecondaryDepth");
				Object v = m.invoke(liveCtx);
				if (v instanceof Double d) {
					secondaryDepthLive = d;
					secondaryDepthFound = true;
				}
			} catch (ReflectiveOperationException | RuntimeException ignored) {
				// leave found=false
			}
		}

		// Probe estimateSurfaceHeight() — the previously-buggy lookup.
		// Vanilla returns preliminarySurfaceLevel + surfaceDepth - 8;
		// expect a sensible terrain-surface Y, NOT == blockY (which
		// indicates the silent fallback fired).
		boolean surfaceHeightFound = false;
		int surfaceHeightLive = 0;
		if (liveCtx != null) {
			try {
				java.lang.reflect.Method m = liveCtx.getClass().getMethod("estimateSurfaceHeight");
				Object v = m.invoke(liveCtx);
				if (v instanceof Integer i) {
					surfaceHeightLive = i;
					surfaceHeightFound = true;
				}
			} catch (ReflectiveOperationException | RuntimeException ignored) {
				// leave found=false
			}
		}

		ExampleMod.LOGGER.info(
			"[surface-diag] runDepth={} found={}  secondaryDepth={} found={}  "
			+ "estimateSurfaceHeight={} found={}  "
			+ "biome={} blockY={} stoneAbove={} stoneBelow={} fluidHeight={} "
			+ "isCold={} isSteep={} ctxSurfaceHeight={} ctxClass={} sampleAt=({},{},{})",
			runDepthLive, runDepthFound,
			secondaryDepthLive, secondaryDepthFound,
			surfaceHeightLive, surfaceHeightFound,
			ctx.biomeName(), ctx.blockY(), ctx.stoneDepthAbove(),
			ctx.stoneDepthBelow(), ctx.fluidHeight(),
			ctx.isCold(), ctx.isSteep(), ctx.surfaceHeight(),
			liveCtx == null ? "null" : liveCtx.getClass().getName(),
			x, y, z);
	}

	private static int readIntField(Object o, String name) {
		Class<?> c = o.getClass();
		while (c != null && c != Object.class) {
			for (java.lang.reflect.Field f : c.getDeclaredFields()) {
				if (!f.getName().equals(name)) continue;
				try {
					f.setAccessible(true);
					return f.getInt(o);
				} catch (ReflectiveOperationException e) {
					return 0;
				}
			}
			c = c.getSuperclass();
		}
		return 0;
	}
}
