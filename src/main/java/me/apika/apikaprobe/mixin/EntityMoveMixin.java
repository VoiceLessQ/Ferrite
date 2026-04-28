package me.apika.apikaprobe.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import me.apika.apikaprobe.monitor.MovementInternalsMonitor;

import net.minecraft.entity.Entity;
import net.minecraft.entity.MovementType;
import net.minecraft.entity.mob.MobEntity;
import net.minecraft.util.math.Vec3d;

/**
 * Times Entity.move(MovementType, Vec3d) — the voxel-shape sweep that
 * resolves requested motion against block collision shapes. Prime
 * suspect for the 71% "other" bucket inside tickMovement.
 *
 * Guarded to MobEntity so the measurement aligns with the other
 * movement-internals buckets (all mob-scoped).
 */
@Mixin(Entity.class)
public abstract class EntityMoveMixin {

	@Inject(method = "move(Lnet/minecraft/entity/MovementType;Lnet/minecraft/util/math/Vec3d;)V", at = @At("HEAD"))
	private void ferrite$onMoveBegin(MovementType type, Vec3d movement, CallbackInfo ci) {
		if (!((Object) this instanceof MobEntity)) {
			return;
		}
		MovementInternalsMonitor.onMoveBegin();
	}

	@Inject(method = "move(Lnet/minecraft/entity/MovementType;Lnet/minecraft/util/math/Vec3d;)V", at = @At("RETURN"))
	private void ferrite$onMoveEnd(MovementType type, Vec3d movement, CallbackInfo ci) {
		if (!((Object) this instanceof MobEntity)) {
			return;
		}
		MovementInternalsMonitor.onMoveEnd();
	}
}
