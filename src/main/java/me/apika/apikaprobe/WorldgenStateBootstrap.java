package me.apika.apikaprobe;

import java.lang.reflect.Method;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import net.fabricmc.fabric.api.event.lifecycle.v1.ServerWorldEvents;
import net.minecraft.registry.Registry;
import net.minecraft.registry.RegistryKey;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.Identifier;
import net.minecraft.world.World;

/**
 * One-shot world-load handler that builds the Rust-side seed-derived
 * worldgen state (see {@code rust/mod/src/worldgen_state.rs} and
 * {@code docs/SEED_DRIVEN_DISPATCH.md}).
 *
 * <p>On overworld load: takes the world seed, walks the
 * {@code NOISE_PARAMETERS} registry for every named noise, and pushes
 * each {@code (identifier, firstOctave, amplitudes)} tuple to Rust via
 * {@link RustBridge#registerNoiseParameter}. Finalizes once the
 * registry walk completes.
 *
 * <p>After this runs, Rust holds a {@code WorldgenState} keyed by full
 * identifier strings (e.g. {@code "minecraft:temperature"}) that
 * matches what vanilla's {@code RandomState} (yarn {@code NoiseConfig})
 * would produce for the same seed — bit-exact, by construction. Rust
 * evaluators can sample any noise without crossing JNI per-position.
 *
 * <p>Reflective accessors on the {@code DoublePerlinNoiseSampler.NoiseParameters}
 * record are used so this file doesn't pin to one yarn signature; the
 * record component names {@code firstOctave} / {@code amplitudes} are
 * stable since the record was introduced and unlikely to drift.
 */
public final class WorldgenStateBootstrap {
	private static final AtomicBoolean initialized = new AtomicBoolean(false);
	/** Full identifier strings (e.g. {@code "minecraft:temperature"})
	 *  for every noise successfully pushed to Rust. Read by the parity
	 *  validator so it doesn't have to re-enumerate the yarn registry. */
	private static volatile List<String> registeredNames = Collections.emptyList();

	private WorldgenStateBootstrap() {}

	public static List<String> registeredNames() {
		return registeredNames;
	}

	public static void register() {
		ServerWorldEvents.LOAD.register((server, world) -> {
			if (world.getRegistryKey() != World.OVERWORLD) {
				return;
			}
			if (initialized.getAndSet(true)) {
				return;
			}
			try {
				bootstrap(server, world);
			} catch (Throwable t) {
				ExampleMod.LOGGER.error(
						"[worldgen-init] bootstrap failed — Rust seed-driven dispatch will be unavailable",
						t);
			}
		});
	}

	private static void bootstrap(net.minecraft.server.MinecraftServer server, ServerWorld world) {
		if (!RustBridge.NATIVE_AVAILABLE) {
			return;
		}
		long seed = world.getSeed();
		if (!RustBridge.initWorldgenState(seed)) {
			ExampleMod.LOGGER.warn(
					"[worldgen-init] initWorldgenState returned false (seed={}) — Rust state already finalized?",
					seed);
			return;
		}

		// Resolve the noise-parameters registry via reflection so this
		// file is decoupled from yarn's `DynamicRegistryManager` accessor
		// drift (`get` / `getOrThrow` / `getOptional` rotate across
		// versions). Bootstrap is one-shot at world load; reflection
		// cost here is irrelevant.
		@SuppressWarnings("rawtypes")
		Registry noiseRegistry = resolveNoiseRegistry(server);
		if (noiseRegistry == null) {
			ExampleMod.LOGGER.warn("[worldgen-init] NOISE_PARAMETERS registry missing — aborting");
			return;
		}

		int registered = 0;
		int failed = 0;
		List<String> names = new ArrayList<>();
		for (Object entry : noiseRegistry) {
			@SuppressWarnings("unchecked")
			Identifier id = noiseRegistry.getId(entry);
			if (id == null) {
				failed++;
				continue;
			}
			NoiseParamsView view = readNoiseParameters(entry);
			if (view == null) {
				failed++;
				continue;
			}
			if (pushToRust(id, view)) {
				registered++;
				names.add(id.toString());
			} else {
				failed++;
			}
		}
		registeredNames = Collections.unmodifiableList(names);

		// Biome R-tree bootstrap. If the overworld's biome source isn't
		// MultiNoiseBiomeSource (e.g., custom dim), we just skip — Rust
		// queries will return -1 and consumers fall back to vanilla.
		int biomeCount = registerBiomes(world);

		// Density function bootstrap. Walks the DENSITY_FUNCTION registry,
		// encodes each tree via DensityFunctionWalker, and pushes to Rust.
		// Unknown node types emit CONSTANT(0) stubs (walker logs them once).
		int dfCount = registerDensityFunctions(server);

		// Climate DFs from the live noise router (resolved noises bound).
		// Walked from NoiseConfig (captured by mixin); registered under
		// `ferrite:climate/<axis>` so Rust can sample climate end-to-end
		// without going through vanilla's Climate.Sampler.
		int climateCount = registerResolvedRouterClimateDfs(seed);
		dfCount += climateCount;

		boolean finalized = RustBridge.finalizeWorldgenState();
		if (!finalized) {
			ExampleMod.LOGGER.warn(
					"[worldgen-init] finalizeWorldgenState returned false ({} registered, {} failed)",
					registered, failed);
			return;
		}
		ExampleMod.LOGGER.info(
				"[worldgen-init] Rust worldgen state ready — seed={}, noises registered={}, biomes={}, dfs={}, failed={}",
				seed, registered, biomeCount, dfCount, failed);

		// Climate sampler capture — must run AFTER finalize so
		// WorldgenParity.findOverworldNoiseConfig can match against
		// Rust's root seeds (which are 0 until finalize publishes state).
		captureOverworldClimateSampler();
	}

