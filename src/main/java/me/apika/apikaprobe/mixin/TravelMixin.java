package me.apika.apikaprobe.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import me.apika.apikaprobe.monitor.MovementInternalsMonitor;

import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.phys.Vec3;

/**
 * Times LivingEntity.travel(Vec3) — the velocity/input prep that
 * internally calls applyGravity() and move(). Its number overlaps
 * with the gravity and move buckets by design; see the nesting note
 * in MovementInternalsMonitor.
 */
@Mixin(LivingEntity.class)
public abstract class TravelMixin {

	@Inject(method = "travel(Lnet/minecraft/world/phys/Vec3;)V", at = @At("HEAD"))
	private void ferrite$onTravelBegin(Vec3 movementInput, CallbackInfo ci) {
		if (!((Object) this instanceof Mob)) {
			return;
		}
		MovementInternalsMonitor.onTravelBegin();
	}

	@Inject(method = "travel(Lnet/minecraft/world/phys/Vec3;)V", at = @At("RETURN"))
	private void ferrite$onTravelEnd(Vec3 movementInput, CallbackInfo ci) {
		if (!((Object) this instanceof Mob)) {
			return;
		}
		MovementInternalsMonitor.onTravelEnd();
	}
}
