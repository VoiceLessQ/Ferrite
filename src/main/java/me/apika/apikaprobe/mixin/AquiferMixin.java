package me.apika.apikaprobe.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import me.apika.apikaprobe.monitor.AquiferMonitor;

import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.DensityFunction;

/**
 * Targets Aquifer$Impl — the concrete (non-disabled) aquifer
 * implementation used in overworld generation. The interface method
 * can't be directly hooked; only the Impl class has a body to inject into.
 *
 * Uses a fully qualified descriptor for the apply method so Mixin's
 * transformer has no ambiguity:
 *   apply(DensityFunction.FunctionContext, double) → BlockState
 */
@Mixin(targets = "net.minecraft.world.level.levelgen.Aquifer$Impl")
public abstract class AquiferMixin {

	@Inject(
		method = "apply(Lnet/minecraft/world/level/levelgen/DensityFunction/FunctionContext;D)Lnet/minecraft/world/level/block/state/BlockState;",
		at = @At("HEAD")
	)
	private void apikaprobe$onApplyBegin(
			DensityFunction.FunctionContext pos,
			double density,
			CallbackInfoReturnable<BlockState> cir) {
		if (!AquiferMonitor.ENABLED) return;
		AquiferMonitor.onApplyBegin();
	}

	@Inject(
		method = "apply(Lnet/minecraft/world/level/levelgen/DensityFunction/FunctionContext;D)Lnet/minecraft/world/level/block/state/BlockState;",
		at = @At("RETURN")
	)
	private void apikaprobe$onApplyEnd(
			DensityFunction.FunctionContext pos,
			double density,
			CallbackInfoReturnable<BlockState> cir) {
		if (!AquiferMonitor.ENABLED) return;
		AquiferMonitor.onApplyEnd();
	}
}
