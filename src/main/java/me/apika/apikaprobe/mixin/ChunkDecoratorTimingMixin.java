package me.apika.apikaprobe.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.StructureManager;
import net.minecraft.world.level.chunk.ChunkGenerator;

import me.apika.apikaprobe.worldgen.chunk.ChunkDecoratorTiming;

/**
 * Times the FEATURES (decoration) phase per chunk and routes the result
 * to {@link ChunkDecoratorTiming} bucketed by the chunk's center biome.
 *
 * <p>Yarn `generateFeatures` corresponds to mojmap `applyBiomeDecoration`.
 * This is the phase where vanilla iterates the 11
 * {@code GenerationStep.Feature} categories per chunk, calling each
 * placed feature's `placeWithBiomeCheck` once per applicable biome.
 *
 * <p>Pure measurement — no behavior change. The data tells us which
 * biomes' decorator phase is cheap (skippable candidates) vs expensive.
 */
@Mixin(ChunkGenerator.class)
public abstract class ChunkDecoratorTimingMixin {
	@Inject(method = "generateFeatures", at = @At("HEAD"))
	private void ferrite$startTiming(WorldGenLevel world, ChunkAccess chunk,
			StructureManager structureAccessor, CallbackInfo ci) {
		ChunkDecoratorTiming.start();
	}

	@Inject(method = "generateFeatures", at = @At("RETURN"))
	private void ferrite$endTiming(WorldGenLevel world, ChunkAccess chunk,
			StructureManager structureAccessor, CallbackInfo ci) {
		// Center of chunk in block coords. ChunkPos.x/z are chunk coords;
		// (cx << 4) + 8 is the center block.
		int cx = (chunk.getPos().x() << 4) + 8;
		int cz = (chunk.getPos().z() << 4) + 8;
		ChunkDecoratorTiming.end(cx, cz);
	}
}