	/**
	 * Walk the live overworld {@code NoiseConfig.noiseRouter()} and
	 * register its 6 climate DFs (temperature, vegetation, continents,
	 * erosion, depth, ridges) into the in-progress Rust build under
	 * {@code ferrite:climate/<axis>} names. These DFs already have their
	 * NoiseHolders bound to live NormalNoise instances, so encoding them
	 * via the walker preserves the right noise references.
	 *
	 * <p>Must run BEFORE finalize. Uses {@link RustBridge#rootSeedsForSeed}
	 * to find the matching captured NoiseConfig (worldgenRootSeedLo/Hi
	 * return 0 pre-finalize, so we can't go through that path here).
	 *
	 * @return number of climate DFs registered
	 */
	private static int registerResolvedRouterClimateDfs(long seed) {
		long[] expected = RustBridge.rootSeedsForSeed(seed);
		if (expected == null || expected.length != 2) {
			ExampleMod.LOGGER.warn("[worldgen-init] climate-router: rootSeedsForSeed returned bad value");
			return 0;
		}
		Object noiseConfig = WorldgenParity.findNoiseConfigBySeeds(expected[0], expected[1]);
		if (noiseConfig == null) {
			ExampleMod.LOGGER.info("[worldgen-init] climate-router: no matching NoiseConfig captured (mixin order?)");
			return 0;
		}
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
		if (router == null) return 0;

		String[][] climateFields = {
			// Climate axes (6).
			{"temperature", "ferrite:climate/temperature"},
			{"vegetation", "ferrite:climate/vegetation"},
			{"continents", "ferrite:climate/continents"},
			{"erosion", "ferrite:climate/erosion"},
			{"depth", "ferrite:climate/depth"},
			{"ridges", "ferrite:climate/ridges"},
			// Terrain density. Yarn 1.21.11 has preliminarySurfaceLevel
			// (used by chunkgen for max surface query) and finalDensity
			// (per-cell-corner sampler).
			{"preliminarySurfaceLevel", "ferrite:terrain/preliminary_surface_level"},
			{"finalDensity", "ferrite:terrain/final_density"},
			// Aquifer / cave noises.
			{"barrierNoise", "ferrite:aquifer/barrier"},
			{"fluidLevelFloodednessNoise", "ferrite:aquifer/fluid_level_floodedness"},
			{"fluidLevelSpreadNoise", "ferrite:aquifer/fluid_level_spread"},
			{"lavaNoise", "ferrite:aquifer/lava"},
			// Ore vein triplet.
			{"veinToggle", "ferrite:vein/toggle"},
			{"veinRidged", "ferrite:vein/ridged"},
			{"veinGap", "ferrite:vein/gap"},
		};
		int rootRegistered = 0;
		List<String> registeredNames = new ArrayList<>();
		java.util.IdentityHashMap<Object, String> identityMap = new java.util.IdentityHashMap<>();
		// Top-level router DFs we'll deep-walk after registration.
		List<Object[]> rootDfs = new ArrayList<>();
		for (String[] field : climateFields) {
			try {
				Method m = router.getClass().getMethod(field[0]);
				Object df = m.invoke(router);
				if (df == null) continue;
				ByteBuffer bytecode = DensityFunctionWalker.encode(df);
				if (bytecode == null) continue;
				byte[] nameBytes = field[1].getBytes(StandardCharsets.UTF_8);
				ByteBuffer nameBuf = ByteBuffer.allocateDirect(nameBytes.length).order(ByteOrder.nativeOrder());
				nameBuf.put(nameBytes); nameBuf.flip();
				if (RustBridge.registerDensityFunction(nameBuf, nameBytes.length, bytecode, bytecode.limit())) {
					rootRegistered++;
					registeredNames.add(field[1]);
					// Track the live DF instance → registered name. Phase 2.5
					// caches use this to identify which DF a wrapping marker
					// (FlatCache/Interpolated/etc.) is caching.
					identityMap.put(df, field[1]);
					rootDfs.add(new Object[]{df, field[1]});
				}
			} catch (ReflectiveOperationException ignored) {
				// router doesn't expose this accessor in current yarn — skip
			}
		}
		// Phase 2.5 step 1 — deep Marker walk. For every Marker reachable
		// from any router root, register the wrapped subtree under
		// `ferrite:auto/<root>/<kind>_<i>` and add `marker → name` to
		// the identity map. Cache mixins use this to bulk-fill via Rust.
		int totalDeepMarkers = 0;
		int totalDeepRegistered = 0;
		int totalDeepFailed = 0;
		java.util.Map<String, String> fingerprintMap = new java.util.HashMap<>();
		for (Object[] rootEntry : rootDfs) {
			Object rootDf = rootEntry[0];
			String rootName = (String) rootEntry[1];
			DeepMarkerWalker.Result r = DeepMarkerWalker.walk(rootDf, rootName, identityMap, fingerprintMap);
			totalDeepMarkers += r.markersFound;
			totalDeepRegistered += r.markersRegistered;
			totalDeepFailed += r.registrationFailures;
		}
		// Synthetic registration: vanilla's NoiseChunk constructor wraps
		// `cacheAllInCell(add(finalDensity, BeardifierMarker))` at chunk
		// gen time — a tree that doesn't exist in any router root. Build
		// its bytecode here so the fingerprint matches when the resulting
		// CacheAllInCell wrapper hits our mixin.
		Object finalDensityDf = null;
		for (Object[] re : rootDfs) {
			if ("ferrite:terrain/final_density".equals(re[1])) {
				finalDensityDf = re[0]; break;
			}
		}
		boolean syntheticOk = registerFullNoiseDensitySynthetic(finalDensityDf, fingerprintMap);

		ExampleMod.LOGGER.info(
				"[worldgen-init] deep-marker walk: found={} registered={} failed={} mapSize={} fpMapSize={} synthetic={}",
				totalDeepMarkers, totalDeepRegistered, totalDeepFailed, identityMap.size(), fingerprintMap.size(),
				syntheticOk ? "ok" : "skipped");
		// Publish the identity + fingerprint maps so cache wrappers can look up names.
		identifiedRouterDfs = java.util.Collections.unmodifiableMap(identityMap);
		fingerprintToName = java.util.Collections.unmodifiableMap(fingerprintMap);
		// Merge synthetic names into the validator-visible list. Builder
		// state was already published by registerDensityFunctions; we
		// extend it so /ferrite density validate iterates these too.
		if (!registeredNames.isEmpty()) {
			List<String> merged = new ArrayList<>(registeredDensityFunctionNames);
			merged.addAll(registeredNames);
			registeredDensityFunctionNames = Collections.unmodifiableList(merged);
		}
		ExampleMod.LOGGER.info(
				"[worldgen-init] climate-router: {} root DFs + {} deep markers registered ({})",
				rootRegistered, totalDeepRegistered, registeredNames);
		return rootRegistered + totalDeepRegistered;
	}

