package me.apika.apikaprobe.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import me.apika.apikaprobe.worldgen.TerrainBulkHandoff;

import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.StructureManager;
import net.minecraft.world.level.levelgen.blending.Blender;
import net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator;
import net.minecraft.world.level.levelgen.RandomState;

/**
 * A/B measurement hook. Targets the private sync populateNoise overload
 * (same method we instrumented in ChunkPhaseMixin). At HEAD: compute the
 * Rust bulk-handoff result, time it, throw it away. Vanilla continues
 * unmodified after the inject returns.
 *
 * Fully qualified descriptor disambiguates from the public async overload:
 *   populateNoise(Blender, StructureManager, RandomState, ChunkAccess, int, int) -> ChunkAccess
 */
@Mixin(NoiseBasedChunkGenerator.class)
public abstract class TerrainBulkHandoffMixin {

	@Inject(
		method = "populateNoise(Lnet/minecraft/world/level/levelgen/blending/Blender;Lnet/minecraft/world/level/StructureManager;Lnet/minecraft/world/level/levelgen/RandomState;Lnet/minecraft/world/level/chunk/ChunkAccess;II)Lnet/minecraft/world/level/chunk/ChunkAccess;",
		at = @At("HEAD")
	)
	private void apikaprobe$bulkTerrainAB(
			Blender blender,
			StructureManager structureAccessor,
			RandomState noiseConfig,
			ChunkAccess chunk,
			int minimumCellY,
			int cellHeight,
			CallbackInfoReturnable<ChunkAccess> cir) {
		TerrainBulkHandoff.apply(chunk, noiseConfig);
		// Do not touch cir — vanilla runs normally after this returns.
	}
}
