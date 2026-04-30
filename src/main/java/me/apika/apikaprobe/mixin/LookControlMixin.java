package me.apika.apikaprobe.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import me.apika.apikaprobe.monitor.LookControlMonitor;

import net.minecraft.entity.ai.control.LookControl;

@Mixin(LookControl.class)
public abstract class LookControlMixin {

	@Inject(method = "tick()V", at = @At("HEAD"))
	private void ferrite$onTickBegin(CallbackInfo ci) {
		LookControlMonitor.onTickBegin();
	}

	@Inject(method = "tick()V", at = @At("RETURN"))
	private void ferrite$onTickEnd(CallbackInfo ci) {
		LookControlMonitor.onTickEnd();
	}
}