	/**
	 * Build and register the synthetic {@code Add(finalDensity, BeardifierMarker)}
	 * subtree that vanilla's {@code NoiseChunk} constructor wraps with
	 * {@code cacheAllInCell(...)} during chunkgen. Adds the fingerprint to
	 * {@code fpMap} so the {@code CacheAllInCell} wrapper produced at chunk-wrap
	 * time matches when our mixin fingerprints its {@code wrapped()} subtree.
	 *
	 * <p>The bytecode is composed by hand:
	 * {@code [OP_ADD][encode(finalDensity)][OP_CONSTANT][f64 0.0]}.
	 * Since {@code DensityFunctionWalker} encodes {@code BeardifierMarker} as
	 * {@code OP_CONSTANT 0.0} (it's a singleton enum returning 0 from
	 * {@code compute()}), this matches what the mixin will see post-mapAll.
	 */
	private static boolean registerFullNoiseDensitySynthetic(Object finalDensity,
			java.util.Map<String, String> fpMap) {
		if (finalDensity == null) return false;
		try {
			ByteBuffer fdBytes = DensityFunctionWalker.encode(finalDensity);
			if (fdBytes == null) return false;
			int fdLen = fdBytes.limit();
			byte[] fdArr = new byte[fdLen];
			fdBytes.position(0);
			fdBytes.get(fdArr);

			// OP_ADD = 0x01; OP_CONSTANT = 0x00 (must match DensityFunctionWalker constants)
			java.io.ByteArrayOutputStream baos = new java.io.ByteArrayOutputStream(fdLen + 16);
			baos.write(0x01);
			baos.write(fdArr, 0, fdLen);
			baos.write(0x00);
			ByteBuffer dbuf = ByteBuffer.allocate(8).order(ByteOrder.nativeOrder()).putDouble(0.0);
			baos.write(dbuf.array(), 0, 8);
			byte[] composed = baos.toByteArray();

			ByteBuffer composedBuf = ByteBuffer.allocateDirect(composed.length).order(ByteOrder.nativeOrder());
			composedBuf.put(composed);
			composedBuf.flip();

			String name = "ferrite:synthetic/full_noise_density";
			byte[] nameBytes = name.getBytes(StandardCharsets.UTF_8);
			ByteBuffer nameBuf = ByteBuffer.allocateDirect(nameBytes.length).order(ByteOrder.nativeOrder());
			nameBuf.put(nameBytes); nameBuf.flip();

			if (!RustBridge.registerDensityFunction(nameBuf, nameBytes.length, composedBuf, composed.length)) {
				return false;
			}
			StringBuilder fp = new StringBuilder(composed.length * 2);
			for (byte b : composed) {
				fp.append(Character.forDigit((b >> 4) & 0xF, 16));
				fp.append(Character.forDigit(b & 0xF, 16));
			}
			fpMap.putIfAbsent(fp.toString(), name);
			return true;
		} catch (RuntimeException e) {
			ExampleMod.LOGGER.warn(
					"[worldgen-init] cellCache synthetic registration failed: {}", e.toString());
			return false;
		}
	}

