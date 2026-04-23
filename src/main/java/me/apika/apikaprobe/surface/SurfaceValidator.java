package me.apika.apikaprobe.surface;

import java.util.concurrent.atomic.AtomicLong;

import me.apika.apikaprobe.ExampleMod;

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

	public static void install(CompiledRuleTree tree) {
		cachedTree = tree;
		resetStats();
		diagDumped.set(false); // re-arm the one-shot diagnostic dump
		cachedSamplers = null; // invalidate noise-sampler cache (new tree may have different channels)
		cachedSplitters = null; // invalidate gradient-PRNG cache (parallel to samplers)
		cachedSamplerClosure = null;
		splitterSplitMethod = null;
		randomNextFloatMethod = null;
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
		Object rustResult = SurfaceRuleEvaluator.evaluateViaRust(tree, ctx);
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

		Object[] samplers = cachedSamplers;
		if (samplers == null || samplers.length != n) {
			samplers = buildSamplerCache(ruleContext, channelNames);
			cachedSamplers = samplers;
		}

		double bx = (double) blockX;
		double bz = (double) blockZ;
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
