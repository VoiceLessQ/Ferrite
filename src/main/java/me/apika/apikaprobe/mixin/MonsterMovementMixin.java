package me.apika.apikaprobe.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import me.apika.apikaprobe.MonsterPhaseMonitor;

import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.mob.MobEntity;

/**
 * Times LivingEntity.tickMovement for mobs only. tickMovement fires for
 * every LivingEntity (players, armor stands, mobs); guard with
 * instanceof MobEntity so we only sample the target population.
 *
 * Note: tickMovement internally calls MobEntity.mobTick on mobs, so this
 * measurement INCLUDES mobTick duration. The monitor reports a computed
 * "self" = movement - mobTick for pure physics cost.
 */
@Mixin(LivingEntity.class)
public abstract class MonsterMovementMixin {

	@Inject(method = "tickMovement()V", at = @At("HEAD"))
	private void ferrite$onMovementBegin(CallbackInfo ci) {
		if (!((Object) this instanceof MobEntity)) {
			return;
		}
		MonsterPhaseMonitor.onMovementBegin();
	}

	@Inject(method = "tickMovement()V", at = @At("RETURN"))
	private void ferrite$onMovementEnd(CallbackInfo ci) {
		if (!((Object) this instanceof MobEntity)) {
			return;
		}
		MonsterPhaseMonitor.onMovementEnd();
	}
}
