package me.apika.apikaprobe.mixin;

import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import com.mojang.datafixers.util.Pair;

import it.unimi.dsi.fastutil.objects.ObjectList;
import me.apika.apikaprobe.monitor.LightUpdateMonitor;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ThreadedLevelLightEngine;

@Mixin(ThreadedLevelLightEngine.class)
public abstract class ThreadedLevelLightEngineMixin {

	@Shadow
	@Final
	private ObjectList<Pair<?, Runnable>> lightTasks;

	@Inject(method = "checkBlock", at = @At("HEAD"))
	private void ferrite$feed(BlockPos pos, CallbackInfo ci) {
		LightUpdateMonitor.onFeed();
	}

	@Inject(method = "runUpdate", at = @At("HEAD"))
	private void ferrite$updateStart(CallbackInfo ci) {
		LightUpdateMonitor.onUpdateStart(this.lightTasks.size());
	}

	@Inject(method = "runUpdate", at = @At("RETURN"))
	private void ferrite$updateEnd(CallbackInfo ci) {
		LightUpdateMonitor.onUpdateEnd();
	}
}
