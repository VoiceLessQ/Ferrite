package me.apika.apikaprobe.monitor;

import me.apika.apikaprobe.bridge.ExampleMod;

import java.util.concurrent.atomic.AtomicLong;

import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;

/**
 * Per-chunk timing of the {@code INITIALIZE_LIGHT} and {@code LIGHT}
 * phases. Measures the actual async-work duration by attaching a
 * {@code whenComplete} listener to the {@code CompletableFuture}
 * returned by {@code ServerLightingProvider.initializeLight} /
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

	private static final AtomicLong RUN_TASKS_TOTAL_NS = new AtomicLong();
	private static final AtomicLong RUN_TASKS_COUNT = new AtomicLong();
	private static final AtomicLong RUN_TASKS_MAX_NS = new AtomicLong();

	// doUpdates split four ways: {server,client} × {block,sky}.
	// Server-vs-client is determined by whether the call is nested inside
	// ServerLightingProvider.runTasks (set via IN_SERVER_RUN_TASKS below).
	private static final AtomicLong SERVER_BLOCK_TOTAL_NS = new AtomicLong();
	private static final AtomicLong SERVER_BLOCK_COUNT = new AtomicLong();
	private static final AtomicLong SERVER_BLOCK_MAX_NS = new AtomicLong();

	private static final AtomicLong SERVER_SKY_TOTAL_NS = new AtomicLong();
	private static final AtomicLong SERVER_SKY_COUNT = new AtomicLong();
	private static final AtomicLong SERVER_SKY_MAX_NS = new AtomicLong();

	private static final AtomicLong CLIENT_BLOCK_TOTAL_NS = new AtomicLong();
	private static final AtomicLong CLIENT_BLOCK_COUNT = new AtomicLong();
	private static final AtomicLong CLIENT_BLOCK_MAX_NS = new AtomicLong();

	private static final AtomicLong CLIENT_SKY_TOTAL_NS = new AtomicLong();
	private static final AtomicLong CLIENT_SKY_COUNT = new AtomicLong();
	private static final AtomicLong CLIENT_SKY_MAX_NS = new AtomicLong();

	private static final ThreadLocal<Boolean> IN_SERVER_RUN_TASKS =
			ThreadLocal.withInitial(() -> false);

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

	public static void recordRunTasks(long elapsedNs) {
		RUN_TASKS_TOTAL_NS.addAndGet(elapsedNs);
		RUN_TASKS_COUNT.incrementAndGet();
		updateMax(RUN_TASKS_MAX_NS, elapsedNs);
	}

	public static void setInServerRunTasks(boolean inRunTasks) {
		IN_SERVER_RUN_TASKS.set(inRunTasks);
	}

	public static boolean inServerRunTasks() {
		return IN_SERVER_RUN_TASKS.get();
	}

	public static void recordDoUpdates(boolean server, boolean block, long elapsedNs) {
		AtomicLong total;
		AtomicLong count;
		AtomicLong max;
		if (server) {
			if (block) {
				total = SERVER_BLOCK_TOTAL_NS;
				count = SERVER_BLOCK_COUNT;
				max = SERVER_BLOCK_MAX_NS;
			} else {
				total = SERVER_SKY_TOTAL_NS;
				count = SERVER_SKY_COUNT;
				max = SERVER_SKY_MAX_NS;
			}
		} else {
			if (block) {
				total = CLIENT_BLOCK_TOTAL_NS;
				count = CLIENT_BLOCK_COUNT;
				max = CLIENT_BLOCK_MAX_NS;
			} else {
				total = CLIENT_SKY_TOTAL_NS;
				count = CLIENT_SKY_COUNT;
				max = CLIENT_SKY_MAX_NS;
			}
		}
		total.addAndGet(elapsedNs);
		count.incrementAndGet();
		updateMax(max, elapsedNs);
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

		long runTasksTotal = RUN_TASKS_TOTAL_NS.getAndSet(0);
		long runTasksCount = RUN_TASKS_COUNT.getAndSet(0);
		long runTasksMax = RUN_TASKS_MAX_NS.getAndSet(0);

		long sBlockTotal = SERVER_BLOCK_TOTAL_NS.getAndSet(0);
		long sBlockCount = SERVER_BLOCK_COUNT.getAndSet(0);
		long sBlockMax = SERVER_BLOCK_MAX_NS.getAndSet(0);

		long sSkyTotal = SERVER_SKY_TOTAL_NS.getAndSet(0);
		long sSkyCount = SERVER_SKY_COUNT.getAndSet(0);
		long sSkyMax = SERVER_SKY_MAX_NS.getAndSet(0);

		long cBlockTotal = CLIENT_BLOCK_TOTAL_NS.getAndSet(0);
		long cBlockCount = CLIENT_BLOCK_COUNT.getAndSet(0);
		long cBlockMax = CLIENT_BLOCK_MAX_NS.getAndSet(0);

		long cSkyTotal = CLIENT_SKY_TOTAL_NS.getAndSet(0);
		long cSkyCount = CLIENT_SKY_COUNT.getAndSet(0);
		long cSkyMax = CLIENT_SKY_MAX_NS.getAndSet(0);

		if (initCount == 0 && lightCount == 0 && runTasksCount == 0
				&& sBlockCount == 0 && sSkyCount == 0
				&& cBlockCount == 0 && cSkyCount == 0) return;

		if (initCount > 0) {
			double avgMs = (double) initTotal / 1_000_000.0 / (double) initCount;
			MonitorLog.info(
					"[chunkgen-light-init] n={} avg={}ms total={}ms max={}ms",
					initCount, String.format("%.2f", avgMs),
					initTotal / 1_000_000, initMax / 1_000_000);
		}
		if (lightCount > 0) {
			double avgMs = (double) lightTotal / 1_000_000.0 / (double) lightCount;
			MonitorLog.info(
					"[chunkgen-light] n={} avg={}ms total={}ms max={}ms",
					lightCount, String.format("%.2f", avgMs),
					lightTotal / 1_000_000, lightMax / 1_000_000);
		}
		if (runTasksCount > 0) {
			double avgMs = (double) runTasksTotal / 1_000_000.0 / (double) runTasksCount;
			MonitorLog.info(
					"[light-runtasks] n={} avg={}ms total={}ms max={}ms",
					runTasksCount, String.format("%.2f", avgMs),
					runTasksTotal / 1_000_000, runTasksMax / 1_000_000);
		}
		if (sBlockCount > 0) {
			double avgMs = (double) sBlockTotal / 1_000_000.0 / (double) sBlockCount;
			MonitorLog.info(
					"[light-doupdates-server-block] n={} avg={}ms total={}ms max={}ms",
					sBlockCount, String.format("%.2f", avgMs),
					sBlockTotal / 1_000_000, sBlockMax / 1_000_000);
		}
		if (sSkyCount > 0) {
			double avgMs = (double) sSkyTotal / 1_000_000.0 / (double) sSkyCount;
			MonitorLog.info(
					"[light-doupdates-server-sky] n={} avg={}ms total={}ms max={}ms",
					sSkyCount, String.format("%.2f", avgMs),
					sSkyTotal / 1_000_000, sSkyMax / 1_000_000);
		}
		if (cBlockCount > 0) {
			double avgMs = (double) cBlockTotal / 1_000_000.0 / (double) cBlockCount;
			MonitorLog.info(
					"[light-doupdates-client-block] n={} avg={}ms total={}ms max={}ms",
					cBlockCount, String.format("%.2f", avgMs),
					cBlockTotal / 1_000_000, cBlockMax / 1_000_000);
		}
		if (cSkyCount > 0) {
			double avgMs = (double) cSkyTotal / 1_000_000.0 / (double) cSkyCount;
			MonitorLog.info(
					"[light-doupdates-client-sky] n={} avg={}ms total={}ms max={}ms",
					cSkyCount, String.format("%.2f", avgMs),
					cSkyTotal / 1_000_000, cSkyMax / 1_000_000);
		}
	}
}
