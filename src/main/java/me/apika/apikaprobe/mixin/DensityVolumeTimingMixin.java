package me.apika.apikaprobe.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import net.minecraft.world.level.levelgen.densityfunction.DensitySampler;
import net.minecraft.world.level.levelgen.densityfunction.DensityVolume;
import net.minecraft.world.level.levelgen.densityfunction.ScopedDensityBuffer;

import me.apika.apikaprobe.monitor.ChunkStageTiming;

// Times whole-volume density sampling (the 26.3 bulk seam) per call.
@Mixin(DensitySampler.Bound.class)
public abstract class DensityVolumeTimingMixin {

	@Inject(method = "sampleVolume(Lnet/minecraft/world/level/levelgen/densityfunction/DensityVolume;)Lnet/minecraft/world/level/levelgen/densityfunction/ScopedDensityBuffer;",
			at = @At("HEAD"))
	private void ferrite$begin(DensityVolume volume, CallbackInfoReturnable<ScopedDensityBuffer> cir) {
		ChunkStageTiming.begin();
	}

	@Inject(method = "sampleVolume(Lnet/minecraft/world/level/levelgen/densityfunction/DensityVolume;)Lnet/minecraft/world/level/levelgen/densityfunction/ScopedDensityBuffer;",
			at = @At("RETURN"))
	private void ferrite$end(DensityVolume volume, CallbackInfoReturnable<ScopedDensityBuffer> cir) {
		ChunkStageTiming.end("sampleVolume");
	}
}
