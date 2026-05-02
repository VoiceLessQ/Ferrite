package me.apika.apikaprobe.mixin;

import org.spongepowered.asm.mixin.Mixin;

import net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator;

/**
 * BROKEN ON 26.1.2 — needs redesign.
 *
 * <p>Targeted Yarn {@code populateNoise(Blender, StructureManager,
 * RandomState, ChunkAccess, int, int)ChunkAccess} (sync 6-arg).  Mojmap
 * 26.1.2 redesigned this to {@code fillFromNoise(Blender, RandomState,
 * StructureManager, ChunkAccess)CompletableFuture<ChunkAccess>} — 4-arg
 * async.  The chunk-gen pipeline became fully async-first.
 *
 * <p>Per CLAUDE.md, bulk-chunk-density and chunk-phase timing are both
 * default-off / closed threads.  Stubbed to keep the file in tree while
 * build moves forward; reintroduction needs a redesign against the new
 * async chunk-gen shape.
 */
@Mixin(NoiseBasedChunkGenerator.class)
public abstract class TerrainBulkHandoffMixin {
}
