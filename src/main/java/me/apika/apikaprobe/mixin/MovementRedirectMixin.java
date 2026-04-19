package me.apika.apikaprobe.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

import me.apika.apikaprobe.PhysicsDispatcher;

import net.minecraft.entity.Entity;
import net.minecraft.entity.mob.MobEntity;
import net.minecraft.util.math.Vec3d;

/**
 * Replaces the call to adjustMovementForCollisions inside Entity.move()
 * with a dispatch through PhysicsDispatcher. Non-mob entities still
 * flow straight to vanilla via the @Invoker — the measurement work
 * only identified mobs as the target.
 *
 * PhysicsDispatcher currently always falls back to vanilla. Step 5
 * flips its ENABLED flag and wires the Rust-backed batched sweep.
 */
@Mixin(Entity.class)
public abstract class MovementRedirectMixin {

	@Redirect(
		method = "move(Lnet/minecraft/entity/MovementType;Lnet/minecraft/util/math/Vec3d;)V",
		at = @At(
			value = "INVOKE",
			target = "Lnet/minecraft/entity/Entity;adjustMovementForCollisions(Lnet/minecraft/util/math/Vec3d;)Lnet/minecraft/util/math/Vec3d;"
		)
	)
	private Vec3d ferrite$redirectAdjust(Entity self, Vec3d motion) {
		if (!(self instanceof MobEntity)) {
			return ((EntityAdjustInvoker) self).ferrite$invokeAdjust(motion);
		}
		return PhysicsDispatcher.adjust(self, motion);
	}
}
