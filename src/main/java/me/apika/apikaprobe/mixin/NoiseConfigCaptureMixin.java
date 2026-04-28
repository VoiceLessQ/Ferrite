package me.apika.apikaprobe.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import net.minecraft.world.gen.noise.NoiseConfig;

import me.apika.apikaprobe.worldgen.WorldgenParity;

/**
 * Captures every constructed {@link NoiseConfig} into a static slot on
 * {@link WorldgenParity} so the Rust↔Java noise parity validator has a
 * live sampler source without plumbing one through reflection every
 * time.
 *
 * <p>Yarn's {@code NoiseConfig} is built once per world (equivalent of
 * mojmap {@code RandomState}). The last-captured reference is what the
 * current world is using; earlier captures are safe to overwrite.
 *
 * <p>Uses {@code <init>} RETURN so the instance is fully constructed
 * before we stash it.
 */
@Mixin(NoiseConfig.class)
public abstract class NoiseConfigCaptureMixin {
	@Inject(method = "<init>", at = @At("RETURN"))
	private void ferrite$captureNoiseConfig(CallbackInfo ci) {
		WorldgenParity.captureNoiseConfig((NoiseConfig)(Object) this);
	}
}
