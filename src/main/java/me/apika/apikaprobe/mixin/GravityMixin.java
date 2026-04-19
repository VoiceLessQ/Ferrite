package me.apika.apikaprobe.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import me.apika.apikaprobe.MovementInternalsMonitor;

import net.minecraft.entity.Entity;
import net.minecraft.entity.mob.MobEntity;

/**
 * Times Entity.applyGravity() — the gravity term applied each tick.
 * Called from inside travel(); its number overlaps with travel.
 */
@Mixin(Entity.class)
public abstract class GravityMixin {

	@Inject(method = "applyGravity()V", at = @At("HEAD"))
	private void ferrite$onGravityBegin(CallbackInfo ci) {
		if (!((Object) this instanceof MobEntity)) {
			return;
		}
		MovementInternalsMonitor.onGravityBegin();
	}

	@Inject(method = "applyGravity()V", at = @At("RETURN"))
	private void ferrite$onGravityEnd(CallbackInfo ci) {
		if (!((Object) this instanceof MobEntity)) {
			return;
		}
		MovementInternalsMonitor.onGravityEnd();
	}
}
