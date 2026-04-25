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
import me.apika.apikaprobe.InterpolatorNameRegistry;
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

		// Only cache-wrapper return classes are interesting; passthrough
		// and unwrap returns aren't carrying a fillable buffer anyway.
		boolean isCacheWrapper = cls.contains("FlatCache")
				|| cls.contains("Interpolator")
				|| cls.contains("CacheAllInCell") || cls.contains("CellCache")
				|| cls.contains("Cache2D") || cls.contains("CacheOnce");
		if (!isCacheWrapper) {
			CacheRouteStats.record(cls, null);
			return;
		}

		// Fast path: identity on the input Marker. Rare hit (mapAll
		// re-instantiates), but cheap.
		String rustName = WorldgenStateBootstrap.identifiedRouterDfs().get(function);

		// Real path: fingerprint the RETURN VALUE's wrapped() subtree.
		// Every cache wrapper class implements MarkerOrMarked.wrapped() →
		// the inner DF it caches. This works regardless of how the input
		// reached us (Marker, HolderHolder unwrap, recursive call, etc.) —
		// the wrapped subtree is the same thing the cache will fill from.
		if (rustName == null) {
			Object inner = ferrite$callWrapped(returned);
			if (inner != null) {
				String fp = DensityFunctionWalker.fingerprint(inner);
				if (fp != null) {
					rustName = WorldgenStateBootstrap.fingerprintToName().get(fp);
				}
			}
		}
		CacheRouteStats.record(cls, rustName);
		// Step 2b: also stash the per-instance rustName so the fillSlice
		// mixin (and friends) can look up which Rust DF to bulk-call
		// when this wrapper participates in the cache fill.
		if (rustName != null) {
			InterpolatorNameRegistry.record(returned, rustName);
		}
		if (rustName == null) {
			CacheRouteStats.recordUnmatchedShape(cls, function.getClass().getSimpleName());
			// One-shot dump of cellCache fingerprint hex prefix so we can
			// diff against what we registered as the synthetic — only logs
			// once per JVM via a sentinel inside CacheRouteStats.
			if (cls.contains("CellCache") || cls.contains("CacheAllInCell")) {
				Object inner = ferrite$callWrapped(returned);
				if (inner != null) {
					String fp = DensityFunctionWalker.fingerprint(inner);
					CacheRouteStats.dumpCellCacheFingerprintOnce(fp);
					CacheRouteStats.dumpCellCacheStructureOnce(inner);
				}
			}
		}
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
