package me.apika.apikaprobe.worldgen;

import me.apika.apikaprobe.bridge.ExampleMod;
import me.apika.apikaprobe.RustBridge;

import java.lang.reflect.Method;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Random;

import net.minecraft.server.MinecraftServer;

/**
 * Rust↔Java density function parity validator.
 *
 * <p>For a given registered DF name, sample N random (x, y, z) points,
 * ask Rust (via {@link RustBridge#sampleDensityFunction}) and vanilla
 * (via the live yarn DensityFunction tree) for the same coord,
 * compare, and report the max abs diff + worst offender.
 *
 * <p>DF outputs are f64 so we tolerate IEEE round-off — anything at
 * the last ULP is noise, anything larger is a real port bug in one
 * of the node formulas.
 */
public final class DensityParity {
	private DensityParity() {}

	private static final double PARITY_EPSILON = 1.0e-9;

	/**
	 * Sample every registered DF at {@code samples} random positions,
	 * compare Rust vs vanilla, return chat-ready summary.
	 */
	public static String runAll(MinecraftServer server, int samples) {
		if (!RustBridge.NATIVE_AVAILABLE) {
			return "[df-parity] native unavailable";
		}
		int dfCount = RustBridge.densityFunctionCount();
		if (dfCount < 0) {
			return "[df-parity] Rust state not finalized";
		}
		List<String> names = WorldgenStateBootstrap.registeredDensityFunctionNames();
		if (names.isEmpty()) {
			return "[df-parity] no density functions registered";
		}

		// Resolve the DENSITY_FUNCTION registry for vanilla-side lookups.
		Object dfRegistry = resolveDfRegistry(server);
		if (dfRegistry == null) {
			return "[df-parity] could not resolve DENSITY_FUNCTION registry";
		}
		Random rng = new Random(0xABCDEFABL);
		int totalPass = 0;
		int totalFail = 0;
		double worstDiff = 0.0;
		String worstName = "";
		int worstX = 0, worstY = 0, worstZ = 0;
		double worstRust = 0, worstYarn = 0;
		java.util.List<String> failures = new java.util.ArrayList<>();

		// Walk each unresolved registry DF with a Visitor that binds
		// NoiseHolders to live NormalNoise instances via
		// RandomState.getOrCreateNoise. The bound tree computes correctly;
		// without this the validator would compare rust-with-real-noise
		// vs vanilla-with-null-noise (noise.getValue returns 0 on null).
		Object noiseConfig = WorldgenParity.findOverworldNoiseConfig();

		for (String fullName : names) {
			// Synthetic names (ferrite:climate/*, ferrite:terrain/*) refer
			// to composed router DFs not in the registry — fetch live
			// instead of resolving from registry.
			Object vanilla;
			if (fullName.startsWith("ferrite:")) {
				String routerField = synthNameToRouterField(fullName);
				vanilla = (routerField == null) ? null : liveRouterField(routerField);
				if (vanilla == null) {
					failures.add(fullName + " (no live router DF)");
					totalFail++;
					continue;
				}
			} else {
				Object unresolved = lookupByName(dfRegistry, fullName);
				if (unresolved == null) {
					failures.add(fullName + " (no vanilla DF)");
					totalFail++;
					continue;
				}
				vanilla = (noiseConfig == null) ? unresolved : resolveNoises(unresolved, noiseConfig);
				if (vanilla == null) vanilla = unresolved;
			}
			byte[] nameBytes = fullName.getBytes(StandardCharsets.UTF_8);
			ByteBuffer nameBuf = ByteBuffer.allocateDirect(nameBytes.length).order(ByteOrder.nativeOrder());
			nameBuf.put(nameBytes);
			nameBuf.flip();

			double maxDiffThis = 0.0;
			int failsInNoise = 0;
			for (int i = 0; i < samples; i++) {
				int x = rng.nextInt(20000) - 10000;
				int y = rng.nextInt(384) - 64;
				int z = rng.nextInt(20000) - 10000;
				double rust = RustBridge.sampleDensityFunction(nameBuf, nameBytes.length, x, y, z);
				if (Double.isNaN(rust)) continue;
				Double yarn = computeVanilla(vanilla, x, y, z);
				if (yarn == null || Double.isNaN(yarn)) continue;
				double diff = Math.abs(rust - yarn);
				if (diff > maxDiffThis) maxDiffThis = diff;
				if (diff > worstDiff) {
					worstDiff = diff; worstName = fullName;
					worstX = x; worstY = y; worstZ = z;
					worstRust = rust; worstYarn = yarn;
				}
				if (diff > PARITY_EPSILON) failsInNoise++;
			}
			if (failsInNoise == 0 && maxDiffThis <= PARITY_EPSILON) {
				totalPass++;
			} else {
				totalFail++;
				failures.add(String.format("%s (maxDiff=%.6e, %d/%d failed)",
						fullName, maxDiffThis, failsInNoise, samples));
			}
		}

		ExampleMod.LOGGER.info(
				"[df-parity] samples/df={} dfs={} pass={} fail={} worst={} @({},{},{}) rust={} yarn={} diff={}",
				samples, names.size(), totalPass, totalFail,
				worstName.isEmpty() ? "-" : worstName,
				worstX, worstY, worstZ,
				String.format("%.6f", worstRust),
				String.format("%.6f", worstYarn),
				String.format("%.6e", worstDiff));
		// Bumped to 50 so all failures show during walker iteration —
		// otherwise small-diff fails get hidden behind big ones.
		int shown = Math.min(failures.size(), 50);
		for (int i = 0; i < shown; i++) {
			ExampleMod.LOGGER.warn("[df-parity]   {}", failures.get(i));
		}
		if (failures.size() > shown) {
			ExampleMod.LOGGER.warn("[df-parity]   ... {} more", failures.size() - shown);
		}
		return String.format("[df-parity] %d DFs, %d pass, %d fail, worst=%.3e (%s)",
				names.size(), totalPass, totalFail, worstDiff,
				worstName.isEmpty() ? "-" : worstName);
	}

