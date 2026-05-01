package me.apika.apikaprobe.mixin;

import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;

import net.minecraft.server.level.ChunkTaskDispatcher;
import net.minecraft.util.thread.ProcessorMailbox;

import me.apika.apikaprobe.monitor.FerriteDispatcherProbe;

/**
 * Wraps the {@link Runnable} payload of every
 * {@code TaskQueue.PrioritizedTask} constructed inside
 * {@link ChunkTaskDispatcher}'s four
 * {@code dispatcher.send(new PrioritizedTask(N, ...))} sites
 * (priority 0=updateLevel, 1=remove, 2=add, 3=pollTask) so
 * {@link FerriteDispatcherProbe} can record submission-to-execution
 * queue-wait latency.
 *
 * <p>Scope key is {@code this.executor.getName()} ("worldgen" /
 * "light") — vanilla names both schedulers' dispatchers "dispatcher",
 * which collides for diagnostics; the worker-pool name distinguishes
 * them.
 *
 * <p>No-op when {@link FerriteDispatcherProbe#ENABLED} is false.
 */
@Mixin(ChunkTaskDispatcher.class)
public abstract class FerriteDispatcherProbeMixin {

	@Shadow @Final
	private ProcessorMailbox<Runnable> executor;

	@ModifyArg(
			method = {"updateLevel", "remove", "add", "pollTask"},
			at = @At(
					value = "INVOKE",
					target = "Lnet/minecraft/util/thread/TaskQueue$PrioritizedTask;<init>(ILjava/lang/Runnable;)V"),
			index = 1)
	private Runnable ferrite$wrapDispatcherTask(Runnable inner) {
		return FerriteDispatcherProbe.wrap(inner, this.executor.getName());
	}
}
