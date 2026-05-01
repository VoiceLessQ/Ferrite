package me.apika.apikaprobe.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import me.apika.apikaprobe.worldgen.TerrainBulkHandoff;

import net.minecraft.world.level.chunk.Chunk;
import net.minecraft.world.level.levelgen.StructureManager;
import net.minecraft.world.level.levelgen.Blender;
import net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator;
import net.minecraft.world.level.levelgen.RandomState;

/**
 * A/B measurement hook. Targets the private sync populateNoise overload
 * (same method we instrumented in ChunkPhaseMixin). At HEAD: compute the
 * Rust bulk-handoff result, time it, throw it away. Vanilla continues
 * unmodified after the inject returns.
 *
 * Fully qualified descriptor disambiguates from the public async overload:
 *   populateNoise(Blender, StructureManager, RandomState, Chunk, int, int) -> Chunk
 */
@Mixin(NoiseBasedChunkGenerator.class)
public abstract class TerrainBulkHandoffMixin {

	@Inject(
		method = "populateNoise(Lnet.minecraft.world.level.levelgen.Blender;Lnet.minecraft.world.level.levelgen.StructureManager;Lnet.minecraft.world.level.levelgen.RandomState;Lnet.minecraft.world.level.chunk.Chunk;II)Lnet.minecraft.world.level.chunk.Chunk;",
		at = @At("HEAD")
	)
	private void apikaprobe$bulkTerrainAB(
			Blender blender,
			StructureManager structureAccessor,
			RandomState noiseConfig,
			Chunk chunk,
			int minimumCellY,
			int cellHeight,
			CallbackInfoReturnable<Chunk> cir) {
		TerrainBulkHandoff.apply(chunk, noiseConfig);
		// Do not touch cir — vanilla runs normally after this returns.
	}
}
