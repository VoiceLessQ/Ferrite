package me.apika.apikaprobe.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import net.minecraft.world.level.biome.MultiNoiseBiomeSource;

import me.apika.apikaprobe.worldgen.BiomeParity;

/**
 * Captures the live {@link MultiNoiseBiomeSource} into
 * {@link BiomeParity} at construction so the parity validator has a
 * yarn-side ground truth without needing to walk the chunk generator
 * each time.
 */
@Mixin(MultiNoiseBiomeSource.class)
public abstract class MultiNoiseBiomeSourceCaptureMixin {
	@Inject(method = "<init>", at = @At("RETURN"))
	private void ferrite$captureBiomeSource(CallbackInfo ci) {
		BiomeParity.captureBiomeSource((MultiNoiseBiomeSource)(Object) this);
	}
}
