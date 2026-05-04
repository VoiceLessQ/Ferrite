package me.apika.apikaprobe.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import net.minecraft.world.chunk.light.ChunkBlockLightProvider;
import net.minecraft.world.chunk.light.ChunkLightProvider;

import me.apika.apikaprobe.monitor.LightTimingMonitor;

/**
 * Times {@code ChunkLightProvider.doLightUpdates}, which is the
 * steady-state BFS drain triggered by {@code checkBlock} edits
 * (e.g. {@code /fill}, piston pushes, breakage). Splits the sample
 * by engine via runtime instanceof, since neither concrete subclass
 * overrides the method on the parent.
 */
@Mixin(ChunkLightProvider.class)
public abstract class LightDoUpdatesMixin {

	@Unique
	private static final ThreadLocal<long[]> ferrite$start =
			ThreadLocal.withInitial(() -> new long[1]);

	@Inject(method = "doLightUpdates", at = @At("HEAD"))
	private void ferrite$head(CallbackInfoReturnable<Integer> cir) {
		ferrite$start.get()[0] = System.nanoTime();
	}

	@Inject(method = "doLightUpdates", at = @At("RETURN"))
	private void ferrite$ret(CallbackInfoReturnable<Integer> cir) {
		long startNs = ferrite$start.get()[0];
		ferrite$start.get()[0] = 0L;
		if (startNs == 0L) return;
		long elapsed = System.nanoTime() - startNs;
		boolean server = LightTimingMonitor.inServerRunTasks();
		boolean block = (Object) this instanceof ChunkBlockLightProvider;
		LightTimingMonitor.recordDoUpdates(server, block, elapsed);
	}
}
