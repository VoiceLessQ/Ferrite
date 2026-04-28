package me.apika.apikaprobe.worldgen;

import me.apika.apikaprobe.ExampleMod;
import me.apika.apikaprobe.RustBridge;

import java.lang.reflect.Method;
import java.util.List;
import java.util.Random;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Rust↔Java biome R-tree parity validator + unloaded biome lookup.
 *
 * <p>Two responsibilities:
 * <ol>
 *   <li>Validator: random-target Rust↔yarn cross-check (R-tree only).</li>
 *   <li>Unloaded lookup: given block (x, y, z), sample vanilla's
 *       {@code Climate.Sampler} (pure function — works for unloaded
 *       chunks) → produce TargetPoint → ask Rust for biome ID → map
 *       to identifier. Lets us answer "what's there?" before vanilla
 *       has generated the chunk.</li>
 * </ol>
 *
 * <p>The tree itself is bit-exact by construction (Rust mirrors
 * vanilla's {@code Climate.RTree.build} algorithm + comparators), but
 * "by construction" needs proof. Pattern: capture the live overworld
 * {@code MultiNoiseBiomeSource} (via {@link #captureBiomeSource}, set
 * by a Mixin on its constructor), generate N random {@code TargetPoint}s,
 * ask both sides which biome wins, log mismatches.
 *
 * <p>Pass criterion: zero mismatches at any quantized target. The
 * R-tree is a discrete classifier, so a single divergence means a real
 * algorithm bug — no IEEE float epsilon to negotiate.
 */
public final class BiomeParity {
	private static final AtomicReference<Object> lastBiomeSource = new AtomicReference<>();
	/** The {@code Climate.Sampler} for the live overworld (mojmap)
	 *  / yarn `MultiNoiseUtil.MultiNoiseSampler`. Captured at world
	 *  load, used by {@link #lookupBiomeAt} to sample climate at any
	 *  block position — works for unloaded chunks because the sampler
	 *  is a pure function of (quartX, quartY, quartZ). */
	private static final AtomicReference<Object> climateSampler = new AtomicReference<>();

	private BiomeParity() {}

	/** Called by {@code MultiNoiseBiomeSourceCaptureMixin} on construction. */
	public static void captureBiomeSource(Object biomeSource) {
		lastBiomeSource.set(biomeSource);
	}

	/** Called by {@code WorldgenStateBootstrap} after it resolves the
	 *  overworld's `Climate.Sampler` from the matching `NoiseConfig`. */
	public static void captureClimateSampler(Object sampler) {
		climateSampler.set(sampler);
	}

	/**
	 * Sample vanilla's Climate.Sampler at given block coords and return
	 * the 6 raw climate floats (temperature, humidity, continentalness,
	 * erosion, depth, weirdness). Returns null on any reflection failure.
	 *
	 * <p>Used by {@code /ferrite biome rust} to compare per-axis output
	 * against Rust's climate DF computations — narrows down which axis
	 * (if any) is diverging.
	 */
	public static double[] sampleVanillaClimate(int blockX, int blockY, int blockZ) {
		Object sampler = climateSampler.get();
		if (sampler == null) return null;
		try {
			Method sampleM = findSampleMethod(sampler.getClass());
			if (sampleM == null) return null;
			int qx = blockX >> 2;
			int qy = blockY >> 2;
			int qz = blockZ >> 2;
			Object targetPoint = sampleM.invoke(sampler, qx, qy, qz);
			long[] tp = readTargetPointLongs(targetPoint);
			if (tp == null) return null;
			// Quantization factor is 10000 — un-quantize back to float, return as double.
			double[] out = new double[6];
			for (int i = 0; i < 6; i++) {
				out[i] = tp[i] / 10000.0;
			}
			return out;
		} catch (ReflectiveOperationException | RuntimeException e) {
			return null;
		}
	}

	/**
	 * Look up the biome at block position {@code (blockX, blockY, blockZ)}
	 * without requiring the chunk to be loaded. Pipeline:
	 * <ol>
	 *   <li>Convert block coords to quart coords ({@code >> 2}).</li>
	 *   <li>Call vanilla's `Climate.Sampler.sample(qx, qy, qz)` — pure
	 *       function, doesn't touch chunk storage.</li>
	 *   <li>Extract the 6 quantized longs from the returned TargetPoint.</li>
	 *   <li>Pass to Rust's {@code queryBiomeAtTarget} → biome ID.</li>
	 *   <li>Map ID back to identifier via
	 *       {@link WorldgenStateBootstrap#registeredBiomeNames}.</li>
	 * </ol>
	 *
	 * <p>Returns null if the climate sampler isn't captured yet, or any
	 * reflective step fails.
	 */
	public static String lookupBiomeAt(int blockX, int blockY, int blockZ) {
		Object sampler = climateSampler.get();
		if (sampler == null) return null;
		List<String> names = WorldgenStateBootstrap.registeredBiomeNames();
		if (names.isEmpty()) return null;
		try {
			Method sampleM = findSampleMethod(sampler.getClass());
			if (sampleM == null) return null;
			int qx = blockX >> 2;
			int qy = blockY >> 2;
			int qz = blockZ >> 2;
			Object targetPoint = sampleM.invoke(sampler, qx, qy, qz);
			long[] tp = readTargetPointLongs(targetPoint);
			if (tp == null) return null;
			int rustId = RustBridge.queryBiomeAtTarget(tp[0], tp[1], tp[2], tp[3], tp[4], tp[5]);
			if (rustId < 0 || rustId >= names.size()) return null;
			return names.get(rustId);
		} catch (ReflectiveOperationException | RuntimeException e) {
			return null;
		}
	}

	/** Find {@code sample(int, int, int)} on a yarn `MultiNoiseSampler` /
	 *  mojmap `Climate.Sampler` (record). */
	private static Method findSampleMethod(Class<?> cls) {
		Class<?> c = cls;
		while (c != null && c != Object.class) {
			for (Method m : c.getDeclaredMethods()) {
				if (!m.getName().equals("sample")) continue;
				Class<?>[] params = m.getParameterTypes();
				if (params.length == 3 && params[0] == int.class
						&& params[1] == int.class && params[2] == int.class) {
					m.setAccessible(true);
					return m;
				}
			}
			c = c.getSuperclass();
		}
		return null;
	}

	/** Yarn renames Climate.TargetPoint → MultiNoiseUtil.NoiseValuePoint —
	 *  pull its 6 quantized record components via reflection. */
	private static long[] readTargetPointLongs(Object targetPoint) {
		if (targetPoint == null) return null;
		// Try yarn record-component names first, then mojmap.
		String[][] candidates = {
			{"temperatureNoise", "humidityNoise", "continentalnessNoise",
				"erosionNoise", "depth", "weirdnessNoise"},
			{"temperature", "humidity", "continentalness",
				"erosion", "depth", "weirdness"},
		};
		for (String[] names : candidates) {
			long[] out = tryReadLongs(targetPoint, names);
			if (out != null) return out;
		}
		return null;
	}

	/**
	 * Query vanilla's {@code ServerWorld.getBiome(BlockPos)} directly —
	 * no Rust, no reflection into the sampler, just whatever vanilla's
	 * own biome-access layer reports. Used for independent verification
	 * against {@link #lookupBiomeAt}; if the two disagree at any coord,
	 * something drifted.
	 *
	 * <p>Works for unloaded chunks too because vanilla's BiomeAccess
	 * falls back to the biome source (same path chunk gen uses) for
	 * positions whose chunk isn't loaded.
	 */
	public static String lookupActualBiomeAt(net.minecraft.server.world.ServerWorld world, int x, int y, int z) {
		net.minecraft.util.math.BlockPos pos = new net.minecraft.util.math.BlockPos(x, y, z);
		net.minecraft.registry.entry.RegistryEntry<net.minecraft.world.biome.Biome> entry = world.getBiome(pos);
		return entry.getKey()
				.map(k -> k.getValue().toString())
				.orElse("unknown");
	}

	private static long[] tryReadLongs(Object o, String[] accessorNames) {
		long[] out = new long[accessorNames.length];
		for (int i = 0; i < accessorNames.length; i++) {
			try {
				Method m = o.getClass().getMethod(accessorNames[i]);
				out[i] = (Long) m.invoke(o);
			} catch (ReflectiveOperationException | RuntimeException e) {
				return null;
			}
		}
		return out;
	}

	/**
	 * Run the parity check. Generates {@code sampleCount} random
	 * {@code TargetPoint}s in the quantized range vanilla uses (~±20000
	 * per axis), queries both yarn and Rust, returns a chat-ready summary.
	 */
	public static String runParityCheck(int sampleCount) {
		if (!RustBridge.NATIVE_AVAILABLE) {
			return "[biome-parity] native unavailable";
		}
		List<String> biomeNames = WorldgenStateBootstrap.registeredBiomeNames();
		if (biomeNames.isEmpty()) {
			return "[biome-parity] no biomes registered with Rust — load an overworld first";
		}
		Object biomeSource = lastBiomeSource.get();
		if (biomeSource == null) {
			return "[biome-parity] vanilla MultiNoiseBiomeSource not captured";
		}

		Method getNoiseBiome = findGetNoiseBiomeFromTarget(biomeSource);
		if (getNoiseBiome == null) {
			return "[biome-parity] could not resolve MultiNoiseBiomeSource.getNoiseBiome(TargetPoint)";
		}

		Random rng = new Random(0xBEEFCAFEL);
		int pass = 0;
		int fail = 0;
		int firstFailIdx = -1;
		long firstFailT = 0, firstFailH = 0, firstFailC = 0;
		long firstFailE = 0, firstFailD = 0, firstFailW = 0;
		String firstFailRust = "";
		String firstFailYarn = "";

		// Vanilla quantizes climate floats by ×10000, and the climate
		// noises typically stay within ±2.0, so quantized values stay
		// within ±20000. Sample uniformly in that range.
		final long range = 20000L;

		for (int i = 0; i < sampleCount; i++) {
			long t = (long) ((rng.nextDouble() * 2.0 - 1.0) * range);
			long h = (long) ((rng.nextDouble() * 2.0 - 1.0) * range);
			long c = (long) ((rng.nextDouble() * 2.0 - 1.0) * range);
			long e = (long) ((rng.nextDouble() * 2.0 - 1.0) * range);
			long d = (long) ((rng.nextDouble() * 2.0 - 1.0) * range);
			long w = (long) ((rng.nextDouble() * 2.0 - 1.0) * range);

			int rustId = RustBridge.queryBiomeAtTarget(t, h, c, e, d, w);
			String rustName;
			if (rustId < 0 || rustId >= biomeNames.size()) {
				rustName = "<rust:invalid id=" + rustId + ">";
			} else {
				rustName = biomeNames.get(rustId);
			}

			String yarnName;
			try {
				Object targetPoint = newTargetPoint(t, h, c, e, d, w);
				if (targetPoint == null) {
					yarnName = "<yarn:no targetpoint>";
				} else {
					Object biomeHolder = getNoiseBiome.invoke(biomeSource, targetPoint);
					yarnName = resolveBiomeIdentifier(biomeHolder);
				}
			} catch (ReflectiveOperationException ex) {
				yarnName = "<yarn:exception>";
			}

			if (rustName.equals(yarnName)) {
				pass++;
			} else {
				fail++;
				if (firstFailIdx < 0) {
					firstFailIdx = i;
					firstFailT = t; firstFailH = h; firstFailC = c;
					firstFailE = e; firstFailD = d; firstFailW = w;
					firstFailRust = rustName;
					firstFailYarn = yarnName;
				}
			}
		}

		String summary;
		if (fail == 0) {
			summary = String.format("[biome-parity] %d samples, %d pass, 0 fail — bit-exact",
					sampleCount, pass);
		} else {
			summary = String.format(
					"[biome-parity] %d samples, %d pass, %d fail — first miss @(%d,%d,%d,%d,%d,%d) rust=%s yarn=%s",
					sampleCount, pass, fail,
					firstFailT, firstFailH, firstFailC, firstFailE, firstFailD, firstFailW,
					firstFailRust, firstFailYarn);
		}
		ExampleMod.LOGGER.info(summary);
		return summary;
	}

	/**
	 * Find {@code MultiNoiseBiomeSource.getNoiseBiome(Climate.TargetPoint)} —
	 * the no-Sampler variant that takes a pre-sampled target point. The
	 * other overload takes (quartX, quartY, quartZ, sampler) and would
	 * require us to also have a Climate.Sampler.
	 */
	private static Method findGetNoiseBiomeFromTarget(Object biomeSource) {
		Class<?> targetPointCls;
		try {
			targetPointCls = Class.forName("net.minecraft.world.biome.source.util.MultiNoiseUtil$NoiseValuePoint");
		} catch (ClassNotFoundException e) {
			return scanOneArg(biomeSource, null);
		}
		// Scan for any method that takes exactly one NoiseValuePoint —
		// yarn likely calls it `getBiome` but we don't have to assume.
		Method m = scanOneArg(biomeSource, targetPointCls);
		if (m != null) return m;
		// Fall back to any 1-arg method with "biome" in its name.
		return scanOneArg(biomeSource, null);
	}

	private static Method scanOneArg(Object biomeSource, Class<?> requiredParamType) {
		Class<?> c = biomeSource.getClass();
		while (c != null && c != Object.class) {
			for (Method m : c.getDeclaredMethods()) {
				if (m.getParameterCount() != 1) continue;
				if (requiredParamType != null) {
					if (!m.getParameterTypes()[0].equals(requiredParamType)) continue;
				} else {
					String n = m.getName().toLowerCase();
					if (!n.contains("biome") || !n.contains("noise")) continue;
				}
				m.setAccessible(true);
				return m;
			}
			c = c.getSuperclass();
		}
		return null;
	}

	/**
	 * Construct a yarn {@code MultiNoiseUtil.NoiseValuePoint} (vanilla
	 * mojmap {@code Climate.TargetPoint}) from the 6 quantized longs.
	 * Reflective because yarn's class name + ctor differ from mojmap's.
	 */
	private static Object newTargetPoint(long t, long h, long c, long e, long d, long w) {
		// Yarn record class: MultiNoiseUtil$NoiseValuePoint(long t, long h, long c, long e, long d, long w)
		try {
			Class<?> cls = Class.forName("net.minecraft.world.biome.source.util.MultiNoiseUtil$NoiseValuePoint");
			java.lang.reflect.Constructor<?> ctor = cls.getDeclaredConstructor(
					long.class, long.class, long.class, long.class, long.class, long.class);
			ctor.setAccessible(true);
			return ctor.newInstance(t, h, c, e, d, w);
		} catch (ReflectiveOperationException | RuntimeException ex) {
			return null;
		}
	}

	/** Same chain as the noise validator. */
	private static String resolveBiomeIdentifier(Object biomeHolder) {
		if (biomeHolder == null) return "null";
		try {
			Method getKey = biomeHolder.getClass().getMethod("getKey");
			Object opt = getKey.invoke(biomeHolder);
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
}
