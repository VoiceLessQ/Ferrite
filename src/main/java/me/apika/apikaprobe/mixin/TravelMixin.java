package me.apika.apikaprobe.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import me.apika.apikaprobe.monitor.MovementInternalsMonitor;

import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.mob.MobEntity;
import net.minecraft.util.math.Vec3d;

/**
 * Times LivingEntity.travel(Vec3d) — the velocity/input prep that
 * internally calls applyGravity() and move(). Its number overlaps
 * with the gravity and move buckets by design; see the nesting note
 * in MovementInternalsMonitor.
 */
@Mixin(LivingEntity.class)
public abstract class TravelMixin {

	@Inject(method = "travel(Lnet/minecraft/util/math/Vec3d;)V", at = @At("HEAD"))
	private void ferrite$onTravelBegin(Vec3d movementInput, CallbackInfo ci) {
		if (!((Object) this instanceof MobEntity)) {
			return;
		}
		MovementInternalsMonitor.onTravelBegin();
	}

	@Inject(method = "travel(Lnet/minecraft/util/math/Vec3d;)V", at = @At("RETURN"))
	private void ferrite$onTravelEnd(Vec3d movementInput, CallbackInfo ci) {
		if (!((Object) this instanceof MobEntity)) {
			return;
		}
		MovementInternalsMonitor.onTravelEnd();
	}
}