	/**
	 * Public probe: resolve a registered DF by name and compute at (x, y, z).
	 * Walks the unresolved registry DF with a Visitor that binds
	 * NoiseHolders via `RandomState.getOrCreateNoise(key)` — matches
	 * what vanilla's `RandomState` constructor does internally. The
	 * bound tree then computes correctly.
	 */
	public static Double sampleVanilla(MinecraftServer server, String fullName, int x, int y, int z) {
		// Synthetic ferrite:* names refer to composed DFs on the live
		// NoiseRouter — not in the DENSITY_FUNCTION registry. Route to
		// the router-field path; the DF returned there is already
		// resolved (NoiseHolders bound, BlendedNoise reseeded by
		// RandomState.NoiseWiringHelper at world load).
		if (fullName.startsWith("ferrite:")) {
			String routerField = synthNameToRouterField(fullName);
			if (routerField == null) return null;
			Object live = liveRouterField(routerField);
			if (live == null) return null;
			return computeVanilla(live, x, y, z);
		}

		Object registry = resolveDfRegistry(server);
		if (registry == null) return null;
		Object unresolvedDf = lookupByName(registry, fullName);
		if (unresolvedDf == null) return null;
		Object noiseConfig = WorldgenParity.findOverworldNoiseConfig();
		Object resolved = (noiseConfig == null) ? unresolvedDf : resolveNoises(unresolvedDf, noiseConfig);
		return computeVanilla(resolved == null ? unresolvedDf : resolved, x, y, z);
	}

	/** Map our synthetic registration names back to NoiseRouter accessor
	 *  method names. Keep in sync with WorldgenStateBootstrap's
	 *  registerResolvedRouterClimateDfs. */
	private static String synthNameToRouterField(String fullName) {
		switch (fullName) {
			case "ferrite:terrain/final_density": return "finalDensity";
			case "ferrite:climate/temperature": return "temperature";
			case "ferrite:climate/vegetation": return "vegetation";
			case "ferrite:climate/continents": return "continents";
			case "ferrite:climate/erosion": return "erosion";
			case "ferrite:climate/depth": return "depth";
			case "ferrite:climate/ridges": return "ridges";
			default: return null;
		}
	}

	/** Look up the named field on the live overworld NoiseRouter and
	 *  return its DensityFunction. Returns null if no RandomState was
	 *  captured or the router doesn't expose this accessor. */
	private static Object liveRouterField(String accessor) {
		Object noiseConfig = WorldgenParity.findOverworldNoiseConfig();
		if (noiseConfig == null) return null;
		Object router = null;
		for (String n : new String[]{"getNoiseRouter", "noiseRouter", "router", "getRouter"}) {
			try {
				Method m = noiseConfig.getClass().getMethod(n);
				router = m.invoke(noiseConfig);
				if (router != null) break;
			} catch (ReflectiveOperationException ignored) { /* next */ }
		}
		if (router == null) return null;
		try {
			Method m = router.getClass().getMethod(accessor);
			return m.invoke(router);
		} catch (ReflectiveOperationException e) {
			return null;
		}
	}

