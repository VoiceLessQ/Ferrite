package me.apika.apikaprobe.mixin;

import java.util.concurrent.CompletableFuture;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import org.slf4j.LoggerFactory;

import me.apika.apikaprobe.monitor.ChunkGenMonitor;

import net.minecraft.server.level.WorldGenRegion;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.StructureManager;
import net.minecraft.world.level.levelgen.blending.Blender;
import net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator;
import net.minecraft.world.level.levelgen.RandomState;

@Mixin(NoiseBasedChunkGenerator.class)
public abstract class ChunkPhaseMixin {

	static {
		LoggerFactory.getLogger("ferrite").info("[chunkgen] ChunkPhaseMixin loaded");
	}

	@Inject(method = "populateNoise", at = @At("HEAD"))
	private void apikaprobe$onNoiseStart(
			Blender blender,
			RandomState noiseConfig,
			StructureManager structureAccessor,
			ChunkAccess chunk,
			CallbackInfoReturnable<CompletableFuture<ChunkAccess>> cir) {
		ChunkGenMonitor.onNoiseStart();
	}

	@Inject(method = "populateNoise", at = @At("RETURN"))
	private void apikaprobe$onNoiseEnd(
			Blender blender,
			RandomState noiseConfig,
			StructureManager structureAccessor,
			ChunkAccess chunk,
			CallbackInfoReturnable<CompletableFuture<ChunkAccess>> cir) {
		ChunkGenMonitor.onNoiseEnd();
	}

	@Inject(method = "buildSurface", at = @At("HEAD"))
	private void apikaprobe$onSurfaceStart(
			WorldGenRegion region,
			StructureManager structures,
			RandomState noiseConfig,
			ChunkAccess chunk,
			CallbackInfo ci) {
		ChunkGenMonitor.onSurfaceStart();
	}

	@Inject(method = "buildSurface", at = @At("RETURN"))
	private void apikaprobe$onSurfaceEnd(
			WorldGenRegion region,
			StructureManager structures,
			RandomState noiseConfig,
			ChunkAccess chunk,
			CallbackInfo ci) {
		ChunkGenMonitor.onSurfaceEnd();
	}

	// --- private sync populateNoise (the real noise work) -------------------
	// Fully qualified descriptor disambiguates from the public async overload.
	// Note parameter order: (Blender, StructureManager, RandomState, ChunkAccess, int, int)
	// — StructureManager comes before RandomState here, unlike the async version.

	@Inject(
		method = "populateNoise(Lnet/minecraft/world/level/levelgen/blending/Blender;Lnet/minecraft/world/level/StructureManager;Lnet/minecraft/world/level/levelgen/RandomState;Lnet/minecraft/world/level/chunk/ChunkAccess;II)Lnet/minecraft/world/level/chunk/ChunkAccess;",
		at = @At("HEAD")
	)
	private void apikaprobe$onSyncNoiseStart(
			Blender blender,
			StructureManager structureAccessor,
			RandomState noiseConfig,
			ChunkAccess chunk,
			int minimumCellY,
			int cellHeight,
			CallbackInfoReturnable<ChunkAccess> cir) {
		ChunkGenMonitor.onSyncNoiseStart();
	}

	@Inject(
		method = "populateNoise(Lnet/minecraft/world/level/levelgen/blending/Blender;Lnet/minecraft/world/level/StructureManager;Lnet/minecraft/world/level/levelgen/RandomState;Lnet/minecraft/world/level/chunk/ChunkAccess;II)Lnet/minecraft/world/level/chunk/ChunkAccess;",
		at = @At("RETURN")
	)
	private void apikaprobe$onSyncNoiseEnd(
			Blender blender,
			StructureManager structureAccessor,
			RandomState noiseConfig,
			ChunkAccess chunk,
			int minimumCellY,
			int cellHeight,
			CallbackInfoReturnable<ChunkAccess> cir) {
		ChunkGenMonitor.onSyncNoiseEnd();
	}
}
