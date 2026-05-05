package me.apika.apikaprobe.monitor;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicLongArray;

import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;

/**
 * Ferrite step-0 probe — measures submission-to-execution latency on
 * {@link net.minecraft.server.level.ChunkTaskDispatcher}'s
 * {@code PrioritizedConsecutiveExecutor dispatcher}. Used to decide
 * whether the dispatcher is actually saturated under sustained chunkgen
 * before any parallelism work is built on top of it.
 *
 * <p>Reading 1.21.11 yarn source directly:
 * {@link net.minecraft.util.thread.ConsecutiveExecutor} is a
 * single-threaded sequential drain of its
 * {@link net.minecraft.util.thread.TaskQueue}. The four priority levels
 * exposed by {@code PrioritizedConsecutiveExecutor(4, ..., "dispatcher")}
 * are ordering, not parallelism. If queue-wait p99 is sub-millisecond
 * during chunkgen, the gate is not the bottleneck — parallelism work
 * should target a different layer.
 *
 * <p>Scoped per {@code ProcessorMailbox.getName()} on the scheduler's worker
 * pool ("worldgen" / "light"), so the two ChunkTaskDispatcher instances
 * are reported separately even though they both name their dispatcher
 * "dispatcher" in vanilla.
 *
 * <p>Default off — opt in via {@code -Dferrite.dispatcherProbe=true}.
 */
public final class FerriteDispatcherProbe {

	private FerriteDispatcherProbe() {}

	public static volatile boolean ENABLED = Boolean.parseBoolean(
			System.getProperty("ferrite.dispatcherProbe", "false"));

	private static final ConcurrentHashMap<String, Stats> byScope = new ConcurrentHashMap<>();

	private static final long REPORT_INTERVAL_NS = 5_000_000_000L;
	private static volatile long lastReportNs = System.nanoTime();

	/** Wires a 5-second periodic logger that emits {@link #diagSummary()}
	 *  through {@link MonitorLog} and resets the per-scope buckets so each
	 *  log line reflects the most recent 5-second window. No-op when
	 *  {@link #ENABLED} is false; flip the flag at runtime via
	 *  {@code /ferrite probe dispatcher on} or boot with
	 *  {@code -Dferrite.dispatcherProbe=true}. */
	public static void register() {
		ServerTickEvents.END_SERVER_TICK.register(server -> {
			if (!ENABLED) return;
			long now = System.nanoTime();
			if (now - lastReportNs < REPORT_INTERVAL_NS) return;
			lastReportNs = now;
			if (byScope.isEmpty()) return;
			MonitorLog.info(diagSummary());
			byScope.clear();
		});
	}

	/** Wraps the inner runnable submitted to a dispatcher so that its
	 *  queue-wait time (submission → execution start) is recorded
	 *  against the named scope. No-op when disabled. */
	public static Runnable wrap(Runnable inner, String scope) {
		if (!ENABLED || inner == null) return inner;
		final long submitNanos = System.nanoTime();
		final String scopeKey = scope != null ? scope : "unknown";
		return () -> {
			long waitNanos = System.nanoTime() - submitNanos;
			byScope.computeIfAbsent(scopeKey, k -> new Stats()).record(waitNanos);
			inner.run();
		};
	}

	/** Records a task's run duration (execution-start → execution-end)
	 *  against the named scope. Complements {@link #wrap}'s queue-wait
	 *  measurement: queue-wait answers "did this task pile up?" while
	 *  duration answers "did this task take long once it ran?" Both
	 *  signals together separate "tasks dispatch fast but execute slow"
	 *  from "tasks pile up at the dispatcher." No-op when disabled. */
	public static void recordTaskDuration(String scope, long durationNanos) {
		if (!ENABLED) return;
		String scopeKey = scope != null ? scope : "unknown";
		byScope.computeIfAbsent(scopeKey, k -> new Stats()).record(durationNanos);
	}

	public static String diagSummary() {
		if (byScope.isEmpty()) {
			return String.format("[ferrite/dispatcher-probe] enabled=%s no samples yet", ENABLED);
		}
		StringBuilder sb = new StringBuilder();
		sb.append(String.format("[ferrite/dispatcher-probe] enabled=%s", ENABLED));
		List<Map.Entry<String, Stats>> entries = new ArrayList<>(byScope.entrySet());
		entries.sort(Map.Entry.comparingByKey());
		for (Map.Entry<String, Stats> e : entries) {
			Stats s = e.getValue();
			long count = s.count.get();
			if (count == 0) continue;
			long sumNs = s.sumNanos.get();
			long maxNs = s.maxNanos.get();
			long meanNs = sumNs / count;
			long p50 = s.percentileNanos(0.50);
			long p99 = s.percentileNanos(0.99);
			long p999 = s.percentileNanos(0.999);
			sb.append(String.format(
					" | %s n=%d mean=%s p50=%s p99=%s p999=%s max=%s",
					e.getKey(), count,
					fmt(meanNs), fmt(p50), fmt(p99), fmt(p999), fmt(maxNs)));
		}
		return sb.toString();
	}

	public static void resetDiag() {
		byScope.clear();
	}

	private static String fmt(long nanos) {
		if (nanos < 1_000) return nanos + "ns";
		if (nanos < 1_000_000) return String.format("%.1fus", nanos / 1_000.0);
		if (nanos < 1_000_000_000L) return String.format("%.2fms", nanos / 1_000_000.0);
		return String.format("%.2fs", nanos / 1_000_000_000.0);
	}

	/** Power-of-two log buckets covering 1ns..~9.2e18ns. Lock-free. */
	private static final class Stats {
		static final int BUCKETS = 64;
		final AtomicLong count = new AtomicLong();
		final AtomicLong sumNanos = new AtomicLong();
		final AtomicLong maxNanos = new AtomicLong();
		final AtomicLongArray buckets = new AtomicLongArray(BUCKETS);

		void record(long nanos) {
			if (nanos < 0) nanos = 0;
			count.incrementAndGet();
			sumNanos.addAndGet(nanos);
			long prev;
			do {
				prev = maxNanos.get();
				if (nanos <= prev) break;
			} while (!maxNanos.compareAndSet(prev, nanos));
			int b = nanos == 0 ? 0 : Math.min(BUCKETS - 1, 63 - Long.numberOfLeadingZeros(nanos));
			buckets.incrementAndGet(b);
		}

		long percentileNanos(double p) {
			long total = count.get();
			if (total == 0) return 0;
			long target = (long) Math.ceil(p * total);
			long cumulative = 0;
			for (int b = 0; b < BUCKETS; b++) {
				cumulative += buckets.get(b);
				if (cumulative >= target) {
					long lo = b == 0 ? 0L : (1L << b);
					long hi = b == BUCKETS - 1 ? lo : (1L << (b + 1));
					return (lo + hi) / 2L;
				}
			}
			return maxNanos.get();
		}
	}
}
