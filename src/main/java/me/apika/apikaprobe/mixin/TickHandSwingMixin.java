package me.apika.apikaprobe.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import me.apika.apikaprobe.monitor.MovementInternalsMonitor;

import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;

/**
 * Times LivingEntity.tickHandSwing() for hostile mobs.
 * HostileEntity.tickMovement() calls tickHandSwing() before super.tickMovement(),
 * so this fires inside the tickMovement window and contributes to movement_self.
 * Players call it from Player.tickMovement(); the Mob guard excludes them.
 */
@Mixin(LivingEntity.class)
public abstract class TickHandSwingMixin {

	@Inject(method = "tickHandSwing()V", at = @At("HEAD"))
	private void ferrite$onHandSwingBegin(CallbackInfo ci) {
		if (!((Object) this instanceof Mob)) {
			return;
		}
		MovementInternalsMonitor.onHandSwingBegin();
	}

	@Inject(method = "tickHandSwing()V", at = @At("RETURN"))
	private void ferrite$onHandSwingEnd(CallbackInfo ci) {
		if (!((Object) this instanceof Mob)) {
			return;
		}
		MovementInternalsMonitor.onHandSwingEnd();
	}
}