	/** Names of registered biomes, parallel to Rust's i32 IDs (slot
	 *  in the array == biome ID Rust returns from queryBiomeAtTarget).
	 *  Used by the parity validator to map Rust IDs back to identifiers. */
	private static volatile List<String> registeredBiomeNames = Collections.emptyList();

	public static List<String> registeredBiomeNames() {
		return registeredBiomeNames;
	}

	/**
	 * Walk the overworld's {@code MultiNoiseBiomeSource} parameters list,
	 * pack each {@code (ParameterPoint, Biome)} entry into the wire
	 * format Rust expects (112 bytes/entry), and push to Rust via
	 * {@link RustBridge#registerBiomeEntries}.
	 *
	 * <p>Returns the number of biomes registered, or 0 if the source
	 * isn't multi-noise (e.g., FixedBiomeSource on a debug world).
	 *
	 * <p>Reflective throughout — yarn signatures for ChunkGenerator /
	 * BiomeSource / Climate.ParameterList shift across versions, and
	 * this fires once at world load so perf doesn't matter.
	 */
	private static int registerBiomes(ServerWorld world) {
		try {
			Object chunkManager = world.getChunkManager();
			Method getChunkGenerator = findMethod(chunkManager.getClass(), "getChunkGenerator");
			if (getChunkGenerator == null) {
				ExampleMod.LOGGER.warn("[worldgen-init] no getChunkGenerator() on {}", chunkManager.getClass().getName());
				return 0;
			}
			Object generator = getChunkGenerator.invoke(chunkManager);
			Method getBiomeSource = findMethod(generator.getClass(), "getBiomeSource");
			if (getBiomeSource == null) {
				ExampleMod.LOGGER.warn("[worldgen-init] no getBiomeSource() on {}", generator.getClass().getName());
				return 0;
			}
			Object biomeSource = getBiomeSource.invoke(generator);

			// MultiNoiseBiomeSource has a private method (mojmap
			// `parameters()`) returning a Climate.ParameterList<Holder<Biome>>.
			// Yarn renames it; try several candidates + walk field types
			// as a fallback.
			Method parametersMethod = null;
			for (String candidate : new String[]{
					"parameters", "getBiomeEntries", "method_39537",
					"getParameterList", "getEntries"}) {
				parametersMethod = findMethod(biomeSource.getClass(), candidate);
				if (parametersMethod != null) break;
			}
			if (parametersMethod == null) {
				StringBuilder dump = new StringBuilder("[worldgen-init] methods on ")
						.append(biomeSource.getClass().getName()).append(": ");
				for (Method m : biomeSource.getClass().getDeclaredMethods()) {
					if (m.getParameterCount() == 0 && !m.getReturnType().equals(void.class)) {
						dump.append(m.getName()).append("():")
								.append(m.getReturnType().getSimpleName()).append(", ");
					}
				}
				ExampleMod.LOGGER.info(dump.toString());
				return 0;
			}
			parametersMethod.setAccessible(true);
			ExampleMod.LOGGER.info("[worldgen-init] parameters-accessor matched: {}",
					parametersMethod.getName());
			Object parameterList = parametersMethod.invoke(biomeSource);
			if (parameterList == null) {
				ExampleMod.LOGGER.warn("[worldgen-init] {}() returned null",
						parametersMethod.getName());
				return 0;
			}
			ExampleMod.LOGGER.info("[worldgen-init] parameter list type: {}",
					parameterList.getClass().getName());

			// Yarn renames Climate.ParameterList.values() → Entries.getEntries().
			Method valuesMethod = null;
			for (String candidate : new String[]{"values", "getEntries", "getBiomeEntries"}) {
				valuesMethod = findMethod(parameterList.getClass(), candidate);
				if (valuesMethod != null) break;
			}
			if (valuesMethod == null) {
				StringBuilder dump = new StringBuilder("[worldgen-init] methods on parameter list ")
						.append(parameterList.getClass().getName()).append(": ");
				for (Method m : parameterList.getClass().getDeclaredMethods()) {
					if (m.getParameterCount() == 0 && !m.getReturnType().equals(void.class)) {
						dump.append(m.getName()).append("():")
								.append(m.getReturnType().getSimpleName()).append(", ");
					}
				}
				ExampleMod.LOGGER.info(dump.toString());
				return 0;
			}
			valuesMethod.setAccessible(true);
			Object listObj = valuesMethod.invoke(parameterList);
			if (!(listObj instanceof List<?> entries)) {
				ExampleMod.LOGGER.warn("[worldgen-init] {}.values() returned {} (not a List)",
						parameterList.getClass().getSimpleName(),
						listObj == null ? "null" : listObj.getClass().getSimpleName());
				return 0;
			}
			ExampleMod.LOGGER.info("[worldgen-init] biome parameter list has {} entries",
					entries.size());

			int n = entries.size();
			ByteBuffer buf = ByteBuffer.allocateDirect(n * 112).order(ByteOrder.nativeOrder());
			List<String> biomeNames = new ArrayList<>(n);
			net.minecraft.registry.entry.RegistryEntry<?>[] biomeHolders =
					new net.minecraft.registry.entry.RegistryEntry<?>[n];

			for (int i = 0; i < n; i++) {
				Object pair = entries.get(i);
				// com.mojang.datafixers.util.Pair has getFirst() / getSecond().
				Method getFirst = pair.getClass().getMethod("getFirst");
				Method getSecond = pair.getClass().getMethod("getSecond");
				Object paramPoint = getFirst.invoke(pair);
				Object biomeHolder = getSecond.invoke(pair);

				String biomeName = resolveBiomeIdentifier(biomeHolder);
				biomeNames.add(biomeName);
				if (biomeHolder instanceof net.minecraft.registry.entry.RegistryEntry<?> holder) {
					biomeHolders[i] = holder;
				}

				packParameterPoint(buf, i, paramPoint, /* biomeId = */ i);
			}

			boolean ok = RustBridge.registerBiomeEntries(buf, n);
			if (!ok) {
				ExampleMod.LOGGER.warn("[worldgen-init] registerBiomeEntries returned false");
				return 0;
			}
			registeredBiomeNames = Collections.unmodifiableList(biomeNames);
			RustBiomeRouter.install(biomeHolders);
			// Pass the exact overworld biome-source reference to the parity
			// validator, bypassing the "last-constructed" mixin capture
			// (which picks up nether/end sources in non-deterministic
			// order and causes cross-world compares).
			BiomeParity.captureBiomeSource(biomeSource);
			return n;
		} catch (ReflectiveOperationException | RuntimeException e) {
			ExampleMod.LOGGER.warn("[worldgen-init] biome registration failed: {}", e.toString());
			return 0;
		}
	}

