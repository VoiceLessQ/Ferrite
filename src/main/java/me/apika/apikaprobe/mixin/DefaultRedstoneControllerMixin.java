package me.apika.apikaprobe.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import me.apika.apikaprobe.monitor.RedstonePhaseMonitor;

import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.redstone.VanillaRedstoneWireEvaluator;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.redstone.Orientation;

/**
 * Per-call counter for the default (slow) redstone controller. Tagged
 * at HEAD of update so a single counter tick corresponds to one
 * controller call. Used by [RedstonePhaseMonitor] to verify at a
 * glance which controller the current world is running — critical
 * when comparing Rust-port benchmarks so we're not accidentally
 * measuring the already-optimized experimental path.
 */
@Mixin(VanillaRedstoneWireEvaluator.class)
public abstract class DefaultRedstoneControllerMixin {

	@Inject(
		method = "update(Lnet.minecraft.world.level.Level;Lnet.minecraft.core.BlockPos;Lnet.minecraft.world.level.block.state.BlockState;Lnet.minecraft.world.level.redstone.Orientation;Z)V",
		at = @At("HEAD")
	)
	private void apikaprobe$onDefaultControllerUpdate(
			Level world, BlockPos pos, BlockState state, Orientation orientation, boolean blockAdded,
			CallbackInfo ci) {
		if (world.isClient()) return;
		RedstonePhaseMonitor.onDefaultController();
	}
}
