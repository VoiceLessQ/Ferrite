package me.apika.apikaprobe.mixin;

import org.spongepowered.asm.mixin.Mixin;

import net.minecraft.world.level.levelgen.NoiseChunk;

/**
 * BROKEN ON 26.1.2 — needs redesign.
 * Targeted Yarn sampleStartDensity() (gone in mojmap) and used
 * DensityInterpolatorAccessor (whose target inner class was renamed
 * NoiseChunk$DensityInterpolator -> NoiseChunk$NoiseInterpolator).
 * Was diagnostic-only.  Stubbed pending redesign.
 */
@Mixin(NoiseChunk.class)
public abstract class InterpolatorDiagnosticMixin {
}
