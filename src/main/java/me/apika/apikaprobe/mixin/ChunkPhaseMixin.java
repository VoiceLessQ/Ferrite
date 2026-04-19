package me.apika.apikaprobe.mixin;

import java.util.concurrent.CompletableFuture;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import org.slf4j.LoggerFactory;

import me.apika.apikaprobe.ChunkGenMonitor;

import net.minecraft.world.ChunkRegion;
import net.minecraft.world.chunk.Chunk;
import net.minecraft.world.gen.StructureAccessor;
import net.minecraft.world.gen.chunk.Blender;
import net.minecraft.world.gen.chunk.NoiseChunkGenerator;
import net.minecraft.world.gen.noise.NoiseConfig;

@Mixin(NoiseChunkGenerator.class)
public abstract class ChunkPhaseMixin {

	static {
		LoggerFactory.getLogger("rusty").info("[chunkgen] ChunkPhaseMixin loaded");
	}

	@Inject(method = "populateNoise", at = @At("HEAD"))
	private void apikaprobe$onNoiseStart(
			Blender blender,
			NoiseConfig noiseConfig,
			StructureAccessor structureAccessor,
			Chunk chunk,
			CallbackInfoReturnable<CompletableFuture<Chunk>> cir) {
		ChunkGenMonitor.onNoiseStart();
	}

	@Inject(method = "populateNoise", at = @At("RETURN"))
	private void apikaprobe$onNoiseEnd(
			Blender blender,
			NoiseConfig noiseConfig,
			StructureAccessor structureAccessor,
			Chunk chunk,
			CallbackInfoReturnable<CompletableFuture<Chunk>> cir) {
		ChunkGenMonitor.onNoiseEnd();
	}

	@Inject(method = "buildSurface", at = @At("HEAD"))
	private void apikaprobe$onSurfaceStart(
			ChunkRegion region,
			StructureAccessor structures,
			NoiseConfig noiseConfig,
			Chunk chunk,
			CallbackInfo ci) {
		ChunkGenMonitor.onSurfaceStart();
	}

	@Inject(method = "buildSurface", at = @At("RETURN"))
	private void apikaprobe$onSurfaceEnd(
			ChunkRegion region,
			StructureAccessor structures,
			NoiseConfig noiseConfig,
			Chunk chunk,
			CallbackInfo ci) {
		ChunkGenMonitor.onSurfaceEnd();
	}

	// --- private sync populateNoise (the real noise work) -------------------
	// Fully qualified descriptor disambiguates from the public async overload.
	// Note parameter order: (Blender, StructureAccessor, NoiseConfig, Chunk, int, int)
	// — StructureAccessor comes before NoiseConfig here, unlike the async version.

	@Inject(
		method = "populateNoise(Lnet/minecraft/world/gen/chunk/Blender;Lnet/minecraft/world/gen/StructureAccessor;Lnet/minecraft/world/gen/noise/NoiseConfig;Lnet/minecraft/world/chunk/Chunk;II)Lnet/minecraft/world/chunk/Chunk;",
		at = @At("HEAD")
	)
	private void apikaprobe$onSyncNoiseStart(
			Blender blender,
			StructureAccessor structureAccessor,
			NoiseConfig noiseConfig,
			Chunk chunk,
			int minimumCellY,
			int cellHeight,
			CallbackInfoReturnable<Chunk> cir) {
		ChunkGenMonitor.onSyncNoiseStart();
	}

	@Inject(
		method = "populateNoise(Lnet/minecraft/world/gen/chunk/Blender;Lnet/minecraft/world/gen/StructureAccessor;Lnet/minecraft/world/gen/noise/NoiseConfig;Lnet/minecraft/world/chunk/Chunk;II)Lnet/minecraft/world/chunk/Chunk;",
		at = @At("RETURN")
	)
	private void apikaprobe$onSyncNoiseEnd(
			Blender blender,
			StructureAccessor structureAccessor,
			NoiseConfig noiseConfig,
			Chunk chunk,
			int minimumCellY,
			int cellHeight,
			CallbackInfoReturnable<Chunk> cir) {
		ChunkGenMonitor.onSyncNoiseEnd();
	}
}
