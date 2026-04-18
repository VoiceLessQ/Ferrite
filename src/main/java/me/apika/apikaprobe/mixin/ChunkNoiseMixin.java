package me.apika.apikaprobe.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import me.apika.apikaprobe.worldgen.ErosionPass;
import me.apika.apikaprobe.worldgen.FeatureInjector;

import net.minecraft.world.ChunkRegion;
import net.minecraft.world.chunk.Chunk;
import net.minecraft.world.gen.StructureAccessor;
import net.minecraft.world.gen.chunk.NoiseChunkGenerator;
import net.minecraft.world.gen.noise.NoiseConfig;

@Mixin(NoiseChunkGenerator.class)
public abstract class ChunkNoiseMixin {

	@Inject(
		method = "buildSurface",
		at = @At("HEAD")
	)
	private void apikaprobe$onBuildSurfaceHead(
			ChunkRegion region,
			StructureAccessor structures,
			NoiseConfig noiseConfig,
			Chunk chunk,
			CallbackInfo ci) {
		long seed = chunk.getPos().toLong();
		// ErosionPass.apply(region, chunk, seed); // disabled: per-chunk seams unfixable
	}

	@Inject(
		method = "buildSurface",
		at = @At("RETURN")
	)
	private void apikaprobe$onBuildSurfaceDone(
			ChunkRegion region,
			StructureAccessor structures,
			NoiseConfig noiseConfig,
			Chunk chunk,
			CallbackInfo ci) {
		long seed = chunk.getPos().toLong();
		FeatureInjector.apply(chunk, seed);
	}
}
