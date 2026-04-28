package me.apika.apikaprobe.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import me.apika.apikaprobe.monitor.AquiferMonitor;

import net.minecraft.block.BlockState;
import net.minecraft.world.gen.densityfunction.DensityFunction;

/**
 * Targets AquiferSampler$Impl — the concrete (non-disabled) aquifer
 * implementation used in overworld generation. The interface method
 * can't be directly hooked; only the Impl class has a body to inject into.
 *
 * Uses a fully qualified descriptor for the apply method so Mixin's
 * transformer has no ambiguity:
 *   apply(DensityFunction$NoisePos, double) → BlockState
 */
@Mixin(targets = "net.minecraft.world.gen.chunk.AquiferSampler$Impl")
public abstract class AquiferMixin {

	@Inject(
		method = "apply(Lnet/minecraft/world/gen/densityfunction/DensityFunction$NoisePos;D)Lnet/minecraft/block/BlockState;",
		at = @At("HEAD")
	)
	private void apikaprobe$onApplyBegin(
			DensityFunction.NoisePos pos,
			double density,
			CallbackInfoReturnable<BlockState> cir) {
		AquiferMonitor.onApplyBegin();
	}

	@Inject(
		method = "apply(Lnet/minecraft/world/gen/densityfunction/DensityFunction$NoisePos;D)Lnet/minecraft/block/BlockState;",
		at = @At("RETURN")
	)
	private void apikaprobe$onApplyEnd(
			DensityFunction.NoisePos pos,
			double density,
			CallbackInfoReturnable<BlockState> cir) {
		AquiferMonitor.onApplyEnd();
	}
}