	/**
	 * Walk `unresolvedDf` with a dynamic-proxy Visitor that binds each
	 * NoiseHolder to the live NormalNoise via
	 * `noiseConfig.getOrCreateNoise(key)`. Returns a new DF tree with
	 * noises resolved, or null on any reflection failure.
	 */
	@SuppressWarnings("unchecked")
	private static Object resolveNoises(Object unresolvedDf, Object noiseConfig) {
		try {
			Class<?> dfClass = unresolvedDf.getClass();
			Class<?> visitorClass = null;
			// First try literal candidate names.
			for (String name : new String[]{
					"net.minecraft.world.level.levelgen.DensityFunction$Visitor",
					"net.minecraft.world.level.levelgen.DensityFunction.Visitor",
					"net.minecraft.world.level.levelgen.DensityFunction$Visitor",
					"net.minecraft.world.level.levelgen.DensityFunction.Visitor",
			}) {
				try { visitorClass = Class.forName(name); break; }
				catch (ClassNotFoundException ignored) { /* next */ }
			}
			// Fallback: locate the DensityFunction interface and walk its
			// inner classes looking for any nested interface whose name
			// contains "Visitor" — yarn likely just renamed the inner.
			if (visitorClass == null) {
				try {
					Class<?> dfInterface = Class.forName(
							"net.minecraft.world.level.levelgen.DensityFunction");
					for (Class<?> inner : dfInterface.getClasses()) {
						if (inner.isInterface() && inner.getSimpleName().toLowerCase().contains("visitor")) {
							visitorClass = inner;
							break;
						}
					}
					if (visitorClass == null) {
						// Log once so we can see what inner classes exist.
						StringBuilder sb = new StringBuilder("DF inner interfaces:");
						for (Class<?> inner : dfInterface.getClasses()) {
							sb.append(' ').append(inner.getSimpleName())
									.append('(').append(inner.isInterface() ? "I" : "C").append(')');
						}
						logResolverActivityOnce(sb.toString(), 0, 0);
					}
				} catch (ClassNotFoundException e) {
					logResolverActivityOnce("DENSITY_FUNCTION_CLASS_NOT_FOUND", 0, 0);
				}
			}
			if (visitorClass == null) {
				logResolverActivityOnce("VISITOR_CLASS_NOT_FOUND", 0, 0);
				return null;
			}
			Method getOrCreateNoise = findGetOrCreateNoise(noiseConfig);
			if (getOrCreateNoise == null) {
				logResolverActivityOnce("GET_OR_CREATE_NOISE_NOT_FOUND", 0, 0);
				return null;
			}

			// Identify visitor methods by SIGNATURE. The interface has two:
			//   1. apply(DensityFunction) → DensityFunction       (structural)
			//   2. visitNoise(NoiseHolder) → NoiseHolder          (renamed in yarn)
			// We dispatch by method-name only at first; if visitNoise is
			// renamed, dispatch by parameter type containing "NoiseHolder".
			java.util.concurrent.atomic.AtomicInteger applyCalls = new java.util.concurrent.atomic.AtomicInteger();
			java.util.concurrent.atomic.AtomicInteger visitNoiseCalls = new java.util.concurrent.atomic.AtomicInteger();
			Object proxy = java.lang.reflect.Proxy.newProxyInstance(
					visitorClass.getClassLoader(),
					new Class<?>[]{visitorClass},
					(p, method, args) -> {
						String name = method.getName();
						if (args != null && args.length == 1) {
							String paramType = method.getParameterTypes()[0].getSimpleName();
							// Yarn 1.21.11 renamed mojmap NoiseHolder to
							// DensityFunction$Noise — SimpleName is just
							// "Noise". Older "NoiseHolder" kept as forward
							// compat fallback.
							if (paramType.equals("Noise") || paramType.contains("NoiseHolder")) {
								visitNoiseCalls.incrementAndGet();
								return resolveNoiseHolder(args[0], noiseConfig, getOrCreateNoise);
							}
							// Structural apply. BlendedNoise / InterpolatedNoiseSampler
							// instances in the registry are seeded with 0L
							// (createUnseeded). Vanilla's RandomState re-seeds them
							// via `noise.withNewRandom(terrainRandom)` where
							// terrainRandom = `random.fromHashOf("minecraft:terrain")`.
							// Mirror that here so the vanilla-side reference matches
							// the world's actual seeded noise.
							Object child = args[0];
							if (child != null) {
								String childCls = child.getClass().getSimpleName();
								if (childCls.equals("InterpolatedNoiseSampler")
										|| childCls.equals("BlendedNoise")
										|| childCls.contains("InterpolatedNoiseSampler")
										|| childCls.contains("BlendedNoise")) {
									Object reseeded = reseedBlendedNoise(child, noiseConfig);
									if (reseeded != null) {
										applyCalls.incrementAndGet();
										return reseeded;
									}
								}
							}
							applyCalls.incrementAndGet();
							return args[0];
						}
						if (name.equals("toString")) return "ferrite$resolver";
						if (name.equals("hashCode")) return System.identityHashCode(p);
						if (name.equals("equals")) return p == args[0];
						return null;
					}
			);

			Method mapAll = findMapAll(dfClass, visitorClass);
			if (mapAll == null) {
				ExampleMod.LOGGER.warn("[df-parity] no mapAll on {}", dfClass.getName());
				return null;
			}
			Object resolved = mapAll.invoke(unresolvedDf, proxy);
			// Log once per first-of-each-DF: how many calls we got.
			logResolverActivityOnce(dfClass.getName(), applyCalls.get(), visitNoiseCalls.get());
			return resolved;
		} catch (ReflectiveOperationException | RuntimeException e) {
			ExampleMod.LOGGER.warn("[df-parity] resolveNoises failed: {}", e.toString());
			return null;
		}
	}

