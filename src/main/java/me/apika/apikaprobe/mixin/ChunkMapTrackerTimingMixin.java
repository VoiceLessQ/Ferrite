package me.apika.apikaprobe.mixin;

import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import it.unimi.dsi.fastutil.ints.Int2ObjectMap;

import net.minecraft.server.level.ChunkMap;
import net.minecraft.server.level.ServerLevel;

import me.apika.apikaprobe.monitor.TrackerTiming;

// Times ChunkMap.tick, the per-level entity tracker pass; fires once per level tick, default off.
@Mixin(ChunkMap.class)
public abstract class ChunkMapTrackerTimingMixin {
	@Shadow @Final private Int2ObjectMap<?> entityMap;
	@Shadow @Final private ServerLevel level;

	@Inject(method = "tick()V", at = @At("HEAD"))
	private void ferrite$trackerBegin(CallbackInfo ci) {
		TrackerTiming.begin();
	}

	@Inject(method = "tick()V", at = @At("RETURN"))
	private void ferrite$trackerEnd(CallbackInfo ci) {
		if (TrackerTiming.ENABLED) TrackerTiming.end(entityMap.size(), level.players().size());
	}
}
