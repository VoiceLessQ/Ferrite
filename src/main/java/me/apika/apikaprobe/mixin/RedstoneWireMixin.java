package me.apika.apikaprobe.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import me.apika.apikaprobe.monitor.RedstonePhaseMonitor;

import net.minecraft.block.BlockState;
import net.minecraft.block.RedstoneWireBlock;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import net.minecraft.world.block.WireOrientation;

/**
 * Times every wire-power propagation cascade.
 *
 * RedstoneWireBlock.update is the single private dispatcher that routes
 * to DefaultRedstoneController or ExperimentalRedstoneController — so
 * one mixin target catches both feature-flag branches. The method is
 * private but mixin targets it by name + descriptor.
 *
 * Recursion handling is in [RedstonePhaseMonitor.onWireBegin/onWireEnd]:
 * a ThreadLocal depth counter ensures only the outermost call of a
 * cascade starts/stops the timer. Inner recursive calls increment the
 * depth but do not start new timers.
 *
 * Client-side filter: the method accepts `World` (not `ServerWorld`), so
 * it could theoretically run on the integrated client. We gate on
 * `!world.isClient()` to keep metrics server-side only.
 */
@Mixin(RedstoneWireBlock.class)
public abstract class RedstoneWireMixin {

	@Inject(
		method = "update(Lnet/minecraft/world/World;Lnet/minecraft/util/math/BlockPos;Lnet/minecraft/block/BlockState;Lnet/minecraft/world/block/WireOrientation;Z)V",
		at = @At("HEAD")
	)
	private void apikaprobe$onWireUpdateBegin(
			World world, BlockPos pos, BlockState state, WireOrientation orientation, boolean blockAdded,
			CallbackInfo ci) {
		if (world.isClient()) return;
		RedstonePhaseMonitor.onWireBegin();
	}

	@Inject(
		method = "update(Lnet/minecraft/world/World;Lnet/minecraft/util/math/BlockPos;Lnet/minecraft/block/BlockState;Lnet/minecraft/world/block/WireOrientation;Z)V",
		at = @At("RETURN")
	)
	private void apikaprobe$onWireUpdateEnd(
			World world, BlockPos pos, BlockState state, WireOrientation orientation, boolean blockAdded,
			CallbackInfo ci) {
		if (world.isClient()) return;
		RedstonePhaseMonitor.onWireEnd();
	}
}
