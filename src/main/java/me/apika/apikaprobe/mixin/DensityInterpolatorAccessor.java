package me.apika.apikaprobe.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import net.minecraft.world.level.levelgen.DensityFunction;

/**
 * BROKEN ON 26.1.2 — needs redesign.
 *
 * <p>Targeted Yarn {@code NoiseChunk$DensityInterpolator} which mojmap
 * renamed to {@code NoiseChunk$NoiseInterpolator} with renamed fields
 * (slice0/slice1 instead of startDensityBuffer/endDensityBuffer, no
 * delegate field).  The mixin's @Mixin target now resolves to a missing
 * class so Mixin will skip applying it (warning only, not crash).
 *
 * <p>Method signatures retained so callers in {@code BulkInterpolatorFill}
 * (referenced from the now-stubbed {@code BulkSampleDensityMixin}) still
 * compile.  Those methods will never actually be invoked because the
 * BulkSampleDensityMixin's hot-path is stubbed and the accessor's target
 * doesn't apply.  Future redesign should retarget {@code NoiseChunk$NoiseInterpolator}
 * and use {@code slice0}/{@code slice1}/{@code noiseFiller} field names.
 */
@Mixin(targets = "net.minecraft.world.level.levelgen.NoiseChunk$DensityInterpolator")
public interface DensityInterpolatorAccessor {

	@Accessor("startDensityBuffer")
	double[][] apikaprobe$getStartDensityBuffer();

	@Accessor("endDensityBuffer")
	double[][] apikaprobe$getEndDensityBuffer();

	@Accessor("delegate")
	DensityFunction apikaprobe$getDelegate();
}
