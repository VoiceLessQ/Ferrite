package me.apika.apikaprobe.worldgen;

import me.apika.apikaprobe.bridge.ExampleMod;
import me.apika.apikaprobe.RustBridge;

import java.lang.reflect.Method;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Rust↔Java noise parity validator.
 *
 * <p>{@link me.apika.apikaprobe.mixin.NoiseConfigCaptureMixin} stashes
 * the live yarn {@code RandomState} into {@link #lastNoiseConfig} as a
 * world loads. {@link #runParityCheck} iterates the names Rust has
 * registered (via {@link WorldgenStateBootstrap#registeredNames}),
 * samples both sides at N test positions, and logs the max absolute
 * diff plus the worst offender.
 *
 * <p>Bit-exact parity is the target: the Rust port (Xoroshiro →
 * ImprovedNoise → PerlinNoise → NormalNoise) is a direct translation
 * of vanilla, so a mismatch means a real bug somewhere in the chain.
 * IEEE round-off at the last ULP is tolerated via {@link #PARITY_EPSILON}.
 *
 * <p>All yarn-side access is reflective so this file doesn't pin to one
 * yarn signature; perf doesn't matter since the validator runs once on
 * demand.
 */
public final class WorldgenParity {
	/** Every RandomState constructed this session. We pick the one
	 *  whose root factory seeds match Rust's — that's the overworld.
	 *  Copy-on-write because the mixin fires from the server-init thread
	 *  and the validator reads from the command thread. */
	private static final List<Object> allNoiseConfigs = new CopyOnWriteArrayList<>();
	/** IEEE round-off at the last ULP is tolerated. */
	private static final double PARITY_EPSILON = 1.0e-10;

	private WorldgenParity() {}

	/** Called from {@link me.apika.apikaprobe.mixin.NoiseConfigCaptureMixin}. */
	public static void captureNoiseConfig(Object noiseConfig) {
		allNoiseConfigs.add(noiseConfig);
	}

	/**
	 * Walk the captured RandomState list and return the one whose root
	 * positional-factory seeds match Rust's overworld state. Used by
	 * {@link WorldgenStateBootstrap#captureOverworldClimateSampler} so
	 * the sampler comes from the right dimension (overworld), not from
	 * whichever RandomState the mixin happened to see last.
	 */
	public static Object findOverworldNoiseConfig() {
		long rustLo = RustBridge.worldgenRootSeedLo();
		long rustHi = RustBridge.worldgenRootSeedHi();
		for (Object nc : allNoiseConfigs) {
			long[] seeds = readYarnRootFactorySeeds(nc);
			if (seeds != null && seeds[0] == rustLo && seeds[1] == rustHi) {
				return nc;
			}
		}
		return null;
	}

	/**
	 * Variant that takes raw expected (lo, hi) pair. Used by
	 * {@link WorldgenStateBootstrap} when we need to find the
	 * RandomState BEFORE finalize (Rust's worldgenRootSeed* return 0
	 * pre-finalize, so we have to compute the expected seeds Java-side).
	 */
	public static Object findNoiseConfigBySeeds(long lo, long hi) {
		for (Object nc : allNoiseConfigs) {
			long[] seeds = readYarnRootFactorySeeds(nc);
			if (seeds != null && seeds[0] == lo && seeds[1] == hi) {
				return nc;
			}
		}
		return null;
	}

	/**
	 * Run the parity check. Samples every registered noise at
	 * {@code sampleCount} random (x, y, z) positions within a
	 * {@code ±boxRadius} cube, compares to yarn, and returns a one-line
	 * chat-ready summary.
	 */
	public static String runParityCheck(int sampleCount, int boxRadius) {
		if (!RustBridge.NATIVE_AVAILABLE) {
			return "[parity] native unavailable";
		}
		if (RustBridge.worldgenNoiseCount() < 0) {
			return "[parity] Rust worldgen state not finalized — load an overworld first";
		}
		if (allNoiseConfigs.isEmpty()) {
			return "[parity] RandomState not captured — load an overworld first";
		}
		// Rust's root factory seeds are the ground truth — the overworld
		// RandomState is the one whose random factory matches.
		long rustLo0 = RustBridge.worldgenRootSeedLo();
		long rustHi0 = RustBridge.worldgenRootSeedHi();
		Object noiseConfig = null;
		StringBuilder triedList = new StringBuilder();
		for (Object nc : allNoiseConfigs) {
			long[] seeds = readYarnRootFactorySeeds(nc);
			if (seeds == null) {
				triedList.append("[unreadable] ");
				continue;
			}
			triedList.append('(').append(seeds[0]).append(',').append(seeds[1]).append(") ");
			if (seeds[0] == rustLo0 && seeds[1] == rustHi0) {
				noiseConfig = nc;
				break;
			}
		}
		if (noiseConfig == null) {
			ExampleMod.LOGGER.warn("[parity] no RandomState matched Rust root seeds ({},{}); tried: {}",
					rustLo0, rustHi0, triedList);
			return "[parity] could not find matching RandomState (see log)";
		}
		List<String> names = WorldgenStateBootstrap.registeredNames();
		if (names.isEmpty()) {
			return "[parity] no noise names recorded — bootstrap did not run";
		}

		YarnSampler yarn = YarnSampler.resolve(noiseConfig);
		if (yarn == null) {
			return "[parity] could not resolve yarn sampler API";
		}

		// Diagnostic: log root-factory seeds both sides so we can see
		// immediately if the divergence is at the root (seed mismatch)
		// vs per-noise (hash path mismatch).
		long rustLo = RustBridge.worldgenRootSeedLo();
		long rustHi = RustBridge.worldgenRootSeedHi();
		long[] yarnSeeds = readYarnRootFactorySeeds(noiseConfig);
		if (yarnSeeds != null) {
			ExampleMod.LOGGER.info(
					"[parity] root factory — rust=({},{}) yarn=({},{}) matches={}",
					rustLo, rustHi, yarnSeeds[0], yarnSeeds[1],
					(rustLo == yarnSeeds[0]) && (rustHi == yarnSeeds[1]));
		} else {
			ExampleMod.LOGGER.info("[parity] root factory — rust=({},{}) yarn=<reflection failed>",
					rustLo, rustHi);
		}

		Random rng = new Random(0xDECAFBADL);
		int pass = 0;
		int fail = 0;
		double worstDiff = 0.0;
		String worstName = "";
		double worstX = 0, worstY = 0, worstZ = 0, worstRust = 0, worstYarn = 0;
		List<String> failures = new ArrayList<>();

		for (String fullName : names) {
			Object sampler = yarn.getSampler(fullName);
			if (sampler == null) {
				failures.add(fullName + " (no yarn sampler)");
				fail++;
				continue;
			}
			byte[] nameBytes = fullName.getBytes(StandardCharsets.UTF_8);
			ByteBuffer nameBuf = ByteBuffer.allocateDirect(nameBytes.length).order(ByteOrder.nativeOrder());
			nameBuf.put(nameBytes);
			nameBuf.flip();

			double maxDiffThis = 0.0;
			double dRust = 0, dYarn = 0, dx = 0, dy = 0, dz = 0;
			for (int i = 0; i < sampleCount; i++) {
				double x = (rng.nextDouble() * 2.0 - 1.0) * boxRadius;
				double y = (rng.nextDouble() * 2.0 - 1.0) * boxRadius;
				double z = (rng.nextDouble() * 2.0 - 1.0) * boxRadius;
				double rustValue = RustBridge.sampleWorldgenNoise(nameBuf, nameBytes.length, x, y, z);
				if (Double.isNaN(rustValue)) {
					continue;
				}
				double yarnValue = yarn.sample(sampler, x, y, z);
				if (Double.isNaN(yarnValue)) {
					continue;
				}
				double diff = Math.abs(rustValue - yarnValue);
				if (diff > maxDiffThis) {
					maxDiffThis = diff;
					dRust = rustValue; dYarn = yarnValue;
					dx = x; dy = y; dz = z;
				}
			}
			if (maxDiffThis <= PARITY_EPSILON) {
				pass++;
			} else {
				fail++;
				failures.add(String.format("%s (maxDiff=%.6e, rust=%.6f yarn=%.6f @(%.1f,%.1f,%.1f))",
						fullName, maxDiffThis, dRust, dYarn, dx, dy, dz));
			}
			if (maxDiffThis > worstDiff) {
				worstDiff = maxDiffThis;
				worstName = fullName;
				worstRust = dRust; worstYarn = dYarn;
				worstX = dx; worstY = dy; worstZ = dz;
			}
		}

		ExampleMod.LOGGER.info(String.format(
				"[parity] samples=%d noises=%d pass=%d fail=%d worst=%s @(%.1f,%.1f,%.1f) rust=%.6f yarn=%.6f diff=%.6e",
				sampleCount, names.size(), pass, fail,
				worstName.isEmpty() ? "-" : worstName,
				worstX, worstY, worstZ, worstRust, worstYarn, worstDiff));
		int shown = Math.min(failures.size(), 10);
		for (int i = 0; i < shown; i++) {
			ExampleMod.LOGGER.warn("[parity]   fail: {}", failures.get(i));
		}
		if (failures.size() > shown) {
			ExampleMod.LOGGER.warn("[parity]   ... {} more failures", failures.size() - shown);
		}
		return String.format("[parity] %d noises, %d pass, %d fail, worst=%.3e (%s)",
				names.size(), pass, fail, worstDiff, worstName.isEmpty() ? "-" : worstName);
	}

	/**
	 * Walk yarn's {@code RandomState.random} (a
	 * {@code XoroshiroPositionalRandomFactory}) and return its
	 * {@code seedLo} / {@code seedHi} via reflection.
	 * Returns null if any field/type isn't found.
	 */
	private static long[] readYarnRootFactorySeeds(Object noiseConfig) {
		// Yarn renames: PositionalRandomFactory → RandomDeriver / PositionalRandomFactory
		// Also try literal field names commonly used in RandomState.
		String[] typeHints = {
			"PositionalRandomFactory", "RandomDeriver",
			"PositionalRandomFactory", "RandomFactory"
		};
		Object factory = null;
		for (String hint : typeHints) {
			factory = findFieldByType(noiseConfig, hint);
			if (factory != null) {
				ExampleMod.LOGGER.info("[parity] yarn random factory field type match: {} ({})",
						hint, factory.getClass().getName());
				break;
			}
		}
		if (factory == null) {
			// Last resort: dump all RandomState field types so we can see the
			// correct one next run.
			StringBuilder sb = new StringBuilder("[parity] RandomState fields: ");
			Class<?> cls = noiseConfig.getClass();
			while (cls != null && cls != Object.class) {
				for (java.lang.reflect.Field f : cls.getDeclaredFields()) {
					sb.append(f.getName()).append(':').append(f.getType().getSimpleName()).append(", ");
				}
				cls = cls.getSuperclass();
			}
			ExampleMod.LOGGER.info(sb.toString());
			return null;
		}
		Long lo = readLongField(factory, new String[]{"seedLo", "field_38400", "seedLow", "seed_lo"});
		Long hi = readLongField(factory, new String[]{"seedHi", "field_38401", "seedHigh", "seed_hi"});
		if (lo == null || hi == null) {
			// Dump all long fields on the factory for the next run.
			StringBuilder sb = new StringBuilder("[parity] factory long fields: ");
			Class<?> cls = factory.getClass();
			while (cls != null && cls != Object.class) {
				for (java.lang.reflect.Field f : cls.getDeclaredFields()) {
					if (f.getType() == long.class) {
						sb.append(f.getName()).append(", ");
					}
				}
				cls = cls.getSuperclass();
			}
			ExampleMod.LOGGER.info(sb.toString());
			return null;
		}
		return new long[]{lo, hi};
	}

	private static Object findFieldByType(Object target, String typeSubstring) {
		Class<?> cls = target.getClass();
		while (cls != null && cls != Object.class) {
			for (java.lang.reflect.Field f : cls.getDeclaredFields()) {
				if (f.getType().getName().contains(typeSubstring)
						|| f.getType().getSimpleName().contains(typeSubstring)) {
					try {
						f.setAccessible(true);
						return f.get(target);
					} catch (ReflectiveOperationException ignored) {
						// try next
					}
				}
			}
			cls = cls.getSuperclass();
		}
		return null;
	}

	private static Long readLongField(Object target, String[] names) {
		Class<?> cls = target.getClass();
		while (cls != null && cls != Object.class) {
			for (String n : names) {
				try {
					java.lang.reflect.Field f = cls.getDeclaredField(n);
					f.setAccessible(true);
					return f.getLong(target);
				} catch (ReflectiveOperationException ignored) {
					// try next
				}
			}
			cls = cls.getSuperclass();
		}
		return null;
	}

	/**
	 * Reflection shim around {@code RandomState.getOrCreateSampler} and
	 * the returned sampler's {@code sample(double, double, double)}.
	 */
	private static final class YarnSampler {
		private final Object noiseConfig;
		private final Method identifierOf;
		private final Method registryKeyOf;
		private final Object noiseParamsRegistryKey;
		private final Method getOrCreateSampler;
		private final Method sampleMethod;

		private YarnSampler(Object noiseConfig, Method identifierOf, Method registryKeyOf,
				Object noiseParamsRegistryKey, Method getOrCreateSampler, Method sampleMethod) {
			this.noiseConfig = noiseConfig;
			this.identifierOf = identifierOf;
			this.registryKeyOf = registryKeyOf;
			this.noiseParamsRegistryKey = noiseParamsRegistryKey;
			this.getOrCreateSampler = getOrCreateSampler;
			this.sampleMethod = sampleMethod;
		}

		static YarnSampler resolve(Object noiseConfig) {
			try {
				Class<?> identifierClass = Class.forName("net.minecraft.resources.Identifier");
				Class<?> registryKeyClass = Class.forName("net.minecraft.resources.ResourceKey");
				Class<?> registryKeysClass = Class.forName("net.minecraft.core.registries.Registries");
				Method identifierOf = identifierClass.getMethod("of", String.class);
				Method registryKeyOf = registryKeyClass.getMethod("of", registryKeyClass, identifierClass);
				Object noiseParamsRegistryKey = registryKeysClass.getField("NOISE_PARAMETERS").get(null);
				Method getOrCreateSampler = noiseConfig.getClass().getMethod("getOrCreateSampler", registryKeyClass);
				// Probe with minecraft:temperature to discover the sample method.
				Object probeId = identifierOf.invoke(null, "minecraft:temperature");
				Object probeKey = registryKeyOf.invoke(null, noiseParamsRegistryKey, probeId);
				Object probeSampler = getOrCreateSampler.invoke(noiseConfig, probeKey);
				if (probeSampler == null) {
					ExampleMod.LOGGER.warn("[parity] probe sampler (minecraft:temperature) resolved to null");
					return null;
				}
				Method sampleMethod = probeSampler.getClass().getMethod(
						"sample", double.class, double.class, double.class);
				return new YarnSampler(noiseConfig, identifierOf, registryKeyOf,
						noiseParamsRegistryKey, getOrCreateSampler, sampleMethod);
			} catch (ReflectiveOperationException | RuntimeException e) {
				ExampleMod.LOGGER.warn("[parity] resolve failed: {}", e.getMessage());
				return null;
			}
		}

		Object getSampler(String fullName) {
			try {
				Object id = identifierOf.invoke(null, fullName);
				Object key = registryKeyOf.invoke(null, noiseParamsRegistryKey, id);
				return getOrCreateSampler.invoke(noiseConfig, key);
			} catch (ReflectiveOperationException | RuntimeException e) {
				return null;
			}
		}

		double sample(Object sampler, double x, double y, double z) {
			try {
				Object result = sampleMethod.invoke(sampler, x, y, z);
				return result instanceof Double d ? d : Double.NaN;
			} catch (ReflectiveOperationException e) {
				return Double.NaN;
			}
		}
	}
}
