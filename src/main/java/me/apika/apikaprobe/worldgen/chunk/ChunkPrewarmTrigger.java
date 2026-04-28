package me.apika.apikaprobe.worldgen.chunk;

import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;

/**
 * Drives {@link ChunkPrewarmer} from per-server-tick player position
 * scans. For each online player, computes their current chunk and
 * schedules biome prediction for chunks within
 * {@code (viewDistance + LOOK_AHEAD)} that haven't been warmed yet.
 *
 * <p>Iteration order is concentric rings outward from the player so
 * nearby chunks (more likely to be needed soon) win priority over far
 * ones. Per-tick scheduling cap prevents flooding the prewarm pool.
 *
 * <p>Cheap when {@link ChunkPrewarmer#ENABLED} is false — early-returns
 * before any scan work.
 */
public final class ChunkPrewarmTrigger {
	private ChunkPrewarmTrigger() {}

	/** Extra rings beyond the player's view distance to keep warm.
	 *  At Rust's measured ~485 chunks/sec drain rate, 32 rings gives
	 *  ~1,408-block-per-side coverage that fills in ~16 sec stationary.
	 *  Concentric ring iteration ensures inner rings always finish first;
	 *  far rings get scheduled only as workers free up. */
	private static final int LOOK_AHEAD = 32;
	/** Per-tick scheduling budget across all players, to bound work. */
	private static final int SCHEDULE_BUDGET_PER_TICK = 32;

	public static void register() {
		ServerTickEvents.END_SERVER_TICK.register(ChunkPrewarmTrigger::onTick);
	}

	private static void onTick(net.minecraft.server.MinecraftServer server) {
		if (!ChunkPrewarmer.ENABLED) return;
		ChunkPrewarmer.start(); // idempotent
		int budget = SCHEDULE_BUDGET_PER_TICK;
		for (ServerWorld world : server.getWorlds()) {
			int viewDistance = world.getServer().getPlayerManager().getViewDistance();
			int radius = viewDistance + LOOK_AHEAD;
			for (ServerPlayerEntity player : world.getPlayers()) {
				int pcx = player.getBlockPos().getX() >> 4;
				int pcz = player.getBlockPos().getZ() >> 4;
				budget = scheduleRings(world, pcx, pcz, radius, budget);
				if (budget <= 0) return;
			}
		}
	}

	/** Walk concentric rings r=0..radius and schedule each (cx,cz).
	 *  Stops when budget is exhausted. */
	private static int scheduleRings(ServerWorld world, int pcx, int pcz, int radius, int budget) {
		for (int r = 0; r <= radius && budget > 0; r++) {
			if (r == 0) {
				if (trySchedule(world, pcx, pcz)) budget--;
				continue;
			}
			// Top + bottom edges
			for (int dx = -r; dx <= r && budget > 0; dx++) {
				if (trySchedule(world, pcx + dx, pcz - r)) budget--;
				if (budget <= 0) break;
				if (trySchedule(world, pcx + dx, pcz + r)) budget--;
			}
			// Left + right edges (excluding corners already done)
			for (int dz = -r + 1; dz <= r - 1 && budget > 0; dz++) {
				if (trySchedule(world, pcx - r, pcz + dz)) budget--;
				if (budget <= 0) break;
				if (trySchedule(world, pcx + r, pcz + dz)) budget--;
			}
		}
		return budget;
	}

	/** Skip chunks vanilla already has loaded — once a chunk is loaded,
	 *  vanilla owns the biome data and our predicted cache would just
	 *  shadow memory we don't need. Evict in case it was cached earlier. */
	private static boolean trySchedule(ServerWorld world, int cx, int cz) {
		if (world.isChunkLoaded(cx, cz)) {
			ChunkPrewarmer.evict(cx, cz);
			return false;
		}
		return ChunkPrewarmer.schedule(cx, cz);
	}
}
