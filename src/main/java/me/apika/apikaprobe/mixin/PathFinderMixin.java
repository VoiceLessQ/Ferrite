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

		if (!RustBridge.NATIVE_AVAILABLE) return;

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
		minCx--; maxCx++; minCz--; maxCz++;

		for (int cx = minCx; cx <= maxCx; cx++) {
			for (int sy = minSy; sy <= maxSy; sy++) {
				for (int cz = minCz; cz <= maxCz; cz++) {
					if (!RustBridge.navIsSectionCached(cx, sy, cz)) {
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
