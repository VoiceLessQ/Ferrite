package me.apika.apikaprobe;

import java.util.HashMap;
import java.util.UUID;

import it.unimi.dsi.fastutil.longs.Long2LongOpenHashMap;

import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;

import net.minecraft.registry.Registries;
import net.minecraft.registry.Registry;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ChunkTicketType;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.ChunkPos;

/**
 * Movement-predictive chunk pre-generation.
 *
 * Every server tick, for each player with non-trivial horizontal speed,
 * extrapolate position along the movement vector to land just outside
 * vanilla's view-distance radius, and submit a ticket for that chunk via
 * vanilla's public API. Vanilla's scheduler runs the generation on its
 * worker pool; we just race it to submission so the chunk is warm by
 * the time the player's own ticket requests it.
 *
 * Velocity is derived from per-tick position deltas, not
 * `Entity.getVelocity()` — player motion is client-authoritative and the
 * server's velocity field stays near-zero in practice.
 *
 * Self-tuning: lookahead distance is `(viewDistance + 3) * 16` blocks,
 * pulled from the server's current player view distance. Lookahead
 * *ticks* are then that distance divided by current speed, capped at
 * MAX_LOOKAHEAD_TICKS so nearly-idle players don't extrapolate hundreds
 * of blocks out along a stale direction.
 *
 * Phase 1 is vanilla-ticket-only — no mixin, no Rust. Monitor at
 * [PreChunkMonitor] tracks submission count, inside-view-dist skips, and
 * completion lead times.
 */
public final class PreChunkDispatcher {

	public static volatile boolean ENABLED = false;

	private static final int MAX_PER_TICK = 4;
	private static final int DEDUPE_TICKS = 20;
	private static final int RADIUS = 0;
	private static final int MAX_LOOKAHEAD_TICKS = 200;
	private static final int VIEW_DISTANCE_MARGIN = 16;
	private static final double MIN_SPEED = 0.05;

	private static ChunkTicketType ticketType;

	private static final Long2LongOpenHashMap LAST_SUBMIT = new Long2LongOpenHashMap();
	private static final HashMap<UUID, double[]> LAST_POS = new HashMap<>();

	private PreChunkDispatcher() {}

	public static void register() {
		ticketType = Registry.register(
				Registries.TICKET_TYPE,
				Identifier.of(ExampleMod.MOD_ID, "prechunk"),
				new ChunkTicketType(80L, ChunkTicketType.FOR_LOADING));
		ServerTickEvents.END_SERVER_TICK.register(PreChunkDispatcher::onTick);
		ServerPlayConnectionEvents.DISCONNECT.register((handler, server) ->
				LAST_POS.remove(handler.getPlayer().getUuid()));
	}

	static int currentViewDistance(MinecraftServer server) {
		return server.getPlayerManager().getViewDistance();
	}

	private static void onTick(MinecraftServer server) {
		if (!ENABLED) return;
		int budget = MAX_PER_TICK;
		long now = server.getTicks();
		int viewDistance = currentViewDistance(server);
		double targetBlocks = (viewDistance + VIEW_DISTANCE_MARGIN) * 16.0;
		PreChunkMonitor.recordViewDistance(viewDistance);

		for (ServerWorld world : server.getWorlds()) {
			for (ServerPlayerEntity player : world.getPlayers()) {
				if (budget <= 0) return;
				ChunkPos target = predict(player, targetBlocks);
				if (target == null) continue;

				long key = target.toLong();
				// Sentinel 0L is safe: `now` is a server tick count ≥ 0, so
				// `now - 0 == now`, which exceeds DEDUPE_TICKS once the server
				// has been running for more than one second. Long.MIN_VALUE
				// would underflow the subtraction and make the check always
				// true — blocking every first submission.
				long last = LAST_SUBMIT.getOrDefault(key, 0L);
				if (last != 0L && now - last < DEDUPE_TICKS) {
					PreChunkMonitor.onDedupeSkipped();
					continue;
				}
				LAST_SUBMIT.put(key, now);

				submit(world, target, now);
				budget--;
			}
		}

		if (LAST_SUBMIT.size() > 1024) {
			LAST_SUBMIT.long2LongEntrySet().removeIf(e -> now - e.getLongValue() > 200L);
		}
	}

	private static ChunkPos predict(ServerPlayerEntity player, double targetBlocks) {
		UUID id = player.getUuid();
		double cx = player.getX();
		double cz = player.getZ();
		double[] last = LAST_POS.put(id, new double[]{cx, cz});

		if (last == null) return null;

		double dx = cx - last[0];
		double dz = cz - last[1];
		double speed = Math.hypot(dx, dz);
		if (speed < MIN_SPEED) return null;

		double lookahead = Math.min(targetBlocks / speed, MAX_LOOKAHEAD_TICKS);
		double x = cx + dx * lookahead;
		double z = cz + dz * lookahead;
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
