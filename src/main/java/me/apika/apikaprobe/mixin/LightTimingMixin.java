package me.apika.apikaprobe.mixin;

import java.util.concurrent.CompletableFuture;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import net.minecraft.server.level.ThreadedLevelLightEngine;
import net.minecraft.world.level.chunk.ChunkAccess;

import me.apika.apikaprobe.monitor.LightTimingMonitor;

/**
 * Times the {@code INITIALIZE_LIGHT} and {@code LIGHT} chunkgen phases
 * by attaching a {@code whenComplete} listener to the returned
 * {@code CompletableFuture}. Captures the actual async work duration
 * (light dispatcher → flood fill → completion), not just the
 * task-submission overhead.
 *
 * <p>Yarn class: {@code ThreadedLevelLightEngine} (mojmap
 * {@code ThreadedLevelLightEngine}). Yarn methods:
 * {@code initializeLight}, {@code light} (mojmap {@code lightChunk}).
 *
 * <p>Per-call start time lives in a ThreadLocal; the listener captures
 * the closure'd start when the future completes on a (potentially
 * different) light worker thread.
 */
@Mixin(ThreadedLevelLightEngine.class)
public abstract class LightTimingMixin {

	@Unique
	private static final ThreadLocal<long[]> ferrite$initStart =
			ThreadLocal.withInitial(() -> new long[1]);

	@Unique
	private static final ThreadLocal<long[]> ferrite$lightStart =
			ThreadLocal.withInitial(() -> new long[1]);

	@Inject(method = "initializeLight", at = @At("HEAD"))
	private void ferrite$initHead(ChunkAccess chunk, boolean lit,
			CallbackInfoReturnable<CompletableFuture<ChunkAccess>> cir) {
		ferrite$initStart.get()[0] = System.nanoTime();
	}

	@Inject(method = "initializeLight", at = @At("RETURN"))
	private void ferrite$initReturn(ChunkAccess chunk, boolean lit,
			CallbackInfoReturnable<CompletableFuture<ChunkAccess>> cir) {
		long startNs = ferrite$initStart.get()[0];
		ferrite$initStart.get()[0] = 0L;
		CompletableFuture<ChunkAccess> f = cir.getReturnValue();
		if (f != null && startNs != 0L) {
			f.whenComplete((c, t) ->
					LightTimingMonitor.recordInit(System.nanoTime() - startNs));
		}
	}

	@Inject(method = "lightChunk", at = @At("HEAD"))
	private void ferrite$lightHead(ChunkAccess chunk, boolean lit,
			CallbackInfoReturnable<CompletableFuture<ChunkAccess>> cir) {
		ferrite$lightStart.get()[0] = System.nanoTime();
	}

	@Inject(method = "lightChunk", at = @At("RETURN"))
	private void ferrite$lightReturn(ChunkAccess chunk, boolean lit,
			CallbackInfoReturnable<CompletableFuture<ChunkAccess>> cir) {
		long startNs = ferrite$lightStart.get()[0];
		ferrite$lightStart.get()[0] = 0L;
		CompletableFuture<ChunkAccess> f = cir.getReturnValue();
		if (f != null && startNs != 0L) {
			f.whenComplete((c, t) ->
					LightTimingMonitor.recordLight(System.nanoTime() - startNs));
		}
	}
}
