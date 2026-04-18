package me.apika.apikaprobe.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import me.apika.apikaprobe.TerrainBulkHandoff;

import net.minecraft.world.chunk.Chunk;
import net.minecraft.world.gen.StructureAccessor;
import net.minecraft.world.gen.chunk.Blender;
import net.minecraft.world.gen.chunk.NoiseChunkGenerator;
import net.minecraft.world.gen.noise.NoiseConfig;

/**
 * A/B measurement hook. Targets the private sync populateNoise overload
 * (same method we instrumented in ChunkPhaseMixin). At HEAD: compute the
 * Rust bulk-handoff result, time it, throw it away. Vanilla continues
 * unmodified after the inject returns.
 *
 * Fully qualified descriptor disambiguates from the public async overload:
 *   populateNoise(Blender, StructureAccessor, NoiseConfig, Chunk, int, int) -> Chunk
 */
@Mixin(NoiseChunkGenerator.class)
public abstract class TerrainBulkHandoffMixin {

	@Inject(
		method = "populateNoise(Lnet/minecraft/world/gen/chunk/Blender;Lnet/minecraft/world/gen/StructureAccessor;Lnet/minecraft/world/gen/noise/NoiseConfig;Lnet/minecraft/world/chunk/Chunk;II)Lnet/minecraft/world/chunk/Chunk;",
		at = @At("HEAD")
	)
	private void apikaprobe$bulkTerrainAB(
			Blender blender,
			StructureAccessor structureAccessor,
			NoiseConfig noiseConfig,
			Chunk chunk,
			int minimumCellY,
			int cellHeight,
			CallbackInfoReturnable<Chunk> cir) {
		TerrainBulkHandoff.apply(chunk, noiseConfig);
		// Do not touch cir — vanilla runs normally after this returns.
	}
}
