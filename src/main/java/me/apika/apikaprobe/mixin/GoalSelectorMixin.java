package me.apika.apikaprobe.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import me.apika.apikaprobe.monitor.GoalSelectorMonitor;

import net.minecraft.world.entity.ai.goal.GoalSelector;

/**
 * Times GoalSelector.tick() and GoalSelector.tickGoals(boolean).
 * Covers both goalSelector and targetSelector fields — both are GoalSelector.
 * tick() internally calls tickGoals(true), so timing is nested:
 *   tick total = goalCleanup + goalUpdate + tickGoals(true)
 */
@Mixin(GoalSelector.class)
public abstract class GoalSelectorMixin {

	@Inject(method = "tick()V", at = @At("HEAD"))
	private void ferrite$tickHead(CallbackInfo ci) {
		GoalSelectorMonitor.onTickBegin();
	}

	@Inject(method = "tick()V", at = @At("RETURN"))
	private void ferrite$tickReturn(CallbackInfo ci) {
		GoalSelectorMonitor.onTickEnd();
	}

	@Inject(method = "tickGoals(Z)V", at = @At("HEAD"))
	private void ferrite$tickGoalsHead(boolean tickAll, CallbackInfo ci) {
		GoalSelectorMonitor.onTickGoalsBegin(tickAll);
	}

	@Inject(method = "tickGoals(Z)V", at = @At("RETURN"))
	private void ferrite$tickGoalsReturn(boolean tickAll, CallbackInfo ci) {
		GoalSelectorMonitor.onTickGoalsEnd(tickAll);
	}
}
