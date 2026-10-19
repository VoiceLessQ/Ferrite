package me.apika.apikaprobe.monitor;

import java.util.concurrent.atomic.AtomicLong;

import net.minecraft.server.MinecraftServer;

import me.apika.apikaprobe.monitor.FerriteDispatcherProbe.Stats;

// Wall time of ChunkMap.tick (entity tracker walk plus packet builds) per level tick; default off.
public final class TrackerTiming {

	private TrackerTiming() {}

	public static volatile boolean ENABLED = Boolean.parseBoolean(
			System.getProperty("ferrite.trackerProbe", "false"));

	private static volatile Stats stats = new Stats();
	private static final AtomicLong entitySum = new AtomicLong();
	private static final AtomicLong playerSum = new AtomicLong();
	private static volatile int startServerTick = -1;
	private static final ThreadLocal<long[]> START = ThreadLocal.withInitial(() -> new long[1]);

	public static void begin() {
		if (!ENABLED) return;
		START.get()[0] = System.nanoTime();
	}

	public static void end(int trackedEntities, int players) {
		if (!ENABLED) return;
		long[] start = START.get();
		if (start[0] == 0) return;
		stats.record(System.nanoTime() - start[0]);
		start[0] = 0;
		entitySum.addAndGet(trackedEntities);
		playerSum.addAndGet(players);
	}

	public static void reset(MinecraftServer server) {
		stats = new Stats();
		entitySum.set(0);
		playerSum.set(0);
		startServerTick = server.getTickCount();
	}

	public static String report(MinecraftServer server) {
		Stats s = stats;
		long n = s.count.get();
		if (n == 0 || startServerTick < 0) {
			return String.format("[ferrite/tracker-probe] enabled=%s no window yet (run /ferrite probe tracker reset)", ENABLED);
		}
		int ticks = Math.max(1, server.getTickCount() - startServerTick);
		double perTickMs = s.sumNanos.get() / (double) ticks / 1_000_000.0;
		double avgTickMs = server.getAverageTickTimeNanos() / 1_000_000.0;
		return String.format(
				"[ferrite/tracker-probe] enabled=%s server-ticks=%d calls=%d tracker=%.3fms/tick"
				+ " mean=%.1fus p99=%.1fus max=%.1fus entities=%.0f players=%.1f"
				+ " share=%.1f%% of avg tick %.2fms (last 100 ticks)",
				ENABLED, ticks, n, perTickMs,
				s.sumNanos.get() / (double) n / 1_000.0,
				s.percentileNanos(0.99) / 1_000.0,
				s.maxNanos.get() / 1_000.0,
				entitySum.get() / (double) n,
				playerSum.get() / (double) n,
				avgTickMs == 0 ? 0.0 : 100.0 * perTickMs / avgTickMs, avgTickMs);
	}
}
