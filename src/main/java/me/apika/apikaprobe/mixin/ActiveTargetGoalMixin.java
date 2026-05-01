package me.apika.apikaprobe.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import me.apika.apikaprobe.monitor.TargetScanMonitor;

import net.minecraft.world.entity.ai.goal.NearestAttackableTargetGoal;

/**
 * Probes NearestAttackableTargetGoal to separate goal-evaluation frequency from
 * actual entity-scan cost.
 *
 * canStart() is called every tick by GoalSelector for every inactive goal.
 * findClosestTarget() fires only when the reciprocalChance gate passes
 * (~20% of canStart() calls with the default chance=10 setting).
 */
@Mixin(NearestAttackableTargetGoal.class)
public abstract class ActiveTargetGoalMixin {

	@Inject(method = "canStart()Z", at = @At("HEAD"))
	private void ferrite$onCanStart(CallbackInfoReturnable<Boolean> cir) {
		TargetScanMonitor.onCanStartCalled();
	}

	@Inject(method = "findClosestTarget()V", at = @At("HEAD"))
	private void ferrite$onScanBegin(CallbackInfo ci) {
		TargetScanMonitor.onScanBegin();
	}

	@Inject(method = "findClosestTarget()V", at = @At("RETURN"))
	private void ferrite$onScanEnd(CallbackInfo ci) {
		TargetScanMonitor.onScanEnd();
	}
}
