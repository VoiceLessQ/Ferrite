package me.apika.apikaprobe.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import me.apika.apikaprobe.monitor.WorldTickMonitor;

import net.minecraft.block.BlockState;
import net.minecraft.block.entity.SignBlockEntity;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;

/**
 * Counts and self-times invocations of the static
 * {@code SignBlockEntity.tick} ticker.
 *
 * <p>The body is ~5-10 ns (one field load, one null check) so the
 * timing here is a lower-bound estimate. The real per-sign cost lives
 * in the BE-tick infrastructure around the call site (range check,
 * ticker map walk, lambda dispatch, profiler push/pop) which can't be
 * measured from inside the body. Use the {@code [worldtick]
 * blockentities} delta between (few signs loaded) and (many signs
 * loaded) for the actual per-sign infrastructure cost.
 *
 * <p>Two HEAD/RETURN injects so we can wall-time the body. CallbackInfo
 * pools for non-cancellable HEAD injects, so per-call alloc cost is
 * effectively zero.
 */
@Mixin(SignBlockEntity.class)
public abstract class SignTickProbeMixin {

	private static final ThreadLocal<long[]> START_NS = ThreadLocal.withInitial(() -> new long[1]);

	@Inject(
		method = "tick(Lnet/minecraft/world/World;Lnet/minecraft/util/math/BlockPos;Lnet/minecraft/block/BlockState;Lnet/minecraft/block/entity/SignBlockEntity;)V",
		at = @At("HEAD")
	)
	private static void apikaprobe$signTickBegin(
			World world, BlockPos pos, BlockState state, SignBlockEntity blockEntity,
			CallbackInfo ci) {
		if (world.isClient()) return;
		START_NS.get()[0] = System.nanoTime();
	}

	@Inject(
		method = "tick(Lnet/minecraft/world/World;Lnet/minecraft/util/math/BlockPos;Lnet/minecraft/block/BlockState;Lnet/minecraft/block/entity/SignBlockEntity;)V",
		at = @At("RETURN")
	)
	private static void apikaprobe$signTickEnd(
			World world, BlockPos pos, BlockState state, SignBlockEntity blockEntity,
			CallbackInfo ci) {
		if (world.isClient()) return;
		long start = START_NS.get()[0];
		if (start == 0L) return;
		START_NS.get()[0] = 0L;
		WorldTickMonitor.recordSignTick(System.nanoTime() - start);
	}
}
