package me.apika.apikaprobe.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

import me.apika.apikaprobe.entity.PhysicsDispatcher;

import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.phys.Vec3;

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
		method = "move(Lnet.minecraft.world.entity.MoverType;Lnet.minecraft.world.phys.Vec3;)V",
		at = @At(
			value = "INVOKE",
			target = "Lnet.minecraft.world.entity.Entity;adjustMovementForCollisions(Lnet.minecraft.world.phys.Vec3;)Lnet.minecraft.world.phys.Vec3;"
		)
	)
	private Vec3 ferrite$redirectAdjust(Entity self, Vec3 motion) {
		if (!(self instanceof Mob)) {
			return ((EntityAdjustInvoker) self).ferrite$invokeAdjust(motion);
		}
		return PhysicsDispatcher.adjust(self, motion);
	}
}
