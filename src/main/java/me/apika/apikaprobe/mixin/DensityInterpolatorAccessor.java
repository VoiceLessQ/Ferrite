package me.apika.apikaprobe.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import net.minecraft.world.gen.densityfunction.DensityFunction;

/**
 * Accessor for the package-private nested ChunkNoiseSampler$DensityInterpolator.
 * Targeted via string form because the inner class isn't conveniently
 * importable.
 *
 * Exposes the two 2D corner-density buffers vanilla uses for interpolation
 * and the underlying `delegate` density function that produced them.
 */
@Mixin(targets = "net.minecraft.world.gen.chunk.ChunkNoiseSampler$DensityInterpolator")
public interface DensityInterpolatorAccessor {

	@Accessor("startDensityBuffer")
	double[][] apikaprobe$getStartDensityBuffer();

	@Accessor("endDensityBuffer")
	double[][] apikaprobe$getEndDensityBuffer();

	@Accessor("delegate")
	DensityFunction apikaprobe$getDelegate();
}
