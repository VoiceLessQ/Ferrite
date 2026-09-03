package me.apika.apikaprobe.mixin;

import java.util.concurrent.CompletableFuture;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import net.minecraft.server.level.GenerationChunkHolder;
import net.minecraft.util.StaticCache2D;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.status.ChunkStatusTasks;
import net.minecraft.world.level.chunk.status.ChunkStep;
import net.minecraft.world.level.chunk.status.WorldGenContext;

import me.apika.apikaprobe.monitor.ChunkStageTiming;

// Times each ChunkStatusTasks stage HEAD to RETURN; async stages capture only their handoff.
// 26.3 merged noise, surface and carvers into buildTerrain.
@Mixin(ChunkStatusTasks.class)
public abstract class ChunkStageTimingMixin {

	@Inject(method = { "generateStructureStarts", "generateStructureReferences", "generateBiomes",
			"buildTerrain", "generateFeatures", "generateSpawn" },
			at = @At("HEAD"))
	private static void ferrite$begin(WorldGenContext context, ChunkStep step,
			StaticCache2D<GenerationChunkHolder> chunks, ChunkAccess chunk,
			CallbackInfoReturnable<CompletableFuture<ChunkAccess>> cir) {
		ChunkStageTiming.begin();
	}

	@Inject(method = "generateStructureStarts", at = @At("RETURN"))
	private static void ferrite$endStructureStarts(WorldGenContext context, ChunkStep step,
			StaticCache2D<GenerationChunkHolder> chunks, ChunkAccess chunk,
			CallbackInfoReturnable<CompletableFuture<ChunkAccess>> cir) {
		ChunkStageTiming.end("generateStructureStarts");
	}

	@Inject(method = "generateStructureReferences", at = @At("RETURN"))
	private static void ferrite$endStructureReferences(WorldGenContext context, ChunkStep step,
			StaticCache2D<GenerationChunkHolder> chunks, ChunkAccess chunk,
			CallbackInfoReturnable<CompletableFuture<ChunkAccess>> cir) {
		ChunkStageTiming.end("generateStructureReferences");
	}

	@Inject(method = "generateBiomes", at = @At("RETURN"))
	private static void ferrite$endBiomes(WorldGenContext context, ChunkStep step,
			StaticCache2D<GenerationChunkHolder> chunks, ChunkAccess chunk,
			CallbackInfoReturnable<CompletableFuture<ChunkAccess>> cir) {
		ChunkStageTiming.end("generateBiomes");
	}

	@Inject(method = "buildTerrain", at = @At("RETURN"))
	private static void ferrite$endTerrain(WorldGenContext context, ChunkStep step,
			StaticCache2D<GenerationChunkHolder> chunks, ChunkAccess chunk,
			CallbackInfoReturnable<CompletableFuture<ChunkAccess>> cir) {
		ChunkStageTiming.end("buildTerrain");
	}

	@Inject(method = "generateFeatures", at = @At("RETURN"))
	private static void ferrite$endFeatures(WorldGenContext context, ChunkStep step,
			StaticCache2D<GenerationChunkHolder> chunks, ChunkAccess chunk,
			CallbackInfoReturnable<CompletableFuture<ChunkAccess>> cir) {
		ChunkStageTiming.end("generateFeatures");
	}

	@Inject(method = "generateSpawn", at = @At("RETURN"))
	private static void ferrite$endSpawn(WorldGenContext context, ChunkStep step,
			StaticCache2D<GenerationChunkHolder> chunks, ChunkAccess chunk,
			CallbackInfoReturnable<CompletableFuture<ChunkAccess>> cir) {
		ChunkStageTiming.end("generateSpawn");
	}
}
