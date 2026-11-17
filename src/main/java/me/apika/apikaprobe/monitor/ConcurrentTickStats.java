package me.apika.apikaprobe.monitor;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicLongArray;
import java.util.concurrent.atomic.LongAdder;

/** Shared accumulation used by entity tick monitors that run on workers. */
final class ConcurrentTickStats<K> {
	private final AtomicLongArray categoryNanos;
	private final ConcurrentHashMap<K, PerTypeStats> byType = new ConcurrentHashMap<>();

	ConcurrentTickStats(int categoryCount) {
		categoryNanos = new AtomicLongArray(categoryCount);
	}

	void record(int category, long durationNanos, K type) {
		categoryNanos.addAndGet(category, durationNanos);
		if (type != null) {
			byType.computeIfAbsent(type, ignored -> new PerTypeStats()).record(durationNanos);
		}
	}

	long drainCategory(int category) {
		return categoryNanos.getAndSet(category, 0L);
	}

	ConcurrentHashMap<K, PerTypeStats> byType() {
		return byType;
	}

	static final class PerTypeStats {
		final LongAdder totalNanos = new LongAdder();
		final AtomicLong maxNanos = new AtomicLong();
		final LongAdder count = new LongAdder();

		void record(long durationNanos) {
			totalNanos.add(durationNanos);
			maxNanos.accumulateAndGet(durationNanos, Math::max);
			count.increment();
		}
	}
}
