package me.apika.apikaprobe.monitor;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicLongArray;

/**
 * Ferrite step-0 probe — measures submission-to-execution latency on
 * {@link net.minecraft.server.world.ChunkTaskScheduler}'s
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
 * <p>Scoped per {@code TaskExecutor.getName()} on the scheduler's worker
 * pool ("worldgen" / "light"), so the two ChunkTaskScheduler instances
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
