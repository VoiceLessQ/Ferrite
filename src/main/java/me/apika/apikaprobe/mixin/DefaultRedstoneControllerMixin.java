package me.apika.apikaprobe.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import me.apika.apikaprobe.monitor.RedstonePhaseMonitor;

import net.minecraft.block.BlockState;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.DefaultRedstoneController;
import net.minecraft.world.World;
import net.minecraft.world.block.WireOrientation;

/**
 * Per-call counter for the default (slow) redstone controller. Tagged
 * at HEAD of update so a single counter tick corresponds to one
 * controller call. Used by [RedstonePhaseMonitor] to verify at a
 * glance which controller the current world is running — critical
 * when comparing Rust-port benchmarks so we're not accidentally
 * measuring the already-optimized experimental path.
 */
@Mixin(DefaultRedstoneController.class)
public abstract class DefaultRedstoneControllerMixin {

	@Inject(
		method = "update(Lnet/minecraft/world/World;Lnet/minecraft/util/math/BlockPos;Lnet/minecraft/block/BlockState;Lnet/minecraft/world/block/WireOrientation;Z)V",
		at = @At("HEAD")
	)
	private void apikaprobe$onDefaultControllerUpdate(
			World world, BlockPos pos, BlockState state, WireOrientation orientation, boolean blockAdded,
			CallbackInfo ci) {
		if (world.isClient()) return;
		RedstonePhaseMonitor.onDefaultController();
	}
}
