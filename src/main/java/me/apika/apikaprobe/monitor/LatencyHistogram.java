package me.apika.apikaprobe.monitor;

import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicLongArray;

/**
 * Lock-free latency histogram for per-window monitor aggregation.
 *
 * 24 log2 buckets covering 16 ns (2^4) to 268 ms (2^28). Bucket {@code i}
 * holds samples in {@code [2^(4+i), 2^(5+i))}. Samples below 16 ns clamp to
 * bucket 0; samples at or above 268 ms clamp to the top bucket.
 *
 * One {@link #record(long)} call costs one atomic increment to a bucket plus
 * three atomic updates (count, total, max). No allocation on the hot path.
 *
 * {@link #drain()} returns a {@link Snapshot} carrying mean and p50/p95/p99
 * alongside the existing max, then resets all counters. Callers feed the
 * snapshot's {@link Snapshot#formatLine()} into their existing
 * {@code [bucket]} log line.
 *
 * Drain is intended to be called once per window from the server-tick
 * handler. Interleaving with concurrent records can leave the counts and
 * the bucket-sum off by up to a window's worth of in-flight samples; this
 * matches the existing per-monitor convention and is acceptable for
 * percentile reporting at 5 s resolution.
 */
public final class LatencyHistogram {
	public static final int BUCKETS = 24;
	private static final int LO_LOG2 = 4;
	private static final int HI_LOG2 = LO_LOG2 + BUCKETS;

	private final AtomicLongArray buckets = new AtomicLongArray(BUCKETS);
	private final AtomicLong count = new AtomicLong();
	private final AtomicLong totalNs = new AtomicLong();
	private final AtomicLong maxNs = new AtomicLong();

	// Zero/negative samples are dropped, so monitors that record per-tick
	// aggregate cost will see snapshot.count < server-tick count when some
	// ticks had no activity. The gap is a sparsity signal, not a bug.
	public void record(long durationNs) {
		if (durationNs <= 0L) return;
		count.incrementAndGet();
		totalNs.addAndGet(durationNs);
		long prev;
		do {
			prev = maxNs.get();
			if (durationNs <= prev) break;
		} while (!maxNs.compareAndSet(prev, durationNs));
		buckets.incrementAndGet(bucketOf(durationNs));
	}

	private static int bucketOf(long durationNs) {
		int log2 = 63 - Long.numberOfLeadingZeros(durationNs);
		int idx = log2 - LO_LOG2;
		if (idx < 0) return 0;
		if (idx >= BUCKETS) return BUCKETS - 1;
		return idx;
	}

	public Snapshot drain() {
		long n = count.getAndSet(0L);
		long total = totalNs.getAndSet(0L);
		long max = maxNs.getAndSet(0L);
		long[] b = new long[BUCKETS];
		long bucketSum = 0L;
		for (int i = 0; i < BUCKETS; i++) {
			b[i] = buckets.getAndSet(i, 0L);
			bucketSum += b[i];
		}
		return new Snapshot(n, total, max, b, bucketSum);
	}

	public static final class Snapshot {
		public final long count;
		public final long totalNs;
		public final long maxNs;
		private final long[] buckets;
		private final long bucketSum;

		Snapshot(long count, long totalNs, long maxNs, long[] buckets, long bucketSum) {
			this.count = count;
			this.totalNs = totalNs;
			this.maxNs = maxNs;
			this.buckets = buckets;
			this.bucketSum = bucketSum;
		}

		public boolean isEmpty() {
			return bucketSum == 0L;
		}

		public long meanNs() {
			return count == 0L ? 0L : totalNs / count;
		}

		public long percentileNs(double p) {
			if (bucketSum == 0L) return 0L;
			long target = (long) Math.ceil(p / 100.0 * bucketSum);
			if (target < 1L) target = 1L;
			long cumulative = 0L;
			for (int i = 0; i < BUCKETS; i++) {
				cumulative += buckets[i];
				if (cumulative >= target) return 1L << (LO_LOG2 + i + 1);
			}
			return 1L << HI_LOG2;
		}

		public String formatLine() {
			return String.format(
					"samples=%d mean=%.3fms p50=%.3fms p95=%.3fms p99=%.3fms max=%.3fms",
					count,
					meanNs() / 1_000_000.0,
					percentileNs(50) / 1_000_000.0,
					percentileNs(95) / 1_000_000.0,
					percentileNs(99) / 1_000_000.0,
					maxNs / 1_000_000.0);
		}
	}
}