	private static final java.util.concurrent.ConcurrentHashMap<String, Boolean> seenResolverDF =
			new java.util.concurrent.ConcurrentHashMap<>();
	private static void logResolverActivityOnce(String dfClass, int applyCalls, int visitNoiseCalls) {
		if (seenResolverDF.putIfAbsent(dfClass, Boolean.TRUE) == null) {
			ExampleMod.LOGGER.info("[df-parity] resolver on {}: apply={} visitNoise={}",
					dfClass, applyCalls, visitNoiseCalls);
		}
	}

	/**
	 * Re-seed a registry BlendedNoise / InterpolatedNoiseSampler with
	 * the world's terrain random — same as RandomState's NoiseWiringHelper
	 * does internally via `noise.withNewRandom(terrainRandom)`.
	 *
	 * <p>Yarn 1.21.11 renames:
	 * - {@code PositionalRandomFactory} → {@code PositionalRandomFactory}
	 * - {@code fromHashOf(String)} → {@code split(String)}
	 * - {@code withNewRandom(RandomSource)} → {@code copyWithRandom(RandomSource)}
	 *
	 * <p>Returns the re-seeded instance or null on any reflection failure
	 * (caller falls through to the unresolved instance).
	 */
	private static final java.util.concurrent.atomic.AtomicBoolean reseedLogged =
			new java.util.concurrent.atomic.AtomicBoolean(false);

	private static Object reseedBlendedNoise(Object blendedNoise, Object noiseConfig) {
		boolean log = reseedLogged.compareAndSet(false, true);
		try {
			Object splitter = findFieldByTypeContains(noiseConfig,
					new String[]{"PositionalRandomFactory", "RandomDeriver", "PositionalRandomFactory", "RandomFactory"});
			if (splitter == null) {
				if (log) ExampleMod.LOGGER.warn("[df-parity] reseed: no splitter on noiseConfig {}", noiseConfig.getClass().getName());
				return null;
			}
			if (log) ExampleMod.LOGGER.info("[df-parity] reseed: splitter type {}", splitter.getClass().getName());

			Method splitMethod = findStringMethod(splitter,
					new String[]{"split", "fromHashOf", "fromHashOfString"});
			if (splitMethod == null) {
				if (log) ExampleMod.LOGGER.warn("[df-parity] reseed: no split(String) on splitter");
				return null;
			}
			if (log) ExampleMod.LOGGER.info("[df-parity] reseed: split method = {}", splitMethod.getName());
			Object terrainRandom = splitMethod.invoke(splitter, "minecraft:terrain");
			if (terrainRandom == null) {
				if (log) ExampleMod.LOGGER.warn("[df-parity] reseed: terrainRandom null");
				return null;
			}
			if (log) ExampleMod.LOGGER.info("[df-parity] reseed: terrainRandom type {}", terrainRandom.getClass().getName());

			Method copyWith = findSingleArgMethod(blendedNoise,
					new String[]{"copyWithRandom", "withNewRandom"},
					terrainRandom.getClass());
			if (copyWith == null) {
				if (log) ExampleMod.LOGGER.warn("[df-parity] reseed: no copyWithRandom on {} matching arg {}",
						blendedNoise.getClass().getName(), terrainRandom.getClass().getName());
				return null;
			}
			if (log) ExampleMod.LOGGER.info("[df-parity] reseed: copyWith method = {}", copyWith.getName());
			Object result = copyWith.invoke(blendedNoise, terrainRandom);
			if (log) ExampleMod.LOGGER.info("[df-parity] reseed: success — new instance {}", result == null ? "null" : "ok");
			return result;
		} catch (ReflectiveOperationException | RuntimeException e) {
			if (log) ExampleMod.LOGGER.warn("[df-parity] reseed: exception {}", e.toString());
			return null;
		}
	}

