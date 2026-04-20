package me.apika.apikaprobe;

import it.unimi.dsi.fastutil.longs.Long2LongOpenHashMap;

import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;

import net.minecraft.registry.Registries;
import net.minecraft.registry.Registry;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ChunkTicketType;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.util.math.Vec3d;

/**
 * Movement-predictive chunk pre-generation.
 *
 * Every server tick, for each player with non-trivial horizontal velocity,
 * extrapolate position LOOKAHEAD_TICKS ahead and submit a ticket for the
 * target chunk via vanilla's public API. Vanilla's own scheduler runs the
 * generation on its worker pool; we just race it to submission so the
 * chunk is warm (or already FULL) by the time the player's own ticket
 * would request it.
 *
 * Phase 1 is vanilla-ticket-only — no mixin, no Rust. Monitor at
 * [PreChunkMonitor] tracks submission count, already-loaded skips, and
 * completion lead times so we can tell if we're actually ahead of vanilla
 * or just burning CPU.
 *
 * Tuning constants (see chat log 2026-04-20):
 *   LOOKAHEAD_TICKS = 60  (3s: ~6 chunks at elytra speed, ~2 at boat speed)
 *   MAX_PER_TICK    = 4   (cap so sustained travel doesn't thrash the gen pool)
 *   DEDUPE_TICKS    = 20  (don't resubmit the same chunk more than 1x/s)
 *   RADIUS          = 0   (single chunk per ticket; neighbours will get their
 *                          own submission next tick as the player advances)
 */
public final class PreChunkDispatcher {

	public static volatile boolean ENABLED = true;

	private static final int LOOKAHEAD_TICKS = 60;
	private static final int MAX_PER_TICK = 4;
	private static final int DEDUPE_TICKS = 20;
	private static final int RADIUS = 0;
	private static final double MIN_SPEED = 0.05;

	// Ticket type for our pre-load requests. Short expiry — if the player
	// doesn't reach the chunk, vanilla unloads it cleanly with no serialize
	// step. FOR_LOADING only: we want generation up to FULL status, not
	// ticking.
	private static ChunkTicketType ticketType;

	// Last submission tick per chunk pos (packed long key). Keyed globally
	// across worlds — cross-dimension ChunkPos collisions are harmless
	// because the value is only read for dedupe TTL.
	private static final Long2LongOpenHashMap LAST_SUBMIT = new Long2LongOpenHashMap();

	private PreChunkDispatcher() {}

	public static void register() {
		ticketType = Registry.register(
				Registries.TICKET_TYPE,
				Identifier.of(ExampleMod.MOD_ID, "prechunk"),
				new ChunkTicketType(80L, ChunkTicketType.FOR_LOADING));
		ServerTickEvents.END_SERVER_TICK.register(PreChunkDispatcher::onTick);
	}

	private static void onTick(MinecraftServer server) {
		if (!ENABLED) return;
		int budget = MAX_PER_TICK;
		long now = server.getTicks();

		for (ServerWorld world : server.getWorlds()) {
			for (ServerPlayerEntity player : world.getPlayers()) {
				if (budget <= 0) return;
				ChunkPos target = predict(player);
				if (target == null) continue;

				if (world.getChunkManager().isChunkLoaded(target.x, target.z)) {
					PreChunkMonitor.onAlreadyLoaded();
					continue;
				}

				long key = target.toLong();
				long last = LAST_SUBMIT.getOrDefault(key, Long.MIN_VALUE);
				if (now - last < DEDUPE_TICKS) continue;
				LAST_SUBMIT.put(key, now);

				submit(world, target, now);
				budget--;
			}
		}

		// Prevent unbounded growth: purge entries older than 10s (200 ticks).
		if (LAST_SUBMIT.size() > 1024) {
			LAST_SUBMIT.long2LongEntrySet().removeIf(e -> now - e.getLongValue() > 200L);
		}
	}

	private static ChunkPos predict(ServerPlayerEntity player) {
		Vec3d v = player.getVelocity();
		double speed = Math.hypot(v.x, v.z);
		if (speed < MIN_SPEED) return null;

		double x = player.getX() + v.x * LOOKAHEAD_TICKS;
		double z = player.getZ() + v.z * LOOKAHEAD_TICKS;
		return new ChunkPos(((int) Math.floor(x)) >> 4, ((int) Math.floor(z)) >> 4);
	}

	private static void submit(ServerWorld world, ChunkPos pos, long submitTick) {
		PreChunkMonitor.onSubmit();
		world.getChunkManager()
				.addChunkLoadingTicket(ticketType, pos, RADIUS)
				.thenAccept(ignored -> {
					long leadTicks = world.getServer().getTicks() - submitTick;
					PreChunkMonitor.onLoaded(leadTicks);
				});
	}
}
