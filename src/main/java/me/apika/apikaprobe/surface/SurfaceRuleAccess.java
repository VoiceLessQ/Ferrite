package me.apika.apikaprobe.surface;

import net.minecraft.server.level.ServerLevel;

/**
 * Best-effort extractor for the active world's surface rule tree.
 *
 * The path is:
 *   ServerLevel → ChunkManager → ChunkGenerator → (NoiseBasedChunkGenerator)
 *     → settings (Holder&lt;ChunkGeneratorSettings&gt;)
 *     → value().surfaceRule()
 *
 * The last two hops use reflection because {@code surfaceRule()} is a
 * record accessor whose name we don't want to bake into a hard import
 * (yarn renames between MC versions are common). If extraction fails
 * for any reason — wrong generator type, unknown settings shape, null
 * — the result carries an {@code error} message the command surfaces
 * verbatim instead of throwing.
 */
public final class SurfaceRuleAccess {

	private SurfaceRuleAccess() {}

	public record Result(Object surfaceRule, String generatorClass, String error) {
		public boolean ok() { return error == null; }
		public static Result fail(String msg) { return new Result(null, null, msg); }
	}

	public static Result extract(ServerLevel world) {
		if (world == null) return Result.fail("world is null");
		Object generator;
		try {
			generator = world.getChunkSource().getChunkGenerator();
		} catch (RuntimeException e) {
			return Result.fail("getChunkGenerator threw: " + e.getMessage());
		}
		if (generator == null) return Result.fail("chunk generator is null");

		String genClass = generator.getClass().getName();

		// Walk up the class hierarchy looking for a NoiseBasedChunkGenerator
		// (or anything with a 'settings' field of registry-entry type).
		Object settingsValue = readSettingsValue(generator);
		if (settingsValue == null) {
			return Result.fail("active generator (" + genClass
				+ ") has no resolvable 'settings' field — surface rule extraction"
				+ " currently only supports NoiseBasedChunkGenerator-shaped generators");
		}

		Object rule = readSurfaceRule(settingsValue);
		if (rule == null) {
			return Result.fail("settings (" + settingsValue.getClass().getName()
				+ ") has no resolvable 'surfaceRule' accessor");
		}

		return new Result(rule, genClass, null);
	}

	private static Object readSettingsValue(Object generator) {
		// Look for a field named 'settings' anywhere in the class hierarchy.
		Class<?> c = generator.getClass();
		while (c != null && c != Object.class) {
			for (java.lang.reflect.Field f : c.getDeclaredFields()) {
				if (!f.getName().equals("settings")) continue;
				try {
					f.setAccessible(true);
					Object raw = f.get(generator);
					if (raw == null) return null;
					// Could be a Holder<ChunkGeneratorSettings>; unwrap via .value().
					return tryUnwrapRegistryEntry(raw);
				} catch (ReflectiveOperationException | RuntimeException e) {
					return null;
				}
			}
			c = c.getSuperclass();
		}
		return null;
	}

	private static Object tryUnwrapRegistryEntry(Object maybeEntry) {
		// If it has a no-arg method named "value", call it.
		try {
			java.lang.reflect.Method m = maybeEntry.getClass().getMethod("value");
			return m.invoke(maybeEntry);
		} catch (NoSuchMethodException e) {
			// Not wrapped — return as-is.
			return maybeEntry;
		} catch (ReflectiveOperationException | RuntimeException e) {
			return null;
		}
	}

	private static Object readSurfaceRule(Object settings) {
		// Try record accessor first.
		try {
			java.lang.reflect.Method m = settings.getClass().getMethod("surfaceRule");
			return m.invoke(settings);
		} catch (NoSuchMethodException ignored) {
			// fall through
		} catch (ReflectiveOperationException | RuntimeException e) {
			return null;
		}
		// Fall back to field walk.
		Class<?> c = settings.getClass();
		while (c != null && c != Object.class) {
			for (java.lang.reflect.Field f : c.getDeclaredFields()) {
				if (!f.getName().equals("surfaceRule")) continue;
				try {
					f.setAccessible(true);
					return f.get(settings);
				} catch (ReflectiveOperationException | RuntimeException e) {
					return null;
				}
			}
			c = c.getSuperclass();
		}
		return null;
	}
}
