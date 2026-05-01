package me.apika.apikaprobe.mixin;

import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import net.minecraft.world.level.levelgen.NoiseChunk;
import net.minecraft.world.level.levelgen.DensityFunction;

import me.apika.apikaprobe.worldgen.RustFlatCache;
import me.apika.apikaprobe.worldgen.RustFinalDensityBufferWrapper;
import me.apika.apikaprobe.worldgen.WorldgenStateBootstrap;

/**
 * Phase 2.5 — "become the cache". When vanilla's wrap chain encounters
 * {@code Marker(FlatCache, X)} (yarn {@code Wrapping(FLAT_CACHE, X)})
 * where X is a known registered DF, return a {@link RustFlatCache} that
 * pre-fills its 5 × 5 quart-grid in bulk via Rust on first sample.
 * Vanilla's outer pipeline reads from our cache identically to its own
 * — we ARE the cache.
 *
 * <p>Yarn's {@code DensityFunctionTypes$Wrapping} is package-private —
 * we detect by class simple name + read the type/wrapped fields via
 * reflection rather than instanceof. Same pattern as
 * {@code DensityFunctionWalker} elsewhere.
 *
 * <p>Toggle: {@link RustFinalDensityBufferWrapper#ENABLED} (re-used for
 * the {@code /ferrite noise rust on/off} command surface).
 */
@Mixin(NoiseChunk.class)
public abstract class ChunkNoiseSamplerMixin {

	@Shadow
	@Final
	private int startCellX;

	@Shadow
	@Final
	private int startCellZ;

	@Shadow
	@Final
	private int horizontalCellBlockCount;

	@Inject(
			method = "getActualDensityFunctionImpl",
			at = @At("HEAD"),
			cancellable = true
	)
	private void ferrite$swapFlatCache(DensityFunction function,
			CallbackInfoReturnable<DensityFunction> cir) {
		if (!RustFinalDensityBufferWrapper.ENABLED) return;
		String cls = function.getClass().getSimpleName();
		if (!cls.equals("Wrapping") && !cls.endsWith("$Wrapping")) return;

		// Wrapping is a record with `type` and `wrapped` accessor methods
		// (yarn names: `type()`, `wrapped()`). Read the type's name() to
		// distinguish FLAT_CACHE from INTERPOLATED / CACHE2D / etc.
		Object typeObj = invokeNoArg(function, "type");
		if (typeObj == null) return;
		String typeName = typeObj.toString();
		if (!"FLAT_CACHE".equals(typeName)) return;

		// Look up the OUTER Wrapping(FLAT_CACHE, X) — that's what we
		// registered. Vanilla's identity-keyed wrap cache means our map
		// matches the Marker instance vanilla passes us, not its inner.
		String name = WorldgenStateBootstrap.identifiedRouterDfs().get(function);
		if (name == null) return;

		Object inner = invokeNoArg(function, "wrapped");
		if (!(inner instanceof DensityFunction innerDf)) return;

		int chunkMinBlockX = this.startCellX * this.horizontalCellBlockCount;
		int chunkMinBlockZ = this.startCellZ * this.horizontalCellBlockCount;
		cir.setReturnValue(new RustFlatCache(innerDf, name, chunkMinBlockX, chunkMinBlockZ));
	}

	private static Object invokeNoArg(Object target, String name) {
		try {
			java.lang.reflect.Method m = target.getClass().getMethod(name);
			m.setAccessible(true);
			return m.invoke(target);
		} catch (ReflectiveOperationException e) {
			return null;
		}
	}
}
