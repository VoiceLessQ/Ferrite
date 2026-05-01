package me.apika.apikaprobe.worldgen.chunk;

import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.level.ServerLevel;

/**
 * Per-server-tick driver for {@link ChunkForcer}. Walks concentric rings
 * around each online player up to {@code viewDist + LOOK_AHEAD} and
 * submits force-gen requests for chunks not already vanilla-loaded.
 *
 * <p>Iteration order is rings outward — chunks closest to the player
 * get queued first, far rings fill in only as workers free up.
 *
 * <p>Cheap when {@link ChunkForcer#ENABLED} is false — early-returns.
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

	public static void register() {
		ServerTickEvents.END_SERVER_TICK.register(ChunkForceTrigger::onTick);
	}

	private static void onTick(net.minecraft.server.MinecraftServer server) {
		if (!ChunkForcer.ENABLED) return;
		int budget = SCHEDULE_BUDGET_PER_TICK;
		for (ServerLevel world : server.getWorlds()) {
			int viewDistance = server.getPlayerManager().getViewDistance();
			int radius = viewDistance + LOOK_AHEAD;
			for (ServerPlayer player : world.getPlayers()) {
				int pcx = player.getBlockPos().getX() >> 4;
				int pcz = player.getBlockPos().getZ() >> 4;
				budget = scheduleRings(world, pcx, pcz, radius, budget);
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

	/** Skip chunks vanilla already has loaded — already done, and the
	 *  prewarm cache for those should also go (vanilla owns biome data
	 *  now). */
	private static boolean trySubmit(ServerLevel world, int cx, int cz) {
		if (world.isChunkLoaded(cx, cz)) {
			ChunkPrewarmer.evict(cx, cz);
			return false;
		}
		return ChunkForcer.submit(world, cx, cz);
	}
}
