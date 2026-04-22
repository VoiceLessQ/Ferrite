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

		Object ourResult;
		try {
			ourResult = SurfaceRuleEvaluator.evaluate(tree, ctx);
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
					"[surface-validate] mismatch #{} at ({},{},{}) vanilla={} eval={} biome={} runDepth={} fluid={}",
					mm, x, y, z, vName, oName,
					ctx.biomeName(), ctx.runDepth(), ctx.fluidHeight());
			} else if (mm == 51) {
				ExampleMod.LOGGER.warn("[surface-validate] suppressing further mismatch lines (>50)");
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
		int blockY = vert[4];
		int runDepth = readIntField(ruleContext, "runDepth");
		double secondaryDepth = readSecondaryDepth(ruleContext);

		// Spike placeholders — will mismatch in their respective conditions.
		boolean isCold = false;
		boolean isSteep = false;
		int surfaceHeight = blockY;

		return new ColumnContext(
				biome, blockY,
				runDepth, stoneDepthAbove, stoneDepthBelow,
				fluidHeight, isCold, isSteep, surfaceHeight,
				secondaryDepth,
				new double[16]); // 16 noise channels — all zero, NoiseThresh will mismatch
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
