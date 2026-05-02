package me.apika.apikaprobe.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import me.apika.apikaprobe.monitor.ServerTickPhaseMonitor;

import net.minecraft.server.level.ServerLevel;

/**
 * Outer-envelope timing for the two scheduledTicks phases of
 * ServerLevel.tick. The two WorldTickScheduler.tick INVOKE sites have
 * the same descriptor; we discriminate by ordinal — vanilla calls
 * blockTickScheduler.tick first (ordinal 0) and fluidTickScheduler.tick
 * second (ordinal 1) at ServerLevel.java:399-401.
 *
 * The two HEAD injects on tickBlock/tickFluid count individual ticks
 * per 5-second window — same approach already used by
 * ServerWorldTickMixin for tickEntity. The increment is one AtomicLong
 * inc per tick (~5 ns), well under the per-tick variance of the work
 * being measured.
 *
 * ChunkManager.tick is declared on the parent class and inherited by
 * ServerChunkManager. The INVOKE bytecode uses the static type of the
 * receiver — getChunkSource() returns ServerChunkManager, so the target
 * descriptor uses that class even though the method is inherited from
 * the parent.
 */
@Mixin(ServerLevel.class)
public abstract class ServerTickPhaseMixin {

	// --- blockTicks (ordinal 0) --------------------------------------------

	@Inject(
		method = "tick(Ljava/util/function/BooleanSupplier;)V",
		at = @At(
			value = "INVOKE",
			target = "Lnet/minecraft/world/ticks/LevelTicks;tick(JILjava/util/function/BiConsumer;)V",
			ordinal = 0
		)
	)
	private void ferrite$onBlockTicksBegin(CallbackInfo ci) {
		ServerTickPhaseMonitor.onBlockTicksBegin();
	}

	@Inject(
		method = "tick(Ljava/util/function/BooleanSupplier;)V",
		at = @At(
			value = "INVOKE",
			target = "Lnet/minecraft/world/ticks/LevelTicks;tick(JILjava/util/function/BiConsumer;)V",
			ordinal = 0,
			shift = At.Shift.AFTER
		)
	)
	private void ferrite$onBlockTicksEnd(CallbackInfo ci) {
		ServerTickPhaseMonitor.onBlockTicksEnd();
	}

	// --- fluidTicks (ordinal 1) --------------------------------------------

	@Inject(
		method = "tick(Ljava/util/function/BooleanSupplier;)V",
		at = @At(
			value = "INVOKE",
			target = "Lnet/minecraft/world/ticks/LevelTicks;tick(JILjava/util/function/BiConsumer;)V",
			ordinal = 1
		)
	)
	private void ferrite$onFluidTicksBegin(CallbackInfo ci) {
		ServerTickPhaseMonitor.onFluidTicksBegin();
	}

	@Inject(
		method = "tick(Ljava/util/function/BooleanSupplier;)V",
		at = @At(
			value = "INVOKE",
			target = "Lnet/minecraft/world/ticks/LevelTicks;tick(JILjava/util/function/BiConsumer;)V",
			ordinal = 1,
			shift = At.Shift.AFTER
		)
	)
	private void ferrite$onFluidTicksEnd(CallbackInfo ci) {
		ServerTickPhaseMonitor.onFluidTicksEnd();
	}

	// --- per-tick counts ---------------------------------------------------

	@Inject(
		method = "tickBlock(Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/Block;)V",
		at = @At("HEAD")
	)
	private void ferrite$countBlockTick(CallbackInfo ci) {
		ServerTickPhaseMonitor.incBlockTickCount();
	}

	@Inject(
		method = "tickFluid(Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/material/Fluid;)V",
		at = @At("HEAD")
	)
	private void ferrite$countFluidTick(CallbackInfo ci) {
		ServerTickPhaseMonitor.incFluidTickCount();
	}

	// chunkTick hook removed intentionally. BEFORE+AFTER @Inject on the
	// ServerChunkManager.tick INVOKE added ~1.1 ms of per-world-tick
	// overhead (measured: total 11.45 ms with hook -> 8.53 ms without),
	// even though the hook fires only ~60 times per second — too rare
	// for nanoTime self-contamination. The most likely cause is that
	// @Inject splits the surrounding ServerLevel.tick body in a way
	// that inhibits JIT inlining across the call site.
	//
	// chunkTick cost is still recoverable indirectly:
	//   chunkTick_approx = other - (worldBorder + weather + raid
	//                                + blockEvents + entityManagement)
	// On measured data, other ≈ 3.95 ms and the housekeeping phases are
	// ~0.5 ms total, so chunkTick ≈ 3.4 ms.
	//
	// Lesson for future instrumentation: even low-call-count @Inject
	// pairs on hot methods can deoptimize the calling method. Prefer
	// computed-other buckets over direct hooks when the target is in
	// an inner server tick loop.
}
