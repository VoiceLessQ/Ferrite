package me.apika.apikaprobe.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import me.apika.apikaprobe.monitor.MonsterPhaseMonitor;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Mob;

/**
 * Times Mob.mobTick (AI goals, pathfinding, sensors).
 *
 * Only Mob instances can reach this method, so no instanceof
 * guard is required — it's the target type by construction.
 *
 * Fully qualified descriptor to be explicit about the ServerLevel param.
 */
@Mixin(Mob.class)
public abstract class MonsterMobTickMixin {

	@Inject(
		method = "customServerAiStep(Lnet/minecraft/server/level/ServerLevel;)V",
		at = @At("HEAD")
	)
	private void ferrite$onMobTickBegin(ServerLevel world, CallbackInfo ci) {
		MonsterPhaseMonitor.onMobTickBegin();
	}

	@Inject(
		method = "customServerAiStep(Lnet/minecraft/server/level/ServerLevel;)V",
		at = @At("RETURN")
	)
	private void ferrite$onMobTickEnd(ServerLevel world, CallbackInfo ci) {
		MonsterPhaseMonitor.onMobTickEnd();
	}
}
