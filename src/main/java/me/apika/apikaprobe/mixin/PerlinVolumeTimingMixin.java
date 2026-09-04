package me.apika.apikaprobe.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import net.minecraft.world.level.levelgen.synth.PerlinNoise;

import me.apika.apikaprobe.monitor.ChunkStageTiming;

// Times vanilla's bulk perlin kernel per volume call (26.3 gate reading).
@Mixin(PerlinNoise.class)
public abstract class PerlinVolumeTimingMixin {

	@Inject(method = "addToVolume", at = @At("HEAD"))
	private void ferrite$begin(CallbackInfo ci) {
		ChunkStageTiming.begin();
	}

	@Inject(method = "addToVolume", at = @At("RETURN"))
	private void ferrite$end(CallbackInfo ci) {
		ChunkStageTiming.end("perlin.addToVolume");
	}
}
