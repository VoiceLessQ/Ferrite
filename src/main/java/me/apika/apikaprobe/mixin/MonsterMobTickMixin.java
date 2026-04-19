package me.apika.apikaprobe.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import me.apika.apikaprobe.MonsterPhaseMonitor;

import net.minecraft.server.world.ServerWorld;
import net.minecraft.entity.mob.MobEntity;

/**
 * Times MobEntity.mobTick (AI goals, pathfinding, sensors).
 *
 * Only MobEntity instances can reach this method, so no instanceof
 * guard is required — it's the target type by construction.
 *
 * Fully qualified descriptor to be explicit about the ServerWorld param.
 */
@Mixin(MobEntity.class)
public abstract class MonsterMobTickMixin {

	@Inject(
		method = "mobTick(Lnet/minecraft/server/world/ServerWorld;)V",
		at = @At("HEAD")
	)
	private void ferrite$onMobTickBegin(ServerWorld world, CallbackInfo ci) {
		MonsterPhaseMonitor.onMobTickBegin();
	}

	@Inject(
		method = "mobTick(Lnet/minecraft/server/world/ServerWorld;)V",
		at = @At("RETURN")
	)
	private void ferrite$onMobTickEnd(ServerWorld world, CallbackInfo ci) {
		MonsterPhaseMonitor.onMobTickEnd();
	}
}
