package me.apika.apikaprobe.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator;

import me.apika.apikaprobe.monitor.ChunkStageTiming;

// Times the pool-side halves of buildTerrain (26.3): noise fill, surface, carvers.
@Mixin(NoiseBasedChunkGenerator.class)
public abstract class TerrainPoolTimingMixin {

	@Inject(method = { "doFill", "buildSurface", "generateCarvers" }, at = @At("HEAD"))
	private void ferrite$begin(CallbackInfo ci) {
		ChunkStageTiming.begin();
	}

	@Inject(method = "doFill", at = @At("RETURN"))
	private void ferrite$endFill(CallbackInfo ci) {
		ChunkStageTiming.end("doFill");
	}

	@Inject(method = "buildSurface", at = @At("RETURN"))
	private void ferrite$endSurface(CallbackInfo ci) {
		ChunkStageTiming.end("buildSurface");
	}

	@Inject(method = "generateCarvers", at = @At("RETURN"))
	private void ferrite$endCarvers(CallbackInfo ci) {
		ChunkStageTiming.end("generateCarvers");
	}
}
