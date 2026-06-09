package me.apika.apikaprobe.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import net.minecraft.world.level.PathNavigationRegion;
import net.minecraft.world.level.chunk.ChunkAccess;

/**
 * Exposes the region's chunk backing so the section snapshot in
 * PathFinderMixin can clamp to real chunks. Out-of-range positions and
 * getChunkNow misses both resolve to EmptyLevelChunk (all AIR) inside
 * PathNavigationRegion.getBlockState; snapshotting those would cache
 * permanent wrong-AIR sections that outlive the region.
 */
@Mixin(PathNavigationRegion.class)
public interface PathNavigationRegionAccessor {

	@Accessor("centerX")
	int ferrite$centerX();

	@Accessor("centerZ")
	int ferrite$centerZ();

	@Accessor("chunks")
	ChunkAccess[][] ferrite$chunks();
}
