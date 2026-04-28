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

import me.apika.apikaprobe.worldgen.BulkChunkDensityFill;
import me.apika.apikaprobe.worldgen.DensityFunctionWalker;
import me.apika.apikaprobe.ExampleMod;
import me.apika.apikaprobe.worldgen.RustFinalDensityBufferWrapper;
import me.apika.apikaprobe.worldgen.WorldgenStateBootstrap;

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
		// IMPORTANT: do NOT remove "CacheAllInCell" thinking it's dead code.
		// 1.21.11 yarn-decompiled source shows Wrapping.Type as a plain
		// enum with constants UPPER_SNAKE (CACHE_ALL_IN_CELL etc.) and NO
		// toString override — by Java spec, default Enum.toString() should
		// return name() = "CACHE_ALL_IN_CELL". HOWEVER, empirical runtime
		// observation (one-shot diagnostic, see git history) shows
		// typeObj.toString() returning "CacheAllInCell" (CamelCase) for
		// every Wrapping.Type encountered during chunkgen. Source/runtime
		// drift, possibly from yarn's tiny mappings being newer than the
		// published sources jar. The CamelCase form is THE actual runtime
		// value; "CACHE_ALL_IN_CELL" + "cache_all_in_cell" are defensive
		// fallbacks for future yarn drift. Removing CamelCase = 0
		// substitutions, breaking the mixin entirely.
		boolean isCellCache = "CacheAllInCell".equals(typeName)        // actual runtime
				|| "CACHE_ALL_IN_CELL".equals(typeName)                 // theoretical Java enum default
				|| "cache_all_in_cell".equals(typeName);                // asString form, defensive
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
