package me.apika.apikaprobe.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import me.apika.apikaprobe.monitor.RedstonePhaseMonitor;

import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.redstone.AlternateCurrentRedstoneWireEvaluator;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.redstone.Orientation;

/**
 * Per-call counter for the experimental (Mojang-optimized) redstone
 * controller. Counterpart to [DefaultRedstoneControllerMixin] — the
 * pair lets the 5s log line show `default=X exp=Y` so every test
 * session self-documents which controller is active.
 */
@Mixin(AlternateCurrentRedstoneWireEvaluator.class)
public abstract class ExperimentalRedstoneControllerMixin {

	@Inject(
		method = "update(Lnet.minecraft.world.level.Level;Lnet.minecraft.core.BlockPos;Lnet.minecraft.world.level.block.state.BlockState;Lnet.minecraft.world.level.redstone.Orientation;Z)V",
		at = @At("HEAD")
	)
	private void apikaprobe$onExperimentalControllerUpdate(
			Level world, BlockPos pos, BlockState state, Orientation orientation, boolean blockAdded,
			CallbackInfo ci) {
		if (world.isClient()) return;
		RedstonePhaseMonitor.onExperimentalController();
	}
}
