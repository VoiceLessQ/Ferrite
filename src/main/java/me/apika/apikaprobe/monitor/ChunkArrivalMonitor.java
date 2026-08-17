package me.apika.apikaprobe.monitor;

import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

/**
 * Measures chunk arrival deficit: how many chunks inside each player's
 * view distance are not loaded this tick. Zero means the server always
 * had terrain ready before the player could see the gap; a sustained
 * positive count is the server-side signature of pop-in.
 *
 * <p>This is gate 1 for the chunkforce default-on question (see
 * LOCAL_DESIGN "Chunk arrival"): fly the same route with the monitor on
 * and compare deficit curves across vanilla / chunkforce / +prewarm.
 *
 * <p>Scan cost is (2*viewDist+1)^2 hasChunk hash lookups per player per
 * tick (441 at vd=10); the scan-cost line in the report keeps us honest
 * about the observer effect.
 *
 * <p>Toggle: {@code /ferrite arrival on|off|status}. Default OFF.
 */
public final class ChunkArrivalMonitor {
	private ChunkArrivalMonitor() {}

	public static volatile boolean ENABLED = false;

	private static final long REPORT_INTERVAL_NS = 5_000_000_000L;

	// Server-thread only, no synchronization needed.
	private static long ticks = 0L;
	private static long deficitSum = 0L;
	private static long deficitMax = 0L;
	private static long ticksWithDeficit = 0L;
	private static long scanNanos = 0L;
	private static double distanceBlocks = 0.0;
	private static double lastX = Double.NaN;
	private static double lastZ = Double.NaN;
	private static long lastReportNanos = System.nanoTime();

	public static void register() {
		ServerTickEvents.END_SERVER_TICK.register(ChunkArrivalMonitor::onTick);
	}

	public static void reset() {
		ticks = 0L;
		deficitSum = 0L;
		deficitMax = 0L;
		ticksWithDeficit = 0L;
		scanNanos = 0L;
		distanceBlocks = 0.0;
		lastX = Double.NaN;
		lastZ = Double.NaN;
		lastReportNanos = System.nanoTime();
	}

	private static void onTick(MinecraftServer server) {
		if (!ENABLED) return;
		long start = System.nanoTime();
		int viewDistance = server.getPlayerList().getViewDistance();
		long deficit = 0L;
		for (ServerLevel world : server.getAllLevels()) {
			for (ServerPlayer player : world.players()) {
				int pcx = player.blockPosition().getX() >> 4;
				int pcz = player.blockPosition().getZ() >> 4;
				for (int dx = -viewDistance; dx <= viewDistance; dx++) {
					for (int dz = -viewDistance; dz <= viewDistance; dz++) {
						if (!world.hasChunk(pcx + dx, pcz + dz)) deficit++;
					}
				}
				// Distance tracking follows the first player only; the
				// A/B protocol is a solo flight, so that is the pilot.
				if (Double.isNaN(lastX)) {
					lastX = player.getX();
					lastZ = player.getZ();
				} else {
					double ddx = player.getX() - lastX;
					double ddz = player.getZ() - lastZ;
					distanceBlocks += Math.sqrt(ddx * ddx + ddz * ddz);
					lastX = player.getX();
					lastZ = player.getZ();
				}
			}
		}
		long now = System.nanoTime();
		ticks++;
		deficitSum += deficit;
		if (deficit > deficitMax) deficitMax = deficit;
		if (deficit > 0) ticksWithDeficit++;
		scanNanos += now - start;

		if (now - lastReportNanos >= REPORT_INTERVAL_NS && ticks > 0L) {
			MonitorLog.info("[chunk-arrival] deficit avg={} max={} ticksWith={}/{} dist={} m scan avg={} us",
					String.format("%.1f", deficitSum / (double) ticks),
					deficitMax,
					ticksWithDeficit,
					ticks,
					String.format("%.0f", distanceBlocks),
					String.format("%.1f", (scanNanos / (double) ticks) / 1_000.0));
			ticks = 0L;
			deficitSum = 0L;
			deficitMax = 0L;
			ticksWithDeficit = 0L;
			scanNanos = 0L;
			distanceBlocks = 0.0;
			lastReportNanos = now;
		}
	}
}