	private static Object findFieldByTypeContains(Object obj, String[] typeHints) {
		Class<?> cls = obj.getClass();
		while (cls != null && cls != Object.class) {
			for (java.lang.reflect.Field f : cls.getDeclaredFields()) {
				String typeName = f.getType().getSimpleName();
				for (String hint : typeHints) {
					if (typeName.contains(hint)) {
						f.setAccessible(true);
						try {
							Object v = f.get(obj);
							if (v != null) return v;
						} catch (IllegalAccessException ignored) { /* next */ }
					}
				}
			}
			cls = cls.getSuperclass();
		}
		return null;
	}

	private static Method findStringMethod(Object target, String[] names) {
		Class<?> cls = target.getClass();
		while (cls != null && cls != Object.class) {
			for (Method m : cls.getDeclaredMethods()) {
				if (m.getParameterCount() != 1) continue;
				if (m.getParameterTypes()[0] != String.class) continue;
				for (String n : names) {
					if (m.getName().equals(n)) {
						m.setAccessible(true);
						return m;
					}
				}
			}
			for (Class<?> iface : cls.getInterfaces()) {
				for (Method m : iface.getDeclaredMethods()) {
					if (m.getParameterCount() != 1) continue;
					if (m.getParameterTypes()[0] != String.class) continue;
					for (String n : names) {
						if (m.getName().equals(n)) {
							m.setAccessible(true);
							return m;
						}
					}
				}
			}
			cls = cls.getSuperclass();
		}
		return null;
	}

	private static Method findSingleArgMethod(Object target, String[] names, Class<?> argType) {
		Class<?> cls = target.getClass();
		while (cls != null && cls != Object.class) {
			for (Method m : cls.getDeclaredMethods()) {
				if (m.getParameterCount() != 1) continue;
				Class<?> p = m.getParameterTypes()[0];
				if (!p.isAssignableFrom(argType)) continue;
				for (String n : names) {
					if (m.getName().equals(n)) {
						m.setAccessible(true);
						return m;
					}
				}
			}
			cls = cls.getSuperclass();
		}
		return null;
	}

	private static Method findGetOrCreateNoise(Object noiseConfig) {
		for (String n : new String[]{"getOrCreateNoise", "getOrCreateNoiseSampler", "getOrCreateSampler"}) {
			for (Method m : noiseConfig.getClass().getDeclaredMethods()) {
				if (m.getName().equals(n) && m.getParameterCount() == 1) {
					m.setAccessible(true);
					return m;
				}
			}
		}
		return null;
	}

	private static Method findMapAll(Class<?> dfClass, Class<?> visitorClass) {
		// Yarn renames mojmap `mapAll` to something like `apply`. Find by
		// signature: a method taking exactly the visitor interface as its
		// single parameter, returning DensityFunction (or its supertype).
		Class<?> c = dfClass;
		while (c != null && c != Object.class) {
			for (Method m : c.getDeclaredMethods()) {
				if (m.getParameterCount() == 1 && m.getParameterTypes()[0].equals(visitorClass)) {
					m.setAccessible(true);
					return m;
				}
			}
			c = c.getSuperclass();
		}
		for (Class<?> iface : dfClass.getInterfaces()) {
			Method m = findMapAll(iface, visitorClass);
			if (m != null) return m;
		}
		return null;
	}

