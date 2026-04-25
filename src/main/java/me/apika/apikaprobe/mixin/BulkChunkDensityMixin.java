package me.apika.apikaprobe.mixin;

import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.lang.reflect.Method;

import net.minecraft.world.gen.chunk.ChunkNoiseSampler;
import net.minecraft.world.gen.densityfunction.DensityFunction;

import me.apika.apikaprobe.BulkChunkDensityFill;
import me.apika.apikaprobe.DensityFunctionWalker;
import me.apika.apikaprobe.ExampleMod;
import me.apika.apikaprobe.RustFinalDensityBufferWrapper;
import me.apika.apikaprobe.WorldgenStateBootstrap;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Phase 2.5 step 4 — substitute vanilla's outer
 * {@code CellCache(Add(finalDensity, beardifier))} with
 * {@link RustFinalDensityBufferWrapper}.
 *
 * <p>Hook: {@code getActualDensityFunctionImpl} HEAD cancellable.
 * Recognizes the synthetic outer CACHE_ALL_IN_CELL Wrapping by
 * fingerprinting its wrapped subtree against the
 * {@code ferrite:synthetic/full_noise_density} entry registered at
 * world load. Only matches that specific synthetic — other
 * CACHE_ALL_IN_CELL markers (if any exist nested in the tree) pass
 * through to vanilla's CellCache unchanged.
 *
 * <p>Gated by {@link BulkChunkDensityFill#ENABLED}; when off, mixin
 * is a no-op. Composes safely with other
 * {@code getActualDensityFunctionImpl} mixins (CacheRouteCaptureMixin,
 * the dirty FlatCache substitution); each runs HEAD cancellable but
 * only fires for its own pattern.
 */
@Mixin(ChunkNoiseSampler.class)
public abstract class BulkChunkDensityMixin {

	private static final AtomicBoolean firstFireLogged = new AtomicBoolean();

	@Shadow @Final
	private int startCellX;

	@Shadow @Final
	private int startCellZ;

	@Shadow @Final
	int horizontalCellBlockCount;

	@Inject(
			method = "getActualDensityFunctionImpl",
			at = @At("HEAD"),
			cancellable = true
	)
	private void ferrite$swapFullNoiseDensity(DensityFunction function,
			CallbackInfoReturnable<DensityFunction> cir) {
		BulkChunkDensityFill.mixinFires.incrementAndGet();
		if (firstFireLogged.compareAndSet(false, true)) {
			ExampleMod.LOGGER.info(
					"[bulk-chunk-density] mixin first fire: input class={}, ENABLED={}",
					function.getClass().getName(), BulkChunkDensityFill.ENABLED);
		}
		if (!BulkChunkDensityFill.ENABLED) return;

		String cls = function.getClass().getSimpleName();
		if (!cls.equals("Wrapping") && !cls.endsWith("$Wrapping")) return;
		BulkChunkDensityFill.wrappingSeen.incrementAndGet();

		Object typeObj = invokeNoArg(function, "type");
		if (typeObj == null) return;
		BulkChunkDensityFill.typeAccessOk.incrementAndGet();
		String typeName = typeObj.toString();
		// One-shot log of distinct typeName values to settle what
		// yarn's Wrapping.Type.toString returns. The dirty FlatCache
		// mixin's "FLAT_CACHE".equals comparison was never verified
		// to actually fire (its identity-lookup gate also bailed).
		BulkChunkDensityFill.recordTypeNameOnce(typeName);
		// Yarn 1.21.11 overrides Wrapping.Type.toString to return camelCase
		// (verified via one-shot diag: "Cache2D", "FlatCache", "CacheOnce",
		// "Interpolated", "CacheAllInCell"). Underlying field name is
		// CACHE_ALL_IN_CELL but that's not what reflective toString gives us.
		boolean isCellCache = "CacheAllInCell".equals(typeName)
				|| "CACHE_ALL_IN_CELL".equals(typeName)  // defensive: future yarn drift
				|| "cache_all_in_cell".equals(typeName);
		if (!isCellCache) {
			BulkChunkDensityFill.nonCellCacheSeen.incrementAndGet();
			return;
		}
		BulkChunkDensityFill.cellCacheTypeSeen.incrementAndGet();

		Object inner = invokeNoArg(function, "wrapped");
		if (!(inner instanceof DensityFunction innerDf)) return;
		BulkChunkDensityFill.fingerprintAttempted.incrementAndGet();
		String fp = DensityFunctionWalker.fingerprint(innerDf);
		if (fp == null) return;
		String registered = WorldgenStateBootstrap.fingerprintToName().get(fp);
		if (!"ferrite:synthetic/full_noise_density".equals(registered)) {
			BulkChunkDensityFill.fingerprintMisses.incrementAndGet();
			return;
		}

		int chunkMinBlockX = this.startCellX * this.horizontalCellBlockCount;
		int chunkMinBlockZ = this.startCellZ * this.horizontalCellBlockCount;
		cir.setReturnValue(new RustFinalDensityBufferWrapper(
				function, chunkMinBlockX, chunkMinBlockZ));
		BulkChunkDensityFill.substitutions.incrementAndGet();
	}

	private static Object invokeNoArg(Object target, String name) {
		try {
			Method m = target.getClass().getMethod(name);
			return m.invoke(target);
		} catch (ReflectiveOperationException e) {
			return null;
		}
	}
}
