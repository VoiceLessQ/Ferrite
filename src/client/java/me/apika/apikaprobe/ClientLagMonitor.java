package me.apika.apikaprobe;

import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import net.minecraft.client.Minecraft;
import net.minecraft.client.world.ClientLevel;

/**
 * Client-side lag diagnostic. Runs on the client thread (single-threaded
 * for tick events), so all fields are plain primitives — no atomics needed.
 *
 * Every 5 seconds logs:
 *   [client-lag] fps avg=X min=Y max=Z [TAG]  entities=E  chunks=C  samples=N
 *
 * Tag classification for fast log scanning:
 *   avg >= 60  →  OK
 *   avg >= 30  →  WARN
 *   avg <  30  →  LAG
 *
 * Entity count via ClientLevel.getRegularEntityCount() (method_18120).
 * Chunk count via Level.getChunkManager().getLoadedChunkCount()
 * (inherited from ChunkManager, method_14151).
 * FPS via Minecraft.getCurrentFps() (method_47599) — same number
 * the F3 overlay displays.
 */
public final class ClientLagMonitor {
	private static final Logger LOGGER = LoggerFactory.getLogger("ferrite");

	private static final long REPORT_INTERVAL_NS = 5_000_000_000L;

	private static long lastReportNs = System.nanoTime();
	private static int sampleCount;
	private static long fpsSum;
	private static int fpsMin = Integer.MAX_VALUE;
	private static int fpsMax;
	private static int lastEntities;
	private static int lastChunks;

	private ClientLagMonitor() {}

	public static void register() {
		ClientTickEvents.END_CLIENT_TICK.register(ClientLagMonitor::onTick);
	}

	private static void onTick(Minecraft client) {
		ClientLevel world = client.world;
		if (world == null) {
			// Not in a world (main menu etc). Reset window so we don't log stale data
			// once we enter a world.
			resetWindow(System.nanoTime());
			return;
		}

		int fps = client.getCurrentFps();
		sampleCount++;
		fpsSum += fps;
		if (fps < fpsMin) fpsMin = fps;
		if (fps > fpsMax) fpsMax = fps;

		lastEntities = world.getRegularEntityCount();
		lastChunks = world.getChunkManager().getLoadedChunkCount();

		long now = System.nanoTime();
		if (now - lastReportNs >= REPORT_INTERVAL_NS && sampleCount > 0) {
			int avg = (int) (fpsSum / sampleCount);
			String tag = avg >= 60 ? "OK" : avg >= 30 ? "WARN" : "LAG";

			LOGGER.info("[client-lag] fps avg={} min={} max={} [{}]  entities={}  chunks={}  samples={}",
					avg, fpsMin, fpsMax, tag, lastEntities, lastChunks, sampleCount);

			resetWindow(now);
		}
	}

	private static void resetWindow(long now) {
		sampleCount = 0;
		fpsSum = 0L;
		fpsMin = Integer.MAX_VALUE;
		fpsMax = 0;
		lastReportNs = now;
	}
}
