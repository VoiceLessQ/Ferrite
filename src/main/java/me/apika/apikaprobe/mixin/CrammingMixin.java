package me.apika.apikaprobe.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import me.apika.apikaprobe.monitor.MovementInternalsMonitor;

import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;

/**
 * Times LivingEntity.tickCramming() for mobs only — the nearby-entity
 * scan + pushAway loop that's the primary O(n×density) cost suspect.
 */
@Mixin(LivingEntity.class)
public abstract class CrammingMixin {

	@Inject(method = "pushEntities()V", at = @At("HEAD"))
	private void ferrite$onCrammingBegin(CallbackInfo ci) {
		if (!((Object) this instanceof Mob)) {
			return;
		}
		MovementInternalsMonitor.onCrammingBegin();
	}

	@Inject(method = "pushEntities()V", at = @At("RETURN"))
	private void ferrite$onCrammingEnd(CallbackInfo ci) {
		if (!((Object) this instanceof Mob)) {
			return;
		}
		MovementInternalsMonitor.onCrammingEnd();
	}
}
