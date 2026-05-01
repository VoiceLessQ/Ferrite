package me.apika.apikaprobe.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import me.apika.apikaprobe.monitor.RedstonePhaseMonitor;

import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.RedStoneWireBlock;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.redstone.Orientation;

/**
 * Times every wire-power propagation cascade.
 *
 * RedStoneWireBlock.update is the single private dispatcher that routes
 * to VanillaRedstoneWireEvaluator or AlternateCurrentRedstoneWireEvaluator — so
 * one mixin target catches both feature-flag branches. The method is
 * private but mixin targets it by name + descriptor.
 *
 * Recursion handling is in [RedstonePhaseMonitor.onWireBegin/onWireEnd]:
 * a ThreadLocal depth counter ensures only the outermost call of a
 * cascade starts/stops the timer. Inner recursive calls increment the
 * depth but do not start new timers.
 *
 * Client-side filter: the method accepts `Level` (not `ServerLevel`), so
 * it could theoretically run on the integrated client. We gate on
 * `!world.isClientSide()` to keep metrics server-side only.
 */
@Mixin(RedStoneWireBlock.class)
public abstract class RedstoneWireMixin {

	@Inject(
		method = "update(Lnet.minecraft.world.level.Level;Lnet.minecraft.core.BlockPos;Lnet.minecraft.world.level.block.state.BlockState;Lnet.minecraft.world.level.redstone.Orientation;Z)V",
		at = @At("HEAD")
	)
	private void apikaprobe$onWireUpdateBegin(
			Level world, BlockPos pos, BlockState state, Orientation orientation, boolean blockAdded,
			CallbackInfo ci) {
		if (world.isClientSide()) return;
		RedstonePhaseMonitor.onWireBegin();
	}

	@Inject(
		method = "update(Lnet.minecraft.world.level.Level;Lnet.minecraft.core.BlockPos;Lnet.minecraft.world.level.block.state.BlockState;Lnet.minecraft.world.level.redstone.Orientation;Z)V",
		at = @At("RETURN")
	)
	private void apikaprobe$onWireUpdateEnd(
			Level world, BlockPos pos, BlockState state, Orientation orientation, boolean blockAdded,
			CallbackInfo ci) {
		if (world.isClientSide()) return;
		RedstonePhaseMonitor.onWireEnd();
	}
}
