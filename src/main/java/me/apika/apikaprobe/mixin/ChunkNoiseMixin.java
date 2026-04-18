package me.apika.apikaprobe.mixin;

import java.util.concurrent.CompletableFuture;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import me.apika.apikaprobe.worldgen.ErosionPass;

import net.minecraft.world.chunk.Chunk;
import net.minecraft.world.gen.StructureAccessor;
import net.minecraft.world.gen.chunk.Blender;
import net.minecraft.world.gen.chunk.NoiseChunkGenerator;
import net.minecraft.world.gen.noise.NoiseConfig;

@Mixin(NoiseChunkGenerator.class)
public abstract class ChunkNoiseMixin {

	@Inject(
		method = "populateNoise",
		at = @At("RETURN")
	)
	private void apikaprobe$onPopulateNoiseDone(
			Blender blender,
			NoiseConfig noiseConfig,
			StructureAccessor structureAccessor,
			Chunk chunk,
			CallbackInfoReturnable<CompletableFuture<Chunk>> cir) {
		long seed = chunk.getPos().toLong();
		ErosionPass.apply(chunk, seed);
	}
}