	/** Names of density functions successfully pushed to Rust. Parallel
	 *  to Rust's internal HashMap; used by the parity validator. */
	private static volatile List<String> registeredDensityFunctionNames = Collections.emptyList();

	/** IdentityHashMap of live DF instances → registered ferrite names.
	 *  Kept as a fast path even though vanilla's {@code mapAll(this::wrap)}
	 *  re-instantiates Markers per-chunk (so identity rarely matches at
	 *  chunk-wrap time). The real lookup happens via
	 *  {@link #fingerprintToName()}. */
	private static volatile java.util.Map<Object, String> identifiedRouterDfs =
			java.util.Collections.emptyMap();

	public static java.util.Map<Object, String> identifiedRouterDfs() {
		return identifiedRouterDfs;
	}

	/** Hex-bytecode fingerprint of every registered Marker's inner
	 *  subtree → registered ferrite name. Survives the
	 *  {@code mapAll(this::wrap)} re-instantiation boundary because
	 *  {@link DensityFunctionWalker} encodes both Markers and their
	 *  post-transformation cache-wrapper equivalents to identical bytes. */
	private static volatile java.util.Map<String, String> fingerprintToName =
			java.util.Collections.emptyMap();

	public static java.util.Map<String, String> fingerprintToName() {
		return fingerprintToName;
	}

