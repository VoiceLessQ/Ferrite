package me.apika.apikaprobe;

import me.apika.apikaprobe.bridge.ExampleMod;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

import net.minecraft.registry.Registries;
import net.minecraft.registry.Registry;
import net.minecraft.server.world.ChunkTicketType;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.ChunkPos;

/**
 * Background chunk pre-generation via vanilla's public ticket API.
 *
 * <p>For each scheduled (cx,cz), submits an
 * {@code addChunkLoadingTicket} request — vanilla queues the chunk on
 * its own chunk-load executor, runs the full status pipeline (noise,
 * biome, surface, decoration, light), and writes it to .mca on next
 * auto-save. Persistence is automatic; a forced chunk survives a server
 * restart even if the player never visits.
 *
 * <p>Compounds with {@link ChunkPrewarmer}: prewarm provides ~99% biome
 * cache hits during the forced gen, so each chunk's biome step is a
 * memory read instead of fresh DF computation. On completion the
 * prediction cache for that chunk is evicted — vanilla now owns the
 * authoritative data and our prediction would just hog memory.
 *
 * <p>Toggle: {@code /ferrite chunkforce on|off}. Default OFF.
 */
public final class ChunkForcer {
	private ChunkForcer() {}

	public static volatile boolean ENABLED = false;

	private static ChunkTicketType ticketType;

	/** Tracks chunks we've submitted that haven't completed yet. */
	private static final ConcurrentHashMap<Long, Boolean> inflight = new ConcurrentHashMap<>();

	private static final AtomicLong scheduled = new AtomicLong();
	private static final AtomicLong completed = new AtomicLong();
	private static final AtomicLong errored = new AtomicLong();

	/** In-flight cap. 50 mirrors established pre-gen tooling defaults —
	 *  beyond that, vanilla's chunk-load executor just queues and the
	 *  extra inflight tracking burns memory with no throughput gain. */
	private static final int MAX_INFLIGHT = 50;

	public static void register() {
		ticketType = Registry.register(
				Registries.TICKET_TYPE,
				Identifier.of(ExampleMod.MOD_ID, "chunkforce"),
				new ChunkTicketType(80L, ChunkTicketType.FOR_LOADING));
	}

	/** Submit a force-gen request. Caller must already be on server thread.
	 *  Returns true if newly queued; false if disabled, capped, already
	 *  inflight, or registration didn't run. */
	public static boolean submit(ServerWorld world, int cx, int cz) {
		if (!ENABLED) return false;
		if (ticketType == null) return false;
		if (inflight.size() >= MAX_INFLIGHT) return false;
		long key = ChunkPrewarmer.key(cx, cz);
		if (inflight.putIfAbsent(key, Boolean.TRUE) != null) return false;
		scheduled.incrementAndGet();
		ChunkPos pos = new ChunkPos(cx, cz);
		try {
			world.getChunkManager()
					.addChunkLoadingTicket(ticketType, pos, 0)
					.whenComplete((res, err) -> {
						inflight.remove(key);
						if (err != null) errored.incrementAndGet();
						else completed.incrementAndGet();
						// Vanilla now owns this chunk's biome data —
						// prewarm prediction is dead weight.
						ChunkPrewarmer.evict(cx, cz);
					});
			return true;
		} catch (Throwable t) {
			inflight.remove(key);
			errored.incrementAndGet();
			return false;
		}
	}

	public static int inflightCount() { return inflight.size(); }
	public static long scheduledCount() { return scheduled.get(); }
	public static long completedCount() { return completed.get(); }
	public static long erroredCount() { return errored.get(); }

	public static void resetStats() {
		scheduled.set(0);
		completed.set(0);
		errored.set(0);
	}
}
