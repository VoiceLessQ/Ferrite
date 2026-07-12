package me.apika.apikaprobe.worldgen.chunk;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.level.ServerLevel;

/**
 * Per-server-tick driver for {@link ChunkForcer}. Walks concentric rings
 * around each online player up to {@code viewDist + LOOK_AHEAD} and
 * submits force-gen requests for chunks not already vanilla-loaded.
 *
 * <p>Directional prediction (2026-07-12): the ring center is shifted
 * ahead of a moving player along their horizontal velocity, up to
 * {@value #MAX_PREDICT_CHUNKS} chunks at speed. A stationary player gets
 * the old behavior (rings centered on the player). Velocity is a
 * per-tick position delta tracked per player UUID; the map is cleared
 * when the forcer is disabled.
 *
 * <p>Iteration order is rings outward: chunks closest to the center
 * get queued first, far rings fill in only as workers free up.
 *
 * <p>Cheap when {@link ChunkForcer#ENABLED} is false: early-returns.
 */
public final class ChunkForceTrigger {
	private ChunkForceTrigger() {}

	/** Rings ahead of player to keep queued. Each forced gen takes
	 *  ~30-100ms of vanilla worker time, so we want a wider buffer than
	 *  prewarm to absorb fast-fly bursts before vanilla falls behind. */
	private static final int LOOK_AHEAD = 16;
	/** Per-tick scheduling cap. Modest because each force triggers a
	 *  full vanilla gen pipeline; flooding the queue starves workers
	 *  from their normal ticking duties. */
	private static final int SCHEDULE_BUDGET_PER_TICK = 8;
	/** Predict this many ticks ahead when projecting player velocity. */
	private static final int PREDICT_TICKS = 60;
	/** Cap on how far ahead (in chunks) the ring center may shift. */
	private static final int MAX_PREDICT_CHUNKS = 12;
	/** Below this horizontal speed (blocks/tick) treat player as idle. */
	private static final double MIN_SPEED = 0.3;

	private record LastPos(double x, double z) {}

	private static final Map<UUID, LastPos> lastPositions = new HashMap<>();

	public static void register() {
		ServerTickEvents.END_SERVER_TICK.register(ChunkForceTrigger::onTick);
	}

	private static void onTick(net.minecraft.server.MinecraftServer server) {
		if (!ChunkForcer.ENABLED) {
			if (!lastPositions.isEmpty()) {
				lastPositions.clear();
			}
			return;
		}
		int budget = SCHEDULE_BUDGET_PER_TICK;
		for (ServerLevel world : server.getAllLevels()) {
			int viewDistance = server.getPlayerList().getViewDistance();
			int radius = viewDistance + LOOK_AHEAD;
			for (ServerPlayer player : world.players()) {
				double px = player.getX();
				double pz = player.getZ();
				LastPos last = lastPositions.put(player.getUUID(), new LastPos(px, pz));

				int pcx = player.blockPosition().getX() >> 4;
				int pcz = player.blockPosition().getZ() >> 4;
				int ccx = pcx;
				int ccz = pcz;
				if (last != null) {
					double vx = px - last.x();
					double vz = pz - last.z();
					double speed = Math.sqrt(vx * vx + vz * vz);
					if (speed >= MIN_SPEED) {
						int aheadChunks = (int) Math.min(MAX_PREDICT_CHUNKS, speed * PREDICT_TICKS / 16.0);
						ccx = pcx + (int) Math.round(vx / speed * aheadChunks);
						ccz = pcz + (int) Math.round(vz / speed * aheadChunks);
					}
				}
				budget = scheduleRings(world, ccx, ccz, radius, budget);
				if (budget <= 0) return;
			}
		}
	}

	private static int scheduleRings(ServerLevel world, int pcx, int pcz, int radius, int budget) {
		for (int r = 0; r <= radius && budget > 0; r++) {
			if (r == 0) {
				if (trySubmit(world, pcx, pcz)) budget--;
				continue;
			}
			for (int dx = -r; dx <= r && budget > 0; dx++) {
				if (trySubmit(world, pcx + dx, pcz - r)) budget--;
				if (budget <= 0) break;
				if (trySubmit(world, pcx + dx, pcz + r)) budget--;
			}
			for (int dz = -r + 1; dz <= r - 1 && budget > 0; dz++) {
				if (trySubmit(world, pcx - r, pcz + dz)) budget--;
				if (budget <= 0) break;
				if (trySubmit(world, pcx + r, pcz + dz)) budget--;
			}
		}
		return budget;
	}

	/** Skip chunks vanilla already has loaded: already done, and the
	 *  prewarm cache for those should also go (vanilla owns biome data
	 *  now). */
	private static boolean trySubmit(ServerLevel world, int cx, int cz) {
		if (world.hasChunk(cx, cz)) {
			ChunkPrewarmer.evict(cx, cz);
			return false;
		}
		return ChunkForcer.submitOneShot(world, cx, cz);
	}
}
