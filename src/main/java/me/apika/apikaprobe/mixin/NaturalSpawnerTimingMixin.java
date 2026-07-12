package me.apika.apikaprobe.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import me.apika.apikaprobe.monitor.MobSpawnMonitor;

import net.minecraft.world.level.NaturalSpawner;

@Mixin(NaturalSpawner.class)
public abstract class NaturalSpawnerTimingMixin {

	@Inject(method = "createState", at = @At("HEAD"))
	private static void ferrite$censusStart(CallbackInfoReturnable<NaturalSpawner.SpawnState> cir) {
		MobSpawnMonitor.onCensusStart();
	}

	@Inject(method = "createState", at = @At("RETURN"))
	private static void ferrite$censusEnd(CallbackInfoReturnable<NaturalSpawner.SpawnState> cir) {
		MobSpawnMonitor.onCensusEnd();
	}

	@Inject(method = "spawnCategoryForChunk", at = @At("HEAD"))
	private static void ferrite$attemptStart(CallbackInfo ci) {
		MobSpawnMonitor.onAttemptStart();
	}

	@Inject(method = "spawnCategoryForChunk", at = @At("RETURN"))
	private static void ferrite$attemptEnd(CallbackInfo ci) {
		MobSpawnMonitor.onAttemptEnd();
	}
}