	public static List<String> registeredDensityFunctionNames() {
		return registeredDensityFunctionNames;
	}

	/**
	 * Walk yarn's `DENSITY_FUNCTION` registry, encode each value via
	 * {@link DensityFunctionWalker}, and push to Rust. Unknown node
	 * types get logged once + encoded as CONSTANT(0) stubs so the
	 * registry walk completes even when yarn renames something.
	 */
	private static int registerDensityFunctions(net.minecraft.server.MinecraftServer server) {
		try {
			Object manager = server.getRegistryManager();
			// Find the DENSITY_FUNCTION registry key.
			Class<?> registryKeysClass = Class.forName("net.minecraft.registry.RegistryKeys");
			Object dfRegistryKey;
			try {
				dfRegistryKey = registryKeysClass.getField("DENSITY_FUNCTION").get(null);
			} catch (ReflectiveOperationException | RuntimeException e) {
				ExampleMod.LOGGER.warn("[worldgen-init] RegistryKeys.DENSITY_FUNCTION missing: {}", e.toString());
				return 0;
			}
			// Pull the registry via the same accessor dance we use for noises.
			@SuppressWarnings("rawtypes")
			Registry dfRegistry = null;
			for (String methodName : new String[]{"getOrThrow", "get", "getRegistry"}) {
				try {
					Method m = manager.getClass().getMethod(methodName, RegistryKey.class);
					Object r = m.invoke(manager, dfRegistryKey);
					if (r instanceof Registry<?> reg) { dfRegistry = reg; break; }
				} catch (ReflectiveOperationException ignored) {
					// try next
				}
			}
			if (dfRegistry == null) {
				ExampleMod.LOGGER.warn("[worldgen-init] could not resolve DENSITY_FUNCTION registry");
				return 0;
			}

			int registered = 0;
			int failed = 0;
			List<String> names = new ArrayList<>();
			for (Object entry : dfRegistry) {
				@SuppressWarnings("unchecked")
				Identifier id = dfRegistry.getId(entry);
				if (id == null) { failed++; continue; }
				String fullName = id.toString();
				ByteBuffer bytecode = DensityFunctionWalker.encode(entry);
				if (bytecode == null) {
					failed++;
					continue;
				}
				int byteLen = bytecode.limit();
				// Name buffer.
				byte[] nameBytes = fullName.getBytes(StandardCharsets.UTF_8);
				ByteBuffer nameBuf = ByteBuffer.allocateDirect(nameBytes.length).order(ByteOrder.nativeOrder());
				nameBuf.put(nameBytes);
				nameBuf.flip();
				boolean ok = RustBridge.registerDensityFunction(
						nameBuf, nameBytes.length, bytecode, byteLen);
				if (ok) {
					registered++;
					names.add(fullName);
				} else {
					failed++;
				}
			}
			registeredDensityFunctionNames = Collections.unmodifiableList(names);
			ExampleMod.LOGGER.info(
					"[worldgen-init] density functions: {} registered, {} failed",
					registered, failed);
			return registered;
		} catch (ReflectiveOperationException | RuntimeException e) {
			ExampleMod.LOGGER.warn("[worldgen-init] density function walk failed: {}", e.toString());
			return 0;
		}
	}

