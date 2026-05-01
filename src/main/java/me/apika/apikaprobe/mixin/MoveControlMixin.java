package me.apika.apikaprobe.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import me.apika.apikaprobe.monitor.MoveControlMonitor;

import net.minecraft.world.entity.ai.control.MoveControl;

@Mixin(MoveControl.class)
public abstract class MoveControlMixin {

	@Inject(method = "tick()V", at = @At("HEAD"))
	private void ferrite$onTickBegin(CallbackInfo ci) {
		MoveControlMonitor.onTickBegin();
	}

	@Inject(method = "tick()V", at = @At("RETURN"))
	private void ferrite$onTickEnd(CallbackInfo ci) {
		MoveControlMonitor.onTickEnd();
	}
}
