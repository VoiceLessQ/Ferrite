package me.apika.apikaprobe.mixin;

import java.util.List;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import net.minecraft.world.level.levelgen.NoiseChunk;

/**
 * Accessor for NoiseChunk's private `interpolators` List.
 *
 * The list element type is NoiseChunk$DensityInterpolator — not
 * directly importable from outside package, so we return List<Object>
 * and cast each element to DensityInterpolatorAccessor at the call site.
 */
@Mixin(NoiseChunk.class)
public interface ChunkNoiseSamplerAccessor {

	@Accessor("interpolators")
	List<Object> apikaprobe$getInterpolators();
}
