package me.apika.apikaprobe.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import me.apika.apikaprobe.monitor.MovementInternalsMonitor;

import net.minecraft.world.entity.Mob;

/**
 * Times Mob.tickNewAi() — the full AI block inside tickMovement.
 * Contains: visibilityCache.clear, goalSelector.tick, targetSelector.tick,
 * navigation.tick (already probed separately), mobTick (excluded from
 * movement_self), moveControl.tick, lookControl.tick, jumpControl.tick.
 *
 * Reported as an informational breakdown — NOT subtracted from "other"
 * because it overlaps with the navigator bucket already in accountedTotal.
 * To estimate goal-selector + control cost: tickNewAi − navigator − mobTick.
 */
@Mixin(Mob.class)
public abstract class TickNewAiMixin {

	@Inject(method = "serverAiStep()V", at = @At("HEAD"))
	private void ferrite$onTickNewAiBegin(CallbackInfo ci) {
		MovementInternalsMonitor.onTickNewAiBegin();
	}

	@Inject(method = "serverAiStep()V", at = @At("RETURN"))
	private void ferrite$onTickNewAiEnd(CallbackInfo ci) {
		MovementInternalsMonitor.onTickNewAiEnd();
	}
}
