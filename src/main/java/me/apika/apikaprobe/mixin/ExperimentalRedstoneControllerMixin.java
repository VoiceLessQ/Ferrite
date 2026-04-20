package me.apika.apikaprobe.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import me.apika.apikaprobe.RedstonePhaseMonitor;

import net.minecraft.block.BlockState;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.ExperimentalRedstoneController;
import net.minecraft.world.World;
import net.minecraft.world.block.WireOrientation;

/**
 * Per-call counter for the experimental (Mojang-optimized) redstone
 * controller. Counterpart to [DefaultRedstoneControllerMixin] — the
 * pair lets the 5s log line show `default=X exp=Y` so every test
 * session self-documents which controller is active.
 */
@Mixin(ExperimentalRedstoneController.class)
public abstract class ExperimentalRedstoneControllerMixin {

	@Inject(
		method = "update(Lnet/minecraft/world/World;Lnet/minecraft/util/math/BlockPos;Lnet/minecraft/block/BlockState;Lnet/minecraft/world/block/WireOrientation;Z)V",
		at = @At("HEAD")
	)
	private void apikaprobe$onExperimentalControllerUpdate(
			World world, BlockPos pos, BlockState state, WireOrientation orientation, boolean blockAdded,
			CallbackInfo ci) {
		if (world.isClient()) return;
		RedstonePhaseMonitor.onExperimentalController();
	}
}
