package me.apika.apikaprobe.worldgen;

import me.apika.apikaprobe.bridge.ExampleMod;
import me.apika.apikaprobe.RustBridge;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import net.minecraft.core.Registry;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator;
import net.minecraft.world.level.levelgen.NoiseRouter;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.level.levelgen.densityfunction.DensityFunction;

// Rust vs vanilla density parity on 26.3: vanilla side is RandomState.sampleBlockValueUncached, compared by float bits.
public final class DensityParity {

	private DensityParity() {}

	// Sample every registered DF at random points; pass means every sample is bit-identical as float.
	public static String runAll(MinecraftServer server, int samples) {
		if (!RustBridge.NATIVE_AVAILABLE) {
			return "[df-parity] native unavailable";
		}
		if (RustBridge.densityFunctionCount() < 0) {
			return "[df-parity] Rust state not finalized";
		}
		List<String> names = WorldgenStateBootstrap.registeredDensityFunctionNames();
		if (names.isEmpty()) {
			return "[df-parity] no density functions registered";
		}
		RandomState state = overworldState(server);
		if (state == null) {
			return "[df-parity] no overworld RandomState";
		}
		Random rng = new Random(0xABCDEFABL);
		int totalPass = 0;
		int totalFail = 0;
		double worstDiff = 0.0;
		String worstName = "";
		int worstX = 0, worstY = 0, worstZ = 0;
		double worstRust = 0;
		float worstYarn = 0;
		List<String> failures = new ArrayList<>();

		for (String fullName : names) {
			DensityFunction vanilla = resolve(server, fullName);
			if (vanilla == null) {
				failures.add(fullName + " (no vanilla DF)");
				totalFail++;
				continue;
			}
			byte[] nameBytes = fullName.getBytes(StandardCharsets.UTF_8);
			ByteBuffer nameBuf = ByteBuffer.allocateDirect(nameBytes.length).order(ByteOrder.nativeOrder());
			nameBuf.put(nameBytes);
			nameBuf.flip();

			double maxDiffThis = 0.0;
			int bitMisses = 0;
			int compared = 0;
			for (int i = 0; i < samples; i++) {
				int x = rng.nextInt(20000) - 10000;
				int y = rng.nextInt(384) - 64;
				int z = rng.nextInt(20000) - 10000;
				double rust = RustBridge.sampleDensityFunction(nameBuf, nameBytes.length, x, y, z);
				if (Double.isNaN(rust)) continue;
				float yarn = state.sampleBlockValueUncached(vanilla, x, y, z);
				if (Float.isNaN(yarn)) continue;
				compared++;
				double diff = Math.abs(rust - yarn);
				if (diff > maxDiffThis) maxDiffThis = diff;
				if (diff > worstDiff) {
					worstDiff = diff; worstName = fullName;
					worstX = x; worstY = y; worstZ = z;
					worstRust = rust; worstYarn = yarn;
				}
				if (Float.floatToIntBits((float) rust) != Float.floatToIntBits(yarn)) bitMisses++;
			}
			if (compared > 0 && bitMisses == 0) {
				totalPass++;
			} else {
				totalFail++;
				failures.add(String.format("%s (maxDiff=%.6e, %d/%d bit misses)",
						fullName, maxDiffThis, bitMisses, compared));
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
		int shown = Math.min(failures.size(), 50);
		for (int i = 0; i < shown; i++) {
			ExampleMod.LOGGER.warn("[df-parity]   {}", failures.get(i));
		}
		if (failures.size() > shown) {
			ExampleMod.LOGGER.warn("[df-parity]   ... {} more", failures.size() - shown);
		}
		return String.format("[df-parity] %d DFs, %d pass, %d fail (float bits), worst=%.3e (%s)",
				names.size(), totalPass, totalFail, worstDiff,
				worstName.isEmpty() ? "-" : worstName);
	}

	// Vanilla's value at (x, y, z) as the float the sampler tree produces; null if the name resolves to nothing.
	public static Float sampleVanillaFloat(MinecraftServer server, String fullName, int x, int y, int z) {
		RandomState state = overworldState(server);
		DensityFunction df = resolve(server, fullName);
		if (state == null || df == null) return null;
		try {
			return state.sampleBlockValueUncached(df, x, y, z);
		} catch (RuntimeException e) {
			ExampleMod.LOGGER.warn("[df-parity] sampleBlockValueUncached failed for {}: {}", fullName, e.toString());
			return null;
		}
	}

	// Double view for callers that diff against the f64 Rust value.
	public static Double sampleVanilla(MinecraftServer server, String fullName, int x, int y, int z) {
		Float f = sampleVanillaFloat(server, fullName, x, y, z);
		return f == null ? null : (double) f;
	}

	// ferrite:* names are router roots; everything else is a DENSITY_FUNCTION registry entry.
	private static DensityFunction resolve(MinecraftServer server, String fullName) {
		if (fullName.startsWith("ferrite:")) {
			NoiseRouter router = overworldRouter(server);
			return router == null ? null : routerField(router, fullName);
		}
		Identifier id = Identifier.tryParse(fullName);
		if (id == null) return null;
		Registry<DensityFunction> registry = server.registryAccess().lookupOrThrow(Registries.DENSITY_FUNCTION);
		return registry.getOptional(id).orElse(null);
	}

	// Keep in sync with WorldgenStateBootstrap.registerResolvedRouterClimateDfs; aquifer and vein roots left the router in 26.3.
	private static DensityFunction routerField(NoiseRouter router, String fullName) {
		return switch (fullName) {
			case "ferrite:terrain/final_density" -> router.finalDensity();
			case "ferrite:terrain/chunk_surface_level",
				"ferrite:terrain/preliminary_surface_level" -> router.chunkSurfaceLevel();
			case "ferrite:climate/temperature" -> router.temperature();
			case "ferrite:climate/vegetation" -> router.vegetation();
			case "ferrite:climate/continents" -> router.continents();
			case "ferrite:climate/erosion" -> router.erosion();
			case "ferrite:climate/depth" -> router.depth();
			case "ferrite:climate/ridges" -> router.ridges();
			default -> null;
		};
	}

	private static RandomState overworldState(MinecraftServer server) {
		Object captured = WorldgenParity.findOverworldNoiseConfig();
		if (captured instanceof RandomState rs) return rs;
		ServerLevel overworld = server.overworld();
		return overworld == null ? null : overworld.getChunkSource().randomState();
	}

	private static NoiseRouter overworldRouter(MinecraftServer server) {
		ServerLevel overworld = server.overworld();
		if (overworld == null) return null;
		ChunkGenerator generator = overworld.getChunkSource().getGenerator();
		if (!(generator instanceof NoiseBasedChunkGenerator noise)) return null;
		return noise.generatorSettings().value().noiseRouter();
	}
}
