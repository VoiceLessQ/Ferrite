package me.apika.apikaprobe.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import me.apika.apikaprobe.monitor.MobSpawnMonitor;

@Mixin(targets = "net.minecraft.world.level.NaturalSpawner$SpawnState")
public abstract class NaturalSpawnerSpawnStateMixin {

	@Inject(method = "afterSpawn", at = @At("HEAD"))
	private void ferrite$onSpawn(CallbackInfo ci) {
		MobSpawnMonitor.onSpawn();
	}
}
