package me.apika.apikaprobe.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import me.apika.apikaprobe.navigation.NavigationCacheBridge;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.pathfinder.PathType;
import net.minecraft.world.level.pathfinder.WalkNodeEvaluator;

@Mixin(WalkNodeEvaluator.class)
public abstract class WalkNodeEvaluatorMixin {

	@Inject(
		method = "getPathTypeFromState(Lnet/minecraft/world/level/BlockGetter;Lnet/minecraft/core/BlockPos;)Lnet/minecraft/world/level/pathfinder/PathType;",
		at = @At("HEAD"),
		cancellable = true
	)
	private static void ferrite$pathTypeFromCache(
		BlockGetter level, BlockPos pos,
		CallbackInfoReturnable<PathType> cir
	) {
		if (!NavigationCacheBridge.WALK_CACHE_ENABLED) return;
		PathType pt = NavigationCacheBridge.cachedPathTypeAt(pos.getX(), pos.getY(), pos.getZ());
		if (pt != null) cir.setReturnValue(pt);
	}
}
