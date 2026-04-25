package me.apika.apikaprobe.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import net.minecraft.world.gen.chunk.ChunkNoiseSampler;
import net.minecraft.world.gen.densityfunction.DensityFunction;

import java.lang.reflect.Method;

import me.apika.apikaprobe.CacheRouteStats;
import me.apika.apikaprobe.DensityFunctionWalker;
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

		// Fast path: identity. Rare hit because vanilla's mapAll(this::wrap)
		// re-instantiates Markers, but cheap to try.
		String rustName = WorldgenStateBootstrap.identifiedRouterDfs().get(function);

		// Real path: fingerprint the input Marker's wrapped subtree.
		// DensityFunctionWalker emits the same OP_MARKER bytes for both
		// Markers and their post-mapAll cache-wrapper equivalents, so the
		// fingerprint we computed at world load matches what we compute now.
		if (rustName == null) {
			Object inner = ferrite$callWrapped(function);
			if (inner != null) {
				String fp = DensityFunctionWalker.fingerprint(inner);
				if (fp != null) {
					rustName = WorldgenStateBootstrap.fingerprintToName().get(fp);
				}
			}
		}
		CacheRouteStats.record(cls, rustName);
	}

	/** Reflectively call {@code wrapped()} on the Marker so we don't
	 *  pin to the yarn-private {@code DensityFunctionTypes$Wrapping} class. */
	private static Object ferrite$callWrapped(Object marker) {
		try {
			Method m = marker.getClass().getMethod("wrapped");
			return m.invoke(marker);
		} catch (ReflectiveOperationException | RuntimeException e) {
			return null;
		}
	}
}
