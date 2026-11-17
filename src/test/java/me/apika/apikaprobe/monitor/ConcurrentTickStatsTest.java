package me.apika.apikaprobe.monitor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;

class ConcurrentTickStatsTest {

	@Test
	void concurrentRecordsKeepCategoryAndPerTypeTotals() throws Exception {
		ConcurrentTickStats<String> stats = new ConcurrentTickStats<>(2);
		int workerCount = 8;
		int recordsPerWorker = 10_000;
		CountDownLatch ready = new CountDownLatch(workerCount);
		CountDownLatch start = new CountDownLatch(1);
		ExecutorService workers = Executors.newFixedThreadPool(workerCount);
		List<Future<?>> results = new ArrayList<>();

		try {
			for (int worker = 0; worker < workerCount; worker++) {
				long duration = worker + 1L;
				results.add(workers.submit(() -> {
					ready.countDown();
					start.await();
					for (int i = 0; i < recordsPerWorker; i++) {
						stats.record(0, duration, "pig");
					}
					return null;
				}));
			}

			assertTrue(ready.await(5, TimeUnit.SECONDS));
			start.countDown();
			for (Future<?> result : results) {
				result.get(10, TimeUnit.SECONDS);
			}

			long expectedTotal = (long) recordsPerWorker * workerCount * (workerCount + 1) / 2;
			long expectedCount = (long) recordsPerWorker * workerCount;
			assertEquals(expectedTotal, stats.drainCategory(0));
			assertEquals(0L, stats.drainCategory(0));
			assertEquals(1, stats.byType().size());

			ConcurrentTickStats.PerTypeStats pigStats = stats.byType().get("pig");
			assertEquals(expectedTotal, pigStats.totalNanos.sum());
			assertEquals(expectedCount, pigStats.count.sum());
			assertEquals(workerCount, pigStats.maxNanos.get());
		} finally {
			workers.shutdownNow();
		}
	}
}
