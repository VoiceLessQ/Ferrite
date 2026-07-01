package me.apika.apikaprobe.mixin;

import java.util.Set;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

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
		// Session 6: the pre-fill box is gone. It snapshotted every section
		// in the (origin, targets) bounding box up front; long paths spanned
		// hundreds of sections, boxes collided in the 512-slot direct-mapped
		// cache, and most sections were evicted before serving a lookup
		// (hit rate 1-17%, ~4400 snapshots per 5 s). Sections now fill
		// lazily on first miss inside the WalkNodeEvaluator intercept; see
		// NavigationCacheBridge.lazySnapshot.
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