	/**
	 * Pull the overworld's {@code Climate.Sampler} (yarn
	 * {@code MultiNoiseUtil.MultiNoiseSampler}) off the chunk generator
	 * via the route {@code chunkGenerator → NoiseConfig (per-world
	 * RandomState equivalent) → multiNoiseSampler}. Stash it in
	 * {@link BiomeParity#captureClimateSampler} for unloaded biome
	 * lookups.
	 *
	 * <p>Walks several yarn-rename candidates because the accessor name
	 * has shifted across versions; falls back to scanning fields by type
	 * if no name matches.
	 */
	private static void captureOverworldClimateSampler() {
		try {
			// First, find the NoiseConfig (yarn) / RandomState (mojmap) on
			// the generator. Yarn's NoiseChunkGenerator stores it by name
			// "settings" → NoiseGeneratorSettings is wrong; the per-world
			// instance is built lazily and lives on the chunk manager. The
			// cleanest path: use the already-captured NoiseConfig that
			// matches Rust's root seeds (WorldgenParity does this for the
			// validator already — same picker logic).
			Object noiseConfig = WorldgenParity.findOverworldNoiseConfig();
			if (noiseConfig == null) {
				ExampleMod.LOGGER.info("[worldgen-init] climate sampler: no matching overworld NoiseConfig captured yet — unloaded biome lookup deferred");
				return;
			}
			// Common yarn names for the Climate.Sampler accessor.
			Object sampler = null;
			for (String candidate : new String[]{
					"getMultiNoiseSampler", "multiNoiseSampler", "sampler",
					"getSampler", "getClimateSampler"}) {
				Method m = findMethod(noiseConfig.getClass(), candidate);
				if (m != null) {
					m.setAccessible(true);
					sampler = m.invoke(noiseConfig);
					if (sampler != null) {
						ExampleMod.LOGGER.info("[worldgen-init] climate sampler accessor: {}", candidate);
						break;
					}
				}
			}
			if (sampler == null) {
				StringBuilder dump = new StringBuilder("[worldgen-init] no climate sampler accessor on ")
						.append(noiseConfig.getClass().getName()).append("; methods: ");
				for (Method m : noiseConfig.getClass().getDeclaredMethods()) {
					if (m.getParameterCount() == 0 && !m.getReturnType().equals(void.class)) {
						dump.append(m.getName()).append("():")
								.append(m.getReturnType().getSimpleName()).append(", ");
					}
				}
				ExampleMod.LOGGER.info(dump.toString());
				return;
			}
			BiomeParity.captureClimateSampler(sampler);
		} catch (ReflectiveOperationException | RuntimeException e) {
			ExampleMod.LOGGER.warn("[worldgen-init] climate sampler capture failed: {}", e.toString());
		}
	}

	private static Method findMethod(Class<?> cls, String name) {
		Class<?> c = cls;
		while (c != null && c != Object.class) {
			for (Method m : c.getDeclaredMethods()) {
				if (m.getName().equals(name) && m.getParameterCount() == 0) {
					return m;
				}
			}
			c = c.getSuperclass();
		}
		return null;
	}

	/**
	 * Pack one {@code Climate.ParameterPoint} record into the entry
	 * buffer at the given index. Reflects out the 6 {@code Parameter}
	 * components + offset, reading {@code min/max} as longs.
	 */
	private static void packParameterPoint(ByteBuffer buf, int idx, Object paramPoint, int biomeId)
			throws ReflectiveOperationException {
		int base = idx * 112;
		// i32 biome_id at offset 0; 4 bytes pad at 4; 13 i64s start at 8.
		buf.putInt(base, biomeId);

		// Six Parameter components by yarn record-accessor names. Yarn 1.21
		// names match mojmap for record components.
		String[] names = {"temperature", "humidity", "continentalness", "erosion", "depth", "weirdness"};
		long off = 8;
		for (String n : names) {
			Method getter = paramPoint.getClass().getMethod(n);
			Object param = getter.invoke(paramPoint);
			Method minM = param.getClass().getMethod("min");
			Method maxM = param.getClass().getMethod("max");
			long min = (Long) minM.invoke(param);
			long max = (Long) maxM.invoke(param);
			buf.putLong(base + (int) off, min);
			buf.putLong(base + (int) off + 8, max);
			off += 16;
		}
		// offset() — last component, also a long.
		Method offsetM = paramPoint.getClass().getMethod("offset");
		long offsetVal = (Long) offsetM.invoke(paramPoint);
		buf.putLong(base + (int) off, offsetVal);
	}

	/**
	 * Pull the identifier string out of a {@code Holder<Biome>} via
	 * the same chain used in {@code SurfaceValidator.fastReadBiomeName}.
	 */
	private static String resolveBiomeIdentifier(Object biomeHolder) {
		try {
			Method unwrapKey = biomeHolder.getClass().getMethod("getKey");
			Object opt = unwrapKey.invoke(biomeHolder);
			if (opt instanceof java.util.Optional<?> o && o.isPresent()) {
				Object regKey = o.get();
				Method getValue = regKey.getClass().getMethod("getValue");
				Object id = getValue.invoke(regKey);
				return id == null ? "unknown" : id.toString();
			}
		} catch (ReflectiveOperationException ignored) {
			// fall through
		}
		return "unknown";
	}

