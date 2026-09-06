package me.apika.apikaprobe.mixin;

import java.util.OptionalInt;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator;

import me.apika.apikaprobe.monitor.ChunkStageTiming;

// Times each point height query (getBaseHeight / getBaseColumn) that structure placement makes.
@Mixin(NoiseBasedChunkGenerator.class)
public abstract class NoiseColumnTimingMixin {

	@Inject(method = "iterateNoiseColumn", at = @At("HEAD"))
	private void ferrite$begin(CallbackInfoReturnable<OptionalInt> cir) {
		ChunkStageTiming.begin();
	}

	@Inject(method = "iterateNoiseColumn", at = @At("RETURN"))
	private void ferrite$end(CallbackInfoReturnable<OptionalInt> cir) {
		ChunkStageTiming.end("iterateNoiseColumn");
	}
}
