package me.apika.apikaprobe.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import me.apika.apikaprobe.monitor.ChunkStageTiming;

// Times the interpolation node's volume sample (child at cell res plus lerp).
@Mixin(targets = "net.minecraft.world.level.levelgen.densityfunction.op.InterpolatedFunction$Sampler")
public abstract class InterpolatedVolumeTimingMixin {

	@Inject(method = "sampleVolume", at = @At("HEAD"))
	private void ferrite$begin(CallbackInfo ci) {
		ChunkStageTiming.begin();
	}

	@Inject(method = "sampleVolume", at = @At("RETURN"))
	private void ferrite$end(CallbackInfo ci) {
		ChunkStageTiming.end("interp.sampleVolume");
	}
}
