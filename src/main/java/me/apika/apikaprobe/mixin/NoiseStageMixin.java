package me.apika.apikaprobe.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import me.apika.apikaprobe.NoiseStageMonitor;

import net.minecraft.world.gen.chunk.ChunkNoiseSampler;

@Mixin(ChunkNoiseSampler.class)
public abstract class NoiseStageMixin {

	@Inject(method = "sampleStartDensity", at = @At("HEAD"))
	private void apikaprobe$onStartBegin(CallbackInfo ci) {
		NoiseStageMonitor.onStartBegin();
	}

	@Inject(method = "sampleStartDensity", at = @At("RETURN"))
	private void apikaprobe$onStartEnd(CallbackInfo ci) {
		NoiseStageMonitor.onStartEnd();
	}

	@Inject(method = "sampleEndDensity", at = @At("HEAD"))
	private void apikaprobe$onEndBegin(int cellX, CallbackInfo ci) {
		NoiseStageMonitor.onEndBegin();
	}

	@Inject(method = "sampleEndDensity", at = @At("RETURN"))
	private void apikaprobe$onEndEnd(int cellX, CallbackInfo ci) {
		NoiseStageMonitor.onEndEnd();
	}
}
