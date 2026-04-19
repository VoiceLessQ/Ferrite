package me.apika.apikaprobe.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import me.apika.apikaprobe.MovementInternalsMonitor;

import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.mob.MobEntity;

/**
 * Times LivingEntity.tickCramming() for mobs only — the nearby-entity
 * scan + pushAway loop that's the primary O(n×density) cost suspect.
 */
@Mixin(LivingEntity.class)
public abstract class CrammingMixin {

	@Inject(method = "tickCramming()V", at = @At("HEAD"))
	private void ferrite$onCrammingBegin(CallbackInfo ci) {
		if (!((Object) this instanceof MobEntity)) {
			return;
		}
		MovementInternalsMonitor.onCrammingBegin();
	}

	@Inject(method = "tickCramming()V", at = @At("RETURN"))
	private void ferrite$onCrammingEnd(CallbackInfo ci) {
		if (!((Object) this instanceof MobEntity)) {
			return;
		}
		MovementInternalsMonitor.onCrammingEnd();
	}
}
