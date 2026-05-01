package me.apika.apikaprobe.mixin;

import org.spongepowered.asm.mixin.Mixin;

import net.minecraft.server.level.ChunkTaskDispatcher;

/**
 * BROKEN ON 26.1.2 — needs redesign.
 *
 * <p>The original mixin wrapped {@code Runnable} payloads of every
 * {@code TaskQueue.PrioritizedTask} constructed inside
 * {@link ChunkTaskDispatcher}'s four send sites
 * (priority 0=updateLevel / 1=remove / 2=add / 3=pollTask) so the
 * per-task queue-wait latency could be sampled.
 *
 * <p>In mojmap 26.1.2 the {@code ProcessorMailbox} class no longer
 * exists; the executor field's type changed (likely {@code TracingExecutor}
 * or a similar replacement).  The {@code TaskQueue.PrioritizedTask}
 * inner class may also have been restructured.
 *
 * <p>This was a diagnostic probe (default-off via FerriteDispatcherProbe.ENABLED)
 * so the runtime impact of stubbing it is zero.  Stubbed as an empty
 * mixin so the build moves forward; reintroduce the wrapping once the
 * 26.1.2 dispatcher internals are mapped.  Per JOURNEY.md, physics-style
 * dispatchers were a CLOSED THREAD, so the priority on this fix is low.
 */
@Mixin(ChunkTaskDispatcher.class)
public abstract class FerriteDispatcherProbeMixin {
}