	/**
	 * Resolve a NoiseHolder: extract key from its noiseData holder,
	 * call `noiseConfig.getOrCreateNoise(key)` to get the live NormalNoise,
	 * build a new NoiseHolder with it bound.
	 */
	private static Object resolveNoiseHolder(Object noiseHolder, Object noiseConfig, Method getOrCreateNoise) {
		if (noiseHolder == null) return null;
		try {
			// noiseHolder.noiseData() → Holder<NoiseParameters>
			Method noiseDataM = noiseHolder.getClass().getMethod("noiseData");
			Object noiseData = noiseDataM.invoke(noiseHolder);
			if (noiseData == null) return noiseHolder;
			// holder.unwrapKey() → Optional<ResourceKey<NoiseParameters>>
			Method unwrapKey = noiseData.getClass().getMethod("getKey");
			Object opt = unwrapKey.invoke(noiseData);
			if (!(opt instanceof java.util.Optional<?> o) || !o.isPresent()) return noiseHolder;
			Object key = o.get();
			// noiseConfig.getOrCreateNoise(key) → NormalNoise
			Object resolvedNoise = getOrCreateNoise.invoke(noiseConfig, key);
			// Construct new NoiseHolder(noiseData, resolvedNoise).
			// Yarn record: NoiseHolder(Holder<NoiseParameters>, @Nullable NormalNoise).
			java.lang.reflect.Constructor<?> ctor = null;
			for (java.lang.reflect.Constructor<?> c : noiseHolder.getClass().getDeclaredConstructors()) {
				if (c.getParameterCount() == 2) { ctor = c; break; }
			}
			if (ctor == null) return noiseHolder;
			ctor.setAccessible(true);
			return ctor.newInstance(noiseData, resolvedNoise);
		} catch (ReflectiveOperationException | RuntimeException e) {
			return noiseHolder; // keep original if resolution fails
		}
	}

	/** Compute vanilla's DF at integer coords. Returns null on failure. */
	private static Double computeVanilla(Object vanillaDf, int x, int y, int z) {
		// Vanilla `DensityFunction.compute(FunctionContext)`. Yarn likely
		// renames to `sample(DensityFunction.FunctionContext)`. Try both.
		Object context = buildSinglePointContext(x, y, z);
		if (context == null) return null;
		Method compute = findCompute(vanillaDf.getClass(), context.getClass());
		if (compute == null) return null;
		try {
			Object r = compute.invoke(vanillaDf, context);
			return r instanceof Double d ? d : null;
		} catch (ReflectiveOperationException e) {
			return null;
		}
	}

	private static Object buildSinglePointContext(int x, int y, int z) {
		// Yarn renames DensityFunction.SinglePointContext — probe likely names.
		String[] candidates = {
			"net.minecraft.world.level.levelgen.DensityFunction$UnblendedNoisePos",
			"net.minecraft.world.level.levelgen.DensityFunction$SinglePointContext",
			"net.minecraft.world.level.levelgen.DensityFunction$SinglePointContext",
		};
		for (String name : candidates) {
			try {
				Class<?> cls = Class.forName(name);
				java.lang.reflect.Constructor<?> ctor = cls.getConstructor(int.class, int.class, int.class);
				return ctor.newInstance(x, y, z);
			} catch (ReflectiveOperationException ignored) {
				// try next
			}
		}
		return null;
	}

	private static Method findCompute(Class<?> dfCls, Class<?> ctxCls) {
		Class<?> c = dfCls;
		while (c != null && c != Object.class) {
			for (Method m : c.getDeclaredMethods()) {
				if ((m.getName().equals("compute") || m.getName().equals("sample"))
						&& m.getParameterCount() == 1
						&& m.getParameterTypes()[0].isAssignableFrom(ctxCls)
						&& m.getReturnType() == double.class) {
					m.setAccessible(true);
					return m;
				}
			}
			c = c.getSuperclass();
		}
		// Fallback: walk interfaces.
		for (Class<?> iface : dfCls.getInterfaces()) {
			Method m = findCompute(iface, ctxCls);
			if (m != null) return m;
		}
		return null;
	}

