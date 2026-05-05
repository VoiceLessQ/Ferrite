package me.apika.apikaprobe.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

import me.apika.apikaprobe.monitor.FerriteDispatcherProbe;

import net.minecraft.util.Util;
import net.minecraft.util.thread.AbstractConsecutiveExecutor;

/**
 * Captures per-task wall-time on every {@link AbstractConsecutiveExecutor}
 * by intercepting the {@code Util.runNamed} call inside {@code pollTask}.
 * Scoped by the executor's {@code name} field (passed as the second arg
 * to {@code runNamed}), so the worldgen and light SCEs are reported
 * separately and the dispatcher's internal SCE is filtered out (its
 * queue-wait is already covered by {@link FerriteDispatcherProbeMixin}).
 *
 * <p>Default off via {@code FerriteDispatcherProbe.ENABLED}.  Phase 1 of
 * the chunk-pipeline parallelism investigation: complements the dispatcher
 * queue-wait probe with execution-side wall time so "tasks dispatch fast
 * but execute slow" is distinguishable from "tasks pile up at the
 * dispatcher."
 */
@Mixin(AbstractConsecutiveExecutor.class)
public abstract class AbstractConsecutiveExecutorDurationMixin {

	@Redirect(
		method = "pollTask",
		at = @At(value = "INVOKE", target = "Lnet/minecraft/util/Util;runNamed(Ljava/lang/Runnable;Ljava/lang/String;)V")
	)
	private void ferrite$timeRun(Runnable runnable, String name) {
		if (!FerriteDispatcherProbe.ENABLED) {
			Util.runNamed(runnable, name);
			return;
		}
		// Skip the dispatcher's internal SCE; its queue-wait is already
		// captured by FerriteDispatcherProbeMixin. Worldgen and light are
		// the SCEs we want execution-side timing for.
		if (!"worldgen".equals(name) && !"light".equals(name)) {
			Util.runNamed(runnable, name);
			return;
		}
		long startNs = System.nanoTime();
		try {
			Util.runNamed(runnable, name);
		} finally {
			long durNs = System.nanoTime() - startNs;
			FerriteDispatcherProbe.recordTaskDuration(name + "-task-duration", durNs);
		}
	}
}
