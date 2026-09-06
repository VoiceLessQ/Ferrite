package me.apika.apikaprobe.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import net.minecraft.core.BlockPos;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.levelgen.placement.FeaturePlacer;
import net.minecraft.world.level.levelgen.placement.PlacedFeature;

import me.apika.apikaprobe.monitor.FeatureTiming;

// Times each placeWithBiomeCheck call and records whether it placed anything; default off.
@Mixin(FeaturePlacer.class)
public abstract class FeaturePlacerTimingMixin {
	@Inject(method = "placeWithBiomeCheck", at = @At("HEAD"))
	private void ferrite$featureBegin(PlacedFeature feature, RandomSource random, BlockPos origin,
			CallbackInfoReturnable<Boolean> cir) {
		FeatureTiming.begin();
	}

	@Inject(method = "placeWithBiomeCheck", at = @At("RETURN"))
	private void ferrite$featureEnd(PlacedFeature feature, RandomSource random, BlockPos origin,
			CallbackInfoReturnable<Boolean> cir) {
		FeatureTiming.end(feature, cir.getReturnValueZ());
	}
}
