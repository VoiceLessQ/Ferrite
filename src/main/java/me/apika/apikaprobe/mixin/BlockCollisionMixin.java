package me.apika.apikaprobe.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import me.apika.apikaprobe.monitor.MovementInternalsMonitor;

import net.minecraft.entity.Entity;
import net.minecraft.entity.mob.MobEntity;

/**
 * Times Entity.tickBlockCollision() for mobs only — the per-tick
 * block-vs-entity collision update. Fully qualified descriptor pins
 * the no-arg overload (a 2-arg variant also exists inside the
 * collision resolver).
 */
@Mixin(Entity.class)
public abstract class BlockCollisionMixin {

	@Inject(method = "tickBlockCollision()V", at = @At("HEAD"))
	private void ferrite$onBlockCollisionBegin(CallbackInfo ci) {
		if (!((Object) this instanceof MobEntity)) {
			return;
		}
		MovementInternalsMonitor.onBlockCollisionBegin();
	}

	@Inject(method = "tickBlockCollision()V", at = @At("RETURN"))
	private void ferrite$onBlockCollisionEnd(CallbackInfo ci) {
		if (!((Object) this instanceof MobEntity)) {
			return;
		}
		MovementInternalsMonitor.onBlockCollisionEnd();
	}
}
