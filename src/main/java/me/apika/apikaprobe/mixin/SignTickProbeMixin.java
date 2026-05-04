package me.apika.apikaprobe.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import me.apika.apikaprobe.monitor.WorldTickMonitor;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.SignBlockEntity;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Counts and self-times invocations of the static
 * SignBlockEntity.tick ticker.
 *
 * <p>The body is ~5-10 ns (one field load, one null check) so the
 * timing here is a lower-bound estimate. The real per-sign cost lives
 * in the BE-tick infrastructure around the call site (range check,
 * ticker map walk, lambda dispatch, profiler push/pop) which can't be
 * measured from inside the body. Use the [worldtick] blockentities
 * delta between (few signs loaded) and (many signs loaded) for the
 * actual per-sign infrastructure cost.
 *
 * <p>Two HEAD/RETURN injects so we can wall-time the body. CallbackInfo
 * pools for non-cancellable HEAD injects, so per-call alloc cost is
 * effectively zero.
 */
@Mixin(SignBlockEntity.class)
public abstract class SignTickProbeMixin {

	private static final ThreadLocal<long[]> START_NS = ThreadLocal.withInitial(() -> new long[1]);

	@Inject(
		method = "tick(Lnet/minecraft/world/level/Level;Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;Lnet/minecraft/world/level/block/entity/SignBlockEntity;)V",
		at = @At("HEAD")
	)
	private static void apikaprobe$signTickBegin(
			Level world, BlockPos pos, BlockState state, SignBlockEntity blockEntity,
			CallbackInfo ci) {
		if (world.isClientSide()) return;
		START_NS.get()[0] = System.nanoTime();
	}

	@Inject(
		method = "tick(Lnet/minecraft/world/level/Level;Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;Lnet/minecraft/world/level/block/entity/SignBlockEntity;)V",
		at = @At("RETURN")
	)
	private static void apikaprobe$signTickEnd(
			Level world, BlockPos pos, BlockState state, SignBlockEntity blockEntity,
			CallbackInfo ci) {
		if (world.isClientSide()) return;
		long start = START_NS.get()[0];
		if (start == 0L) return;
		START_NS.get()[0] = 0L;
		WorldTickMonitor.recordSignTick(System.nanoTime() - start);
	}
}
