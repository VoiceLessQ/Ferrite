package me.apika.apikaprobe.mixin;

import java.util.Set;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import me.apika.apikaprobe.RustBridge;
import me.apika.apikaprobe.monitor.NavigationMonitor;
import me.apika.apikaprobe.navigation.NavigationCacheBridge;

import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.navigation.GroundPathNavigation;
import net.minecraft.world.level.PathNavigationRegion;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.pathfinder.Path;
import net.minecraft.world.level.pathfinder.PathFinder;

@Mixin(PathFinder.class)
public abstract class PathFinderMixin {

	@Inject(
		method = "findPath(Lnet/minecraft/world/level/PathNavigationRegion;Lnet/minecraft/world/entity/Mob;Ljava/util/Set;FIF)Lnet/minecraft/world/level/pathfinder/Path;",
		at = @At("HEAD")
	)
	private void ferrite$onFindPathBegin(
		PathNavigationRegion level, Mob entity, Set<BlockPos> targets,
		float maxPathLength, int reachRange, float maxVisitedNodesMultiplier,
		CallbackInfoReturnable<Path> cir
	) {
		NavigationMonitor.onFindPathBegin();

		if (!NavigationCacheBridge.WALK_CACHE_ENABLED || !RustBridge.NATIVE_AVAILABLE) return;

		// Lazy fill: snapshot all sections in the bounding box of
		// (entity position, targets), expanded by ±1 chunk in X/Z.
		BlockPos origin = entity.blockPosition();
		int minCx = origin.getX() >> 4, maxCx = minCx;
		int minSy = origin.getY() >> 4, maxSy = minSy;
		int minCz = origin.getZ() >> 4, maxCz = minCz;
		for (BlockPos t : targets) {
			int tx = t.getX() >> 4, ty = t.getY() >> 4, tz = t.getZ() >> 4;
			if (tx < minCx) minCx = tx; if (tx > maxCx) maxCx = tx;
			if (ty < minSy) minSy = ty; if (ty > maxSy) maxSy = ty;
			if (tz < minCz) minCz = tz; if (tz > maxCz) maxCz = tz;
		}
		minCx--; maxCx++; minSy--; maxSy++; minCz--; maxCz++;

		// Clamp to chunks the region actually backs. Outside its array (and
		// for getChunkNow misses inside it) PathNavigationRegion serves
		// EmptyLevelChunk = all AIR; snapshotting those would cache permanent
		// wrong-AIR sections that outlive this region.
		PathNavigationRegionAccessor region = (PathNavigationRegionAccessor) level;
		ChunkAccess[][] chunks = region.ferrite$chunks();
		int regionMinCx = region.ferrite$centerX();
		int regionMinCz = region.ferrite$centerZ();
		int regionMaxCx = regionMinCx + chunks.length - 1;
		int regionMaxCz = regionMinCz + (chunks.length > 0 ? chunks[0].length : 0) - 1;
		if (minCx < regionMinCx) minCx = regionMinCx;
		if (maxCx > regionMaxCx) maxCx = regionMaxCx;
		if (minCz < regionMinCz) minCz = regionMinCz;
		if (maxCz > regionMaxCz) maxCz = regionMaxCz;

		// Clamp Y to real world sections; outside build height is air by
		// definition and not worth a 16 KB section.
		int minWorldSy = level.getMinY() >> 4;
		int maxWorldSy = (level.getMinY() + level.getHeight() - 1) >> 4;
		if (minSy < minWorldSy) minSy = minWorldSy;
		if (maxSy > maxWorldSy) maxSy = maxWorldSy;

		for (int cx = minCx; cx <= maxCx; cx++) {
			for (int cz = minCz; cz <= maxCz; cz++) {
				// getChunkNow miss: region would serve EmptyLevelChunk here.
				if (chunks[cx - regionMinCx][cz - regionMinCz] == null) continue;
				for (int sy = minSy; sy <= maxSy; sy++) {
					// Gate on the Java kind cache - the store the hot path
					// reads. See NavigationCacheBridge.hasJavaSection.
					if (!NavigationCacheBridge.hasJavaSection(cx, sy, cz)) {
						NavigationCacheBridge.snapshotSection(cx, sy, cz, level);
					}
				}
			}
		}
	}

	@Inject(
		method = "findPath(Lnet/minecraft/world/level/PathNavigationRegion;Lnet/minecraft/world/entity/Mob;Ljava/util/Set;FIF)Lnet/minecraft/world/level/pathfinder/Path;",
		at = @At("RETURN")
	)
	private void ferrite$onFindPathEnd(
		PathNavigationRegion level, Mob entity, Set<BlockPos> targets,
		float maxPathLength, int reachRange, float maxVisitedNodesMultiplier,
		CallbackInfoReturnable<Path> cir
	) {
		NavigationMonitor.onFindPathEnd(entity, cir.getReturnValue());
		if (entity.getNavigation() instanceof GroundPathNavigation) {
			NavigationCacheBridge.checkPathParity(cir.getReturnValue());
		}
	}
}