	private static boolean pushToRust(Identifier id, NoiseParamsView view) {
		String fullName = id.toString();
		byte[] nameBytes = fullName.getBytes(StandardCharsets.UTF_8);
		ByteBuffer nameBuf = ByteBuffer.allocateDirect(nameBytes.length).order(ByteOrder.nativeOrder());
		nameBuf.put(nameBytes);
		nameBuf.flip();

		double[] amps = view.amplitudes;
		ByteBuffer ampBuf = ByteBuffer.allocateDirect(amps.length * Double.BYTES).order(ByteOrder.nativeOrder());
		ampBuf.asDoubleBuffer().put(amps);

		return RustBridge.registerNoiseParameter(
				nameBuf, nameBytes.length, view.firstOctave, ampBuf, amps.length);
	}

	/** Snapshot of a NoiseParameters record's two components. */
	private static final class NoiseParamsView {
		final int firstOctave;
		final double[] amplitudes;
		NoiseParamsView(int firstOctave, double[] amplitudes) {
			this.firstOctave = firstOctave;
			this.amplitudes = amplitudes;
		}
	}

	/**
	 * Try a sequence of yarn accessors on {@code DynamicRegistryManager}
	 * to resolve the {@code NOISE_PARAMETERS} {@link Registry}. Names
	 * rotate across yarn versions ({@code get} / {@code getOrThrow} /
	 * {@code getOptional}); we try each in order.
	 */
	@SuppressWarnings("rawtypes")
	private static Registry resolveNoiseRegistry(net.minecraft.server.MinecraftServer server) {
		Object manager = server.getRegistryManager();
		Object key = RegistryKeys.NOISE_PARAMETERS;
		String[] candidates = {"getOrThrow", "get", "getRegistry"};
		for (String methodName : candidates) {
			try {
				Method method = manager.getClass().getMethod(methodName, RegistryKey.class);
				Object result = method.invoke(manager, key);
				if (result instanceof Registry<?> r) {
					return r;
				}
			} catch (ReflectiveOperationException ignored) {
				// try next candidate
			}
		}
		// Fall back: try Optional-returning accessor.
		try {
			Method method = manager.getClass().getMethod("getOptional", RegistryKey.class);
			Object result = method.invoke(manager, key);
			if (result instanceof java.util.Optional<?> opt && opt.isPresent()
					&& opt.get() instanceof Registry<?> r) {
				return r;
			}
		} catch (ReflectiveOperationException ignored) {
			// give up
		}
		return null;
	}

	/**
	 * Read {@code firstOctave} and {@code amplitudes} from a
	 * {@code DoublePerlinNoiseSampler.NoiseParameters} record via
	 * reflection. Returns null if either accessor is missing — caller
	 * logs and skips that entry.
	 */
	private static NoiseParamsView readNoiseParameters(Object params) {
		try {
			Method firstOctaveMethod = params.getClass().getMethod("firstOctave");
			Method amplitudesMethod = params.getClass().getMethod("amplitudes");
			int firstOctave = (Integer) firstOctaveMethod.invoke(params);
			Object ampList = amplitudesMethod.invoke(params);
			double[] amps = unpackAmplitudes(ampList);
			return new NoiseParamsView(firstOctave, amps);
		} catch (ReflectiveOperationException | RuntimeException e) {
			ExampleMod.LOGGER.warn(
					"[worldgen-init] could not read NoiseParameters record fields ({}): {}",
					e.getClass().getSimpleName(), e.getMessage());
			return null;
		}
	}

	/**
	 * Vanilla's {@code amplitudes} is a {@code DoubleList} (fastutil).
	 * We extract values via {@code size()} + {@code getDouble(int)} so
	 * we don't need a fastutil import in this file.
	 */
	private static double[] unpackAmplitudes(Object doubleList) throws ReflectiveOperationException {
		Method sizeMethod = doubleList.getClass().getMethod("size");
		Method getDoubleMethod = doubleList.getClass().getMethod("getDouble", int.class);
		int n = (Integer) sizeMethod.invoke(doubleList);
		double[] out = new double[n];
		for (int i = 0; i < n; i++) {
			out[i] = (Double) getDoubleMethod.invoke(doubleList, i);
		}
		return out;
	}
}
