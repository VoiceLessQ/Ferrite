package me.apika.apikaprobe.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import me.apika.apikaprobe.monitor.MovementInternalsMonitor;

import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.phys.Vec3;

/**
 * Times Entity.adjustMovementForCollisions(Vec3) — the pure-geometry
 * voxel-shape sweep called once per move() invocation. This is the
 * actual Rust port target (isolating geometry from move()'s side effects).
 *
 * Targets the 1-arg instance overload; the 3-arg and 5-arg static
 * helpers are internal delegates, not separately measured.
 */
@Mixin(Entity.class)
public abstract class AdjustCollisionsMixin {

	@Inject(method = "adjustMovementForCollisions(Lnet/minecraft/world/phys/Vec3;)Lnet/minecraft/world/phys/Vec3;", at = @At("HEAD"))
	private void ferrite$onAdjustBegin(Vec3 movement, CallbackInfoReturnable<Vec3> cir) {
		if (!((Object) this instanceof Mob)) {
			return;
		}
		MovementInternalsMonitor.onAdjustCollisionsBegin();
	}

	@Inject(method = "adjustMovementForCollisions(Lnet/minecraft/world/phys/Vec3;)Lnet/minecraft/world/phys/Vec3;", at = @At("RETURN"))
	private void ferrite$onAdjustEnd(Vec3 movement, CallbackInfoReturnable<Vec3> cir) {
		if (!((Object) this instanceof Mob)) {
			return;
		}
		MovementInternalsMonitor.onAdjustCollisionsEnd();
	}
}
