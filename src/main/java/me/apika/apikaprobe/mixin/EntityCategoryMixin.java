package me.apika.apikaprobe.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import me.apika.apikaprobe.monitor.EntityTickMonitor;

import net.minecraft.entity.Entity;
import net.minecraft.server.world.ServerWorld;

/**
 * Hooks ServerWorld.tickEntity(Entity) HEAD+RETURN to bucket per-entity
 * tick cost by category in EntityTickMonitor. Runs alongside
 * ServerWorldTickMixin — both target the same method, Mixin supports
 * multiple injections on one target. Separate class keeps the category
 * profile independent of the total-cost monitor.
 */
@Mixin(ServerWorld.class)
public abstract class EntityCategoryMixin {

	@Inject(
		method = "tickEntity(Lnet/minecraft/entity/Entity;)V",
		at = @At("HEAD")
	)
	private void ferrite$onEntityTickBegin(Entity entity, CallbackInfo ci) {
		EntityTickMonitor.onEntityTickBegin(entity);
	}

	@Inject(
		method = "tickEntity(Lnet/minecraft/entity/Entity;)V",
		at = @At("RETURN")
	)
	private void ferrite$onEntityTickEnd(Entity entity, CallbackInfo ci) {
		EntityTickMonitor.onEntityTickEnd();
	}
}
