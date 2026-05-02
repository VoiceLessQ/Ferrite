package me.apika.apikaprobe.mixin;

import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

/**
 * Exposes Entity's private adjustMovementForCollisions so
 * MovementRedirectMixin can fall back to vanilla when the Rust path
 * isn't eligible. Calling the Invoker bypasses the @Redirect site,
 * so we don't recurse.
 */
@Mixin(Entity.class)
public interface EntityAdjustInvoker {
	@Invoker("collide")
	Vec3 ferrite$invokeAdjust(Vec3 motion);
}
