package me.apika.apikaprobe.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import me.apika.apikaprobe.monitor.MovementInternalsMonitor;

import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.MoverType;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.phys.Vec3;

/**
 * Times Entity.move(MoverType, Vec3) — the voxel-shape sweep that
 * resolves requested motion against block collision shapes. Prime
 * suspect for the 71% "other" bucket inside tickMovement.
 *
 * Guarded to Mob so the measurement aligns with the other
 * movement-internals buckets (all mob-scoped).
 */
@Mixin(Entity.class)
public abstract class EntityMoveMixin {

	@Inject(method = "move(Lnet.minecraft.world.entity.MoverType;Lnet.minecraft.world.phys.Vec3;)V", at = @At("HEAD"))
	private void ferrite$onMoveBegin(MoverType type, Vec3 movement, CallbackInfo ci) {
		if (!((Object) this instanceof Mob)) {
			return;
		}
		MovementInternalsMonitor.onMoveBegin();
	}

	@Inject(method = "move(Lnet.minecraft.world.entity.MoverType;Lnet.minecraft.world.phys.Vec3;)V", at = @At("RETURN"))
	private void ferrite$onMoveEnd(MoverType type, Vec3 movement, CallbackInfo ci) {
		if (!((Object) this instanceof Mob)) {
			return;
		}
		MovementInternalsMonitor.onMoveEnd();
	}
}
