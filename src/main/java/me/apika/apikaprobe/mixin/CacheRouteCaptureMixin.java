package me.apika.apikaprobe.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import net.minecraft.world.gen.chunk.ChunkNoiseSampler;
import net.minecraft.world.gen.densityfunction.DensityFunction;

import me.apika.apikaprobe.CacheRouteStats;
import me.apika.apikaprobe.WorldgenStateBootstrap;

/**
 * Phase 2.5 step 2a — pure-observational mixin.
 *
 * <p>Every time vanilla's wrap chain replaces a {@code Marker(type, X)}
 * with one of its cache wrapper classes (FlatCache, NoiseInterpolator,
 * CacheAllInCell, etc.), record whether the input Marker is in our
 * deep-walker identity map. Counters live in {@link CacheRouteStats};
 * see that class for what the numbers mean.
 *
 * <p>Read-only: doesn't change the returned wrapper, so vanilla
 * chunkgen runs unmodified. Confirms step 1's identity map actually
 * matches at chunk-wrap time before we commit to writing the real
 * fill mixin (step 2b).
 */
@Mixin(ChunkNoiseSampler.class)
public abstract class CacheRouteCaptureMixin {

	@Inject(
			method = "getActualDensityFunctionImpl",
			at = @At("RETURN")
	)
	private void ferrite$captureCacheRoute(DensityFunction function,
			CallbackInfoReturnable<DensityFunction> cir) {
		DensityFunction returned = cir.getReturnValue();
		if (returned == null || returned == function) return;
		String cls = returned.getClass().getSimpleName();
		String rustName = WorldgenStateBootstrap.identifiedRouterDfs().get(function);
		CacheRouteStats.record(cls, rustName);
	}
}
