package me.apika.apikaprobe.monitor;

import me.apika.apikaprobe.bridge.ExampleMod;

import java.util.concurrent.atomic.AtomicLong;

import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;

/**
 * Per-chunk timing of the {@code INITIALIZE_LIGHT} and {@code LIGHT}
 * phases. Measures the actual async-work duration by attaching a
 * {@code whenComplete} listener to the {@code CompletableFuture}
 * returned by {@code ThreadedLevelLightEngine.initializeLight} /
 * {@code light}, not just the synchronous task-submission overhead.
 *
 * <p>Goal: data-driven decision on whether the {@code LIGHT} phase is
 * the next-biggest sinner in chunkgen wall-time after our existing
 * accelerators (DF, biome, surface). The 47 chunks/sec ceiling we hit
 * in worldgen Act 6 is consistent with ~21 ms per chunk total — this
 * monitor tells us how much of that 21 ms light eats.
 *
 * <p>Pure measurement; no behavior change.
 */
public final class LightTimingMonitor {
	private static final long REPORT_INTERVAL_NS = 5_000_000_000L;

	private static final AtomicLong INIT_TOTAL_NS = new AtomicLong();
	private static final AtomicLong INIT_COUNT = new AtomicLong();
	private static final AtomicLong INIT_MAX_NS = new AtomicLong();

	private static final AtomicLong LIGHT_TOTAL_NS = new AtomicLong();
	private static final AtomicLong LIGHT_COUNT = new AtomicLong();
	private static final AtomicLong LIGHT_MAX_NS = new AtomicLong();

	private static volatile long lastReportNs = System.nanoTime();

	private LightTimingMonitor() {}

	public static void register() {
		ServerTickEvents.END_SERVER_TICK.register(server -> maybeReport());
	}

	public static void recordInit(long elapsedNs) {
		INIT_TOTAL_NS.addAndGet(elapsedNs);
		INIT_COUNT.incrementAndGet();
		updateMax(INIT_MAX_NS, elapsedNs);
	}

	public static void recordLight(long elapsedNs) {
		LIGHT_TOTAL_NS.addAndGet(elapsedNs);
		LIGHT_COUNT.incrementAndGet();
		updateMax(LIGHT_MAX_NS, elapsedNs);
	}

	private static void updateMax(AtomicLong field, long candidate) {
		long prev;
		do {
			prev = field.get();
			if (candidate <= prev) return;
		} while (!field.compareAndSet(prev, candidate));
	}

	private static void maybeReport() {
		long now = System.nanoTime();
		if (now - lastReportNs < REPORT_INTERVAL_NS) return;
		lastReportNs = now;

		long initTotal = INIT_TOTAL_NS.getAndSet(0);
		long initCount = INIT_COUNT.getAndSet(0);
		long initMax = INIT_MAX_NS.getAndSet(0);

		long lightTotal = LIGHT_TOTAL_NS.getAndSet(0);
		long lightCount = LIGHT_COUNT.getAndSet(0);
		long lightMax = LIGHT_MAX_NS.getAndSet(0);

		if (initCount == 0 && lightCount == 0) return;

		if (initCount > 0) {
			double avgMs = (double) initTotal / 1_000_000.0 / (double) initCount;
			ExampleMod.LOGGER.info(
					"[chunkgen-light-init] n={} avg={}ms total={}ms max={}ms",
					initCount, String.format("%.2f", avgMs),
					initTotal / 1_000_000, initMax / 1_000_000);
		}
		if (lightCount > 0) {
			double avgMs = (double) lightTotal / 1_000_000.0 / (double) lightCount;
			ExampleMod.LOGGER.info(
					"[chunkgen-light] n={} avg={}ms total={}ms max={}ms",
					lightCount, String.format("%.2f", avgMs),
					lightTotal / 1_000_000, lightMax / 1_000_000);
		}
	}
}
