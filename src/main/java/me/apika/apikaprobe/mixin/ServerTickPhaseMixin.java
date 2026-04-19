package me.apika.apikaprobe.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import me.apika.apikaprobe.ServerTickPhaseMonitor;

import net.minecraft.server.world.ServerWorld;

/**
 * Outer-envelope timing for the two biggest non-entity phases of
 * ServerWorld.tick:
 *   - WorldTickScheduler.tick (covers both blockTicks and fluidTicks —
 *     same method, two call sites, both captured)
 *   - ChunkManager.tick (random-tick loop, chunk updates, spawning)
 *
 * Each hook fires per-world per-tick — ~60 calls/sec on a 3-world server
 * at 20 TPS. Negligible compared to the inner-loop traps we hit earlier.
 *
 * ChunkManager.tick is declared on the parent class and inherited by
 * ServerChunkManager — the @At INVOKE target uses the parent's type
 * signature (class_2802).
 */
@Mixin(ServerWorld.class)
public abstract class ServerTickPhaseMixin {

	// --- scheduledTicks (blockTicks + fluidTicks, same method) -------------

	@Inject(
		method = "tick(Ljava/util/function/BooleanSupplier;)V",
		at = @At(
			value = "INVOKE",
			target = "Lnet/minecraft/world/tick/WorldTickScheduler;tick(JILjava/util/function/BiConsumer;)V"
		)
	)
	private void ferrite$onScheduledBegin(CallbackInfo ci) {
		ServerTickPhaseMonitor.onScheduledTicksBegin();
	}

	@Inject(
		method = "tick(Ljava/util/function/BooleanSupplier;)V",
		at = @At(
			value = "INVOKE",
			target = "Lnet/minecraft/world/tick/WorldTickScheduler;tick(JILjava/util/function/BiConsumer;)V",
			shift = At.Shift.AFTER
		)
	)
	private void ferrite$onScheduledEnd(CallbackInfo ci) {
		ServerTickPhaseMonitor.onScheduledTicksEnd();
	}

	// --- chunkTick ---------------------------------------------------------

	@Inject(
		method = "tick(Ljava/util/function/BooleanSupplier;)V",
		at = @At(
			value = "INVOKE",
			target = "Lnet/minecraft/world/chunk/ChunkManager;tick(Ljava/util/function/BooleanSupplier;Z)V"
		)
	)
	private void ferrite$onChunkTickBegin(CallbackInfo ci) {
		ServerTickPhaseMonitor.onChunkTickBegin();
	}

	@Inject(
		method = "tick(Ljava/util/function/BooleanSupplier;)V",
		at = @At(
			value = "INVOKE",
			target = "Lnet/minecraft/world/chunk/ChunkManager;tick(Ljava/util/function/BooleanSupplier;Z)V",
			shift = At.Shift.AFTER
		)
	)
	private void ferrite$onChunkTickEnd(CallbackInfo ci) {
		ServerTickPhaseMonitor.onChunkTickEnd();
	}
}