	private static Object resolveDfRegistry(MinecraftServer server) {
		try {
			Object manager = server.getRegistryManager();
			Class<?> registryKeysClass = Class.forName("net.minecraft.core.registries.Registries");
			Object dfKey = registryKeysClass.getField("DENSITY_FUNCTION").get(null);
			for (String n : new String[]{"getOrThrow", "get", "getRegistry"}) {
				try {
					Method m = manager.getClass().getMethod(n,
							Class.forName("net.minecraft.resources.ResourceKey"));
					Object r = m.invoke(manager, dfKey);
					if (r != null) return r;
				} catch (ReflectiveOperationException ignored) {
					// try next
				}
			}
		} catch (ReflectiveOperationException | RuntimeException e) {
			ExampleMod.LOGGER.warn("[df-parity] resolveDfRegistry failed: {}", e.toString());
		}
		return null;
	}

	/**
	 * Look up a DF by name from the registry. NOTE: registry DFs are
	 * UNRESOLVED — their `NoiseHolder.noise` fields are null, so compute
	 * paths that hit a noise leaf return 0 instead of the real sample.
	 * For RESOLVED DFs (with live noise instances), use the RandomState
	 * route via {@link #lookupResolved}.
	 */
	private static Object lookupByName(Object registry, String fullName) {
		try {
			if (!(registry instanceof Iterable<?> iter)) return null;
			Method getId = registry.getClass().getMethod("getId", Object.class);
			for (Object entry : iter) {
				Object id = getId.invoke(registry, entry);
				if (id != null && fullName.equals(id.toString())) {
					return entry;
				}
			}
		} catch (ReflectiveOperationException ignored) {
			// fall through
		}
		return null;
	}

	/**
	 * Look up a RESOLVED DF by name. Walks
	 * `RandomState.getNoiseRouter()` and resolves each named field by
	 * matching its DF reference back to the registry's name. This is the
	 * tree that has live noise instances bound, so its compute path
	 * matches what vanilla would actually use during chunk gen.
	 *
	 * <p>Yarn `NoiseRouter` is a record with ~16 named density-function
	 * fields (temperature, vegetation, continents, erosion, depth,
	 * ridges, initialDensityWithoutJaggedness, finalDensity,
	 * veinToggle, veinRidged, veinGap, plus barrier, fluidLevelFloodedness,
	 * fluidLevelSpread, lava). We probe each accessor; the resulting
	 * DF is the RESOLVED tree.
	 */
	private static Object lookupResolved(Object noiseConfig, String fullName) {
		// Only use the router-field shortcut for names where the full
		// identifier EQUALS a known router field. The router holds ~15
		// specific DFs (temperature, vegetation, continents, erosion,
		// depth, ridges, etc.) — those are published as root climate /
		// terrain samplers with these exact identifiers. Names like
		// `minecraft:overworld/depth` are DIFFERENT DFs (registry-only,
		// router DF `depth` is the climate depth, not the overworld's
		// offset-based depth). Matching on loose substrings breaks them.
		String[] routerOnly = {
			"minecraft:shift_x", "minecraft:shift_z",
			"minecraft:y",
		};
		boolean routerTarget = false;
		for (String r : routerOnly) {
			if (fullName.equals(r)) { routerTarget = true; break; }
		}
		if (!routerTarget) return null; // force fallback to registry (unresolved)
		Object router = null;
		for (String n : new String[]{"getNoiseRouter", "noiseRouter", "router", "getRouter"}) {
			try {
				Method m = noiseConfig.getClass().getMethod(n);
				router = m.invoke(noiseConfig);
				if (router != null) break;
			} catch (ReflectiveOperationException ignored) {
				// next
			}
		}
		if (router == null) return null;
		String nameLower = fullName.toLowerCase();
		String[] parts = nameLower.split(":");
		String last = parts[parts.length - 1];
		for (Method m : router.getClass().getDeclaredMethods()) {
			if (m.getParameterCount() != 0) continue;
			String mname = m.getName().toLowerCase();
			if (mname.equals(last) || mname.equals(last.replace("_", ""))) {
				try {
					m.setAccessible(true);
					Object df = m.invoke(router);
					if (df != null) return df;
				} catch (ReflectiveOperationException ignored) {
					// next
				}
			}
		}
		return null;
	}

	private static Method findMethod(Class<?> cls, String name, Class<?>... paramTypes) {
		Class<?> c = cls;
		while (c != null && c != Object.class) {
			for (Method m : c.getDeclaredMethods()) {
				if (m.getName().equals(name) && m.getParameterCount() == paramTypes.length) {
					return m;
				}
			}
			c = c.getSuperclass();
		}
		return null;
	}
}
