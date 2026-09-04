package me.apika.apikaprobe.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import net.minecraft.world.level.levelgen.synth.SmearedPerlinNoise;

import me.apika.apikaprobe.monitor.ChunkStageTiming;

// Times the smeared (blended-noise) perlin volume kernel per call.
@Mixin(SmearedPerlinNoise.class)
public abstract class SmearedPerlinVolumeTimingMixin {

	@Inject(method = "addToVolume", at = @At("HEAD"))
	private void ferrite$begin(CallbackInfo ci) {
		ChunkStageTiming.begin();
	}

	@Inject(method = "addToVolume", at = @At("RETURN"))
	private void ferrite$end(CallbackInfo ci) {
		ChunkStageTiming.end("smeared.addToVolume");
	}
}
