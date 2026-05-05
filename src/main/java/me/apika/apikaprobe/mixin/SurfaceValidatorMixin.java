package me.apika.apikaprobe.mixin;

import org.spongepowered.asm.mixin.Mixin;

import net.minecraft.world.level.levelgen.SurfaceRules;

/**
 * BROKEN ON 26.1.2 — needs redesign.
 *
 * <p>Targeted Yarn {@code SurfaceRules.buildSurface(...)} static method
 * which has been removed from SurfaceRules in mojmap 26.1.2 — surface
 * building moved to ChunkGenerator and the dispatch shape is different.
 *
 * <p>Surface dispatcher work is a closed thread (see JOURNEY "Things
 * not to re-investigate" and PIANO_STATUS).  Stubbed to keep the file
 * in tree while the build moves forward.
 */
@Mixin(SurfaceRules.class)
public abstract class SurfaceValidatorMixin {
}
