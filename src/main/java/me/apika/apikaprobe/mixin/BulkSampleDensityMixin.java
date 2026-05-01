package me.apika.apikaprobe.mixin;

import org.spongepowered.asm.mixin.Mixin;

import net.minecraft.world.level.levelgen.NoiseChunk;

/**
 * BROKEN ON 26.1.2 — needs redesign.
 *
 * <p>The original Phase 2.5 step 2b mixin canceled vanilla's per-z-row
 * x per-interpolator sampleDensity loop in favor of a bulk Rust JNI fill.
 * Targeted private method {@code NoiseChunk.sampleDensity(boolean, int)}
 * and shadowed {@code NoiseChunk.DensityInterpolator} (inner class).
 *
 * <p>In mojmap 26.1.2 the {@code DensityInterpolator} inner class no
 * longer exists; the interpolation infrastructure inside NoiseChunk was
 * redesigned.  Mojang reshaped how interpolators are stored and fed.
 *
 * <p>Per CLAUDE.md, bulk-chunk-density is a CLOSED THREAD ("don't
 * re-open without a fundamentally new approach") so this mixin's feature
 * was already default-off on 1.21.11.  Stubbed here as an empty mixin to
 * keep the file in tree per the "infrastructure stays" directive while
 * the build moves forward.  A future Phase that re-opens the bulk-density
 * thread will need to rewrite this against the new NoiseChunk shape.
 */
@Mixin(NoiseChunk.class)
public abstract class BulkSampleDensityMixin {
}
