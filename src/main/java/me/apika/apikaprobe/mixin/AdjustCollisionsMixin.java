package me.apika.apikaprobe.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import me.apika.apikaprobe.monitor.MovementInternalsMonitor;

import net.minecraft.entity.Entity;
import net.minecraft.entity.mob.MobEntity;
import net.minecraft.util.math.Vec3d;

/**
 * Times Entity.adjustMovementForCollisions(Vec3d) — the pure-geometry
 * voxel-shape sweep called once per move() invocation. This is the
 * actual Rust port target (isolating geometry from move()'s side effects).
 *
 * Targets the 1-arg instance overload; the 3-arg and 5-arg static
 * helpers are internal delegates, not separately measured.
 */
@Mixin(Entity.class)
public abstract class AdjustCollisionsMixin {

	@Inject(method = "adjustMovementForCollisions(Lnet/minecraft/util/math/Vec3d;)Lnet/minecraft/util/math/Vec3d;", at = @At("HEAD"))
	private void ferrite$onAdjustBegin(Vec3d movement, CallbackInfoReturnable<Vec3d> cir) {
		if (!((Object) this instanceof MobEntity)) {
			return;
		}
		MovementInternalsMonitor.onAdjustCollisionsBegin();
	}

	@Inject(method = "adjustMovementForCollisions(Lnet/minecraft/util/math/Vec3d;)Lnet/minecraft/util/math/Vec3d;", at = @At("RETURN"))
	private void ferrite$onAdjustEnd(Vec3d movement, CallbackInfoReturnable<Vec3d> cir) {
		if (!((Object) this instanceof MobEntity)) {
			return;
		}
		MovementInternalsMonitor.onAdjustCollisionsEnd();
	}
}
