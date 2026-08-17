package me.apika.apikaprobe.monitor;

import me.apika.apikaprobe.bridge.ExampleMod;

import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

import java.util.UUID;

/**
 * Automated flight for the chunk-arrival A/B: teleports the requesting
 * player forward along their look heading at a fixed speed, one step
 * per server tick, with the arrival monitor running. No manual flying,
 * so runs are exactly repeatable (same speed, same straight line).
 *
 * <p>Start via {@code /ferrite arrival bench <blocksPerSec> <seconds>};
 * the heading is captured from the player yaw at start. Stop early with
 * {@code /ferrite arrival bench stop}. The end-of-run summary prints to
 * the log directly (not through MonitorLog) so a muted logger cannot
 * swallow it.
 *
 * <p>Register AFTER ChunkArrivalMonitor: this listener reads
 * {@link ChunkArrivalMonitor#lastDeficit} for the tick, which the
 * monitor computes for the pre-step position in its own listener.
 */
public final class ArrivalFlightBench {
	private ArrivalFlightBench() {}

	private static volatile UUID pilot = null;
	private static double stepX;
	private static double stepZ;
	private static int ticksLeft;
	private static int ticksTotal;
	private static int speedBps;
	// Run accumulators, server thread only.
	private static long deficitSum;
	private static long deficitMax;
	private static long ticksWithDeficit;
	private static long sampled;

	public static void register() {
		ServerTickEvents.END_SERVER_TICK.register(ArrivalFlightBench::onTick);
	}

	/** Returns false if a run is already active. */
	public static boolean start(ServerPlayer player, int blocksPerSec, int seconds) {
		if (pilot != null) return false;
		double yawRad = Math.toRadians(player.getYRot());
		double perTick = blocksPerSec / 20.0;
		stepX = -Math.sin(yawRad) * perTick;
		stepZ = Math.cos(yawRad) * perTick;
		ticksTotal = seconds * 20;
		ticksLeft = ticksTotal;
		speedBps = blocksPerSec;
		deficitSum = 0L;
		deficitMax = 0L;
		ticksWithDeficit = 0L;
		sampled = 0L;
		ChunkArrivalMonitor.reset();
		ChunkArrivalMonitor.ENABLED = true;
		pilot = player.getUUID();
		return true;
	}

	public static boolean stop() {
		if (pilot == null) return false;
		ticksLeft = 0;
		return true;
	}

	public static boolean isActive() {
		return pilot != null;
	}

	private static void onTick(MinecraftServer server) {
		UUID id = pilot;
		if (id == null) return;
		ServerPlayer player = server.getPlayerList().getPlayer(id);
		if (player == null) {
			finish(server, "pilot left");
			return;
		}
		// Sample the deficit the monitor just computed for this tick.
		long d = ChunkArrivalMonitor.lastDeficit;
		sampled++;
		deficitSum += d;
		if (d > deficitMax) deficitMax = d;
		if (d > 0) ticksWithDeficit++;

		if (ticksLeft-- <= 0) {
			finish(server, "complete");
			return;
		}
		player.connection.teleport(
				player.getX() + stepX, player.getY(), player.getZ() + stepZ,
				player.getYRot(), player.getXRot());
	}

	private static void finish(MinecraftServer server, String reason) {
		pilot = null;
		ChunkArrivalMonitor.ENABLED = false;
		String summary = String.format(
				"[arrival-bench] %s: speed=%d b/s ran=%d/%d ticks deficit avg=%.1f max=%d ticksWith=%d/%d force(enabled=%s auto=%s)",
				reason, speedBps, sampled, ticksTotal + 1,
				sampled > 0 ? deficitSum / (double) sampled : 0.0,
				deficitMax, ticksWithDeficit, sampled,
				me.apika.apikaprobe.worldgen.chunk.ChunkForcer.ENABLED,
				me.apika.apikaprobe.worldgen.chunk.ChunkForcer.AUTO);
		ExampleMod.LOGGER.info(summary);
		server.getPlayerList().getPlayers().forEach(
				p -> p.sendSystemMessage(net.minecraft.network.chat.Component.literal(summary)));
	}
}
