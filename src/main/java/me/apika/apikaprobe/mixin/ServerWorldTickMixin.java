package me.apika.apikaprobe.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import me.apika.apikaprobe.monitor.WorldTickMonitor;

import net.minecraft.world.entity.Entity;
import net.minecraft.server.level.ServerLevel;

/**
 * Times {@code ServerLevel.tickEntity(Entity)} at HEAD and RETURN.
 *
 * Called once per entity per server tick. Fully qualified descriptor
 * is used even though there's only one overload, to mirror the rest
 * of the mod's Mixin style and be explicit about the target.
 */
@Mixin(ServerLevel.class)
public abstract class ServerWorldTickMixin {

	@Inject(
		method = "tickNonPassenger(Lnet/minecraft/world/entity/Entity;)V",
		at = @At("HEAD")
	)
	private void ferrite$onEntityBegin(Entity entity, CallbackInfo ci) {
		WorldTickMonitor.onEntityBegin();
	}

	@Inject(
		method = "tickNonPassenger(Lnet/minecraft/world/entity/Entity;)V",
		at = @At("RETURN")
	)
	private void ferrite$onEntityEnd(Entity entity, CallbackInfo ci) {
		WorldTickMonitor.onEntityEnd();
	}
}
