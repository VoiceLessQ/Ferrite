package me.apika.apikaprobe.entity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;

class CrammingTickStateTest {

	@Test
	void workerThreadsCannotRunTheBatchAndObserveItsCompletion() throws Exception {
		CrammingTickState<Object> state = new CrammingTickState<>();
		Object world = new Object();
		AtomicInteger batchCalls = new AtomicInteger();
		int workerCount = 8;
		CountDownLatch ready = new CountDownLatch(workerCount);
		CountDownLatch start = new CountDownLatch(1);
		ExecutorService workers = Executors.newFixedThreadPool(workerCount);
		List<Future<Boolean>> workerResults = new ArrayList<>();

		try {
			for (int i = 0; i < workerCount; i++) {
				workerResults.add(workers.submit(() -> {
					ready.countDown();
					start.await();
					return state.runOnceOnServerThread(world, 42, false, () -> {
						batchCalls.incrementAndGet();
						return true;
					});
				}));
			}

			assertTrue(ready.await(5, TimeUnit.SECONDS));
			start.countDown();
			for (Future<Boolean> result : workerResults) {
				assertFalse(result.get(5, TimeUnit.SECONDS));
			}
			assertEquals(0, batchCalls.get());
			assertFalse(state.isCompleted(world, 42));

			assertTrue(state.runOnceOnServerThread(world, 42, true, () -> {
				batchCalls.incrementAndGet();
				return true;
			}));
			assertTrue(state.isCompleted(world, 42));
			assertFalse(state.runOnceOnServerThread(world, 42, true, () -> {
				batchCalls.incrementAndGet();
				return true;
			}));
			assertEquals(1, batchCalls.get());
		} finally {
			workers.shutdownNow();
		}
	}

	@Test
	void failedBatchDoesNotSuppressVanillaForThatTick() {
		CrammingTickState<Object> state = new CrammingTickState<>();
		Object world = new Object();

		assertFalse(state.runOnceOnServerThread(world, 9, true, () -> false));
		assertFalse(state.isCompleted(world, 9));
	}
}
