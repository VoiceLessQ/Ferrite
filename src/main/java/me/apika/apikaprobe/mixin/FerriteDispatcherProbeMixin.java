package me.apika.apikaprobe.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;

import me.apika.apikaprobe.monitor.FerriteDispatcherProbe;

import net.minecraft.server.level.ChunkTaskDispatcher;

/**
 * Captures queue-wait latency per dispatcher priority lane on
 * {@link ChunkTaskDispatcher}'s internal {@code PriorityConsecutiveExecutor
 * dispatcher}. Wraps each {@code Runnable} payload at construction time of
 * the four {@code StrictQueue.RunnableWithPriority} sites; the wrap records
 * submission-to-execution-start latency into a per-priority scope so the
 * pollTask lane can be distinguished from level-change / release / submit.
 *
 * <p>Default off via {@code FerriteDispatcherProbe.ENABLED}.  Opt in with
 * {@code -Dferrite.dispatcherProbe=true}.  Phase 1 of the chunk-pipeline
 * parallelism investigation (see LOCAL_DESIGN); the question this answers
 * is whether the dispatcher tail is actually the bottleneck.
 */
@Mixin(ChunkTaskDispatcher.class)
public abstract class FerriteDispatcherProbeMixin {

	@ModifyArg(
		method = "onLevelChange",
		at = @At(value = "INVOKE", target = "Lnet/minecraft/util/thread/StrictQueue$RunnableWithPriority;<init>(ILjava/lang/Runnable;)V"),
		index = 1
	)
	private Runnable ferrite$wrapLevelChange(Runnable inner) {
		return FerriteDispatcherProbe.wrap(inner, "dispatcher-p0-levelchange");
	}

	@ModifyArg(
		method = "release",
		at = @At(value = "INVOKE", target = "Lnet/minecraft/util/thread/StrictQueue$RunnableWithPriority;<init>(ILjava/lang/Runnable;)V"),
		index = 1
	)
	private Runnable ferrite$wrapRelease(Runnable inner) {
		return FerriteDispatcherProbe.wrap(inner, "dispatcher-p1-release");
	}

	@ModifyArg(
		method = "submit",
		at = @At(value = "INVOKE", target = "Lnet/minecraft/util/thread/StrictQueue$RunnableWithPriority;<init>(ILjava/lang/Runnable;)V"),
		index = 1
	)
	private Runnable ferrite$wrapSubmit(Runnable inner) {
		return FerriteDispatcherProbe.wrap(inner, "dispatcher-p2-submit");
	}

	@ModifyArg(
		method = "pollTask",
		at = @At(value = "INVOKE", target = "Lnet/minecraft/util/thread/StrictQueue$RunnableWithPriority;<init>(ILjava/lang/Runnable;)V"),
		index = 1
	)
	private Runnable ferrite$wrapPollTask(Runnable inner) {
		return FerriteDispatcherProbe.wrap(inner, "dispatcher-p3-polltask");
	}
}
