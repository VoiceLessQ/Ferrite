package me.apika.apikaprobe.worldgen.chunk;

import java.util.HashMap;
import java.util.Iterator;
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
 * when the trigger goes idle.
 *
 * <p>Speed-gated auto mode (2026-08-17): with {@link ChunkForcer#AUTO}
 * on (the default), forcing engages per player once their smoothed
 * horizontal speed crosses {@value #ENGAGE_SPEED} blocks/tick and
 * disengages below {@value #DISENGAGE_SPEED} (hysteresis so the gate
 * does not flap at the threshold). The A/B behind the thresholds: at
 * ~4.4 blocks/tick vanilla runs a sustained 43-159 missing-chunk
 * deficit inside view distance, chunkforce holds it at zero; at
 * ~1.7 blocks/tick (rocket-elytra cruise) the deficit is zero either
 * way, so slow players engage nothing and cost nothing. Teleport jumps
 * (delta over {@value #TELEPORT_CUTOFF} blocks/tick) reset tracking
 * instead of polluting the speed estimate. Manual
 * {@code /ferrite chunkforce on} still forces for everyone regardless
 * of speed.
 *
 * <p>Iteration order is rings outward: chunks closest to the center
 * get queued first, far rings fill in only as workers free up.
 *
 * <p>Cheap when idle: with manual off and no fast player, per tick this
 * is one map walk over online players doing subtraction.
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
	/** Auto mode engages at this smoothed speed (2.0 b/t = 40 blocks/s). */
	private static final double ENGAGE_SPEED = 2.0;
	/** Auto mode disengages below this smoothed speed. */
	private static final double DISENGAGE_SPEED = 1.5;
	/** EMA smoothing factor; ~0.1 needs a couple seconds of sustained
	 *  speed to engage, so knockback and short hops stay below the gate. */
	private static final double EMA_ALPHA = 0.1;
	/** Per-tick delta above this is a teleport, not movement. */
	private static final double TELEPORT_CUTOFF = 16.0;

	private static final class Tracked {
		double x;
		double z;
		double emaSpeed;
		boolean engaged;

		Tracked(double x, double z) {
			this.x = x;
			this.z = z;
		}
	}

	private static final Map<UUID, Tracked> tracked = new HashMap<>();
	private static long lastTickSeen = 0L;

	public static void register() {
		ServerTickEvents.END_SERVER_TICK.register(ChunkForceTrigger::onTick);
	}

	private static void onTick(net.minecraft.server.MinecraftServer server) {
		boolean manual = ChunkForcer.ENABLED;
		boolean auto = ChunkForcer.AUTO;
		if (!manual && !auto) {
			if (!tracked.isEmpty()) tracked.clear();
			ChunkForcer.autoActive = false;
			return;
		}
		boolean anyEngaged = false;
		int budget = SCHEDULE_BUDGET_PER_TICK;
		for (ServerLevel world : server.getAllLevels()) {
			int viewDistance = server.getPlayerList().getViewDistance();
			int radius = viewDistance + LOOK_AHEAD;
			for (ServerPlayer player : world.players()) {
				double px = player.getX();
				double pz = player.getZ();
				Tracked t = tracked.get(player.getUUID());
				double vx = 0.0;
				double vz = 0.0;
				double speed = 0.0;
				if (t == null) {
					t = new Tracked(px, pz);
					tracked.put(player.getUUID(), t);
				} else {
					vx = px - t.x;
					vz = pz - t.z;
					speed = Math.sqrt(vx * vx + vz * vz);
					if (speed > TELEPORT_CUTOFF) {
						// Teleport: restart tracking at the new spot.
						speed = 0.0;
						vx = 0.0;
						vz = 0.0;
						t.emaSpeed = 0.0;
						t.engaged = false;
					}
					t.x = px;
					t.z = pz;
					t.emaSpeed += EMA_ALPHA * (speed - t.emaSpeed);
				}
				if (t.engaged) {
					if (t.emaSpeed < DISENGAGE_SPEED) t.engaged = false;
				} else {
					if (t.emaSpeed >= ENGAGE_SPEED) t.engaged = true;
				}
				boolean force = manual || (auto && t.engaged);
				if (t.engaged) anyEngaged = true;
				if (!force || budget <= 0) continue;

				int pcx = player.blockPosition().getX() >> 4;
				int pcz = player.blockPosition().getZ() >> 4;
				int ccx = pcx;
				int ccz = pcz;
				if (speed >= MIN_SPEED) {
					int aheadChunks = (int) Math.min(MAX_PREDICT_CHUNKS, speed * PREDICT_TICKS / 16.0);
					ccx = pcx + (int) Math.round(vx / speed * aheadChunks);
					ccz = pcz + (int) Math.round(vz / speed * aheadChunks);
				}
				// autoActive must be open before submits for the gate in
				// submitOneShot to pass on this tick.
				ChunkForcer.autoActive = true;
				budget = scheduleRings(world, ccx, ccz, radius, budget);
			}
		}
		ChunkForcer.autoActive = manual || (auto && anyEngaged);
		pruneStale(server);
	}

	/** Drop tracking for players no longer online (cheap, runs rarely). */
	private static void pruneStale(net.minecraft.server.MinecraftServer server) {
		if (++lastTickSeen % 200 != 0) return;
		if (tracked.isEmpty()) return;
		Iterator<UUID> it = tracked.keySet().iterator();
		while (it.hasNext()) {
			if (server.getPlayerList().getPlayer(it.next()) == null) it.remove();
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
