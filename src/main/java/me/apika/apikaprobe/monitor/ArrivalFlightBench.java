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

	// Suite mode: interleaved baseline/auto runs on fresh strips, since
	// terrain variance between strips is as large as the effect under
	// test (2026-08-17: baseline 36.6 vs auto 54.5 on different strips
	// proved single runs unusable). Strips advance +STRIP_SPACING blocks
	// perpendicular to the heading per run; a settle pause between runs
	// lets in-flight tickets drain.
	private static final int STRIP_SPACING = 3000;
	private static final int SETTLE_TICKS = 200;
	private static boolean suite = false;
	private static int suiteRunsPerArm;
	private static int suiteRunIndex;
	private static int settleLeft;
	private static double suiteStartX;
	private static double suiteStartY;
	private static double suiteStartZ;
	private static float suiteYaw;
	private static float suitePitch;
	private static final java.util.List<double[]> suiteResults = new java.util.ArrayList<>();

	// Headless mode: -Dferrite.autobench=x,z,speed,seconds,runsPerArm
	// teleports the first player to (x, 200, z) facing north, runs the
	// suite, logs the comparison, and halts the server so the gradle
	// process exits. Complete-control CI-style measurement, no manual
	// commands (see build.gradle -Pferrite.autobench).
	private static String autobenchSpec = System.getProperty("ferrite.autobench");
	private static int autobenchDelay = 100;
	private static boolean autoShutdown = false;

	public static void register() {
		ServerTickEvents.END_SERVER_TICK.register(ArrivalFlightBench::onTick);
	}

	private static void autobenchTick(MinecraftServer server) {
		if (server.getPlayerList().getPlayers().isEmpty()) return;
		if (--autobenchDelay > 0) return;
		String spec = autobenchSpec;
		autobenchSpec = null;
		try {
			String[] p = spec.split(",");
			double x = Double.parseDouble(p[0].trim());
			double z = Double.parseDouble(p[1].trim());
			int speed = Integer.parseInt(p[2].trim());
			int secs = Integer.parseInt(p[3].trim());
			int runs = Integer.parseInt(p[4].trim());
			ServerPlayer player = firstPlayer(server);
			player.setGameMode(net.minecraft.world.level.GameType.SPECTATOR);
			player.connection.teleport(x, 200.0, z, 0.0f, 0.0f);
			autoShutdown = true;
			// Suite fields come from the args, not the (still in-flight)
			// teleport, so the start position is exact.
			suite = true;
			suiteRunsPerArm = runs;
			suiteRunIndex = 0;
			suiteResults.clear();
			suiteStartX = x;
			suiteStartY = 200.0;
			suiteStartZ = z;
			suiteYaw = 0.0f;
			suitePitch = 0.0f;
			speedBps = speed;
			ticksTotal = secs * 20;
			settleLeft = 100;
			ExampleMod.LOGGER.info("[arrival-bench] autobench: ({}, {}) {} b/s x {} s, {} runs/arm",
					x, z, speed, secs, runs);
		} catch (RuntimeException e) {
			ExampleMod.LOGGER.warn("[arrival-bench] bad autobench spec '{}' (want x,z,speed,seconds,runsPerArm): {}",
					spec, e.toString());
		}
	}

	/** Returns false if a run is already active. */
	public static boolean start(ServerPlayer player, int blocksPerSec, int seconds) {
		if (pilot != null || suite) return false;
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

	/** Interleaved suite: 2*runsPerArm runs, even index = baseline
	 *  (auto off), odd = auto on, each on a fresh strip. Restores the
	 *  AUTO flag when done. Returns false if a run is active. */
	public static boolean startSuite(ServerPlayer player, int blocksPerSec, int seconds, int runsPerArm) {
		if (pilot != null || suite) return false;
		suite = true;
		suiteRunsPerArm = runsPerArm;
		suiteRunIndex = 0;
		suiteResults.clear();
		suiteStartX = player.getX();
		suiteStartY = player.getY();
		suiteStartZ = player.getZ();
		suiteYaw = player.getYRot();
		suitePitch = player.getXRot();
		speedBps = blocksPerSec;
		ticksTotal = seconds * 20;
		settleLeft = 1;
		return true;
	}

	public static boolean stop() {
		if (pilot == null && !suite) return false;
		suite = false;
		ticksLeft = 0;
		if (pilot == null) {
			settleLeft = 0;
			warmingUp = false;
			ChunkArrivalMonitor.ENABLED = false;
		}
		return true;
	}

	public static boolean isActive() {
		return pilot != null;
	}

	private static UUID suitePilot = null;
	private static boolean suitePrevAuto;
	// Post-teleport warmup: counting starts only once the arrival point
	// has fully loaded (deficit 0) or the cap passes, so run averages
	// measure flight arrival, not teleport fill (the max=1089 artifact
	// in the first suite attempt, 2026-08-17).
	private static boolean warmingUp = false;
	private static int warmupLeft;
	private static final int WARMUP_CAP_TICKS = 1200;

	private static void onTick(MinecraftServer server) {
		if (autobenchSpec != null && !suite && pilot == null) {
			autobenchTick(server);
			return;
		}
		UUID id = pilot;
		if (id == null) {
			if (suite) suiteTick(server);
			return;
		}
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

	private static void suiteTick(MinecraftServer server) {
		if (warmingUp) {
			ServerPlayer p = server.getPlayerList().getPlayer(suitePilot);
			if (p == null) {
				suite = false;
				warmingUp = false;
				broadcast(server, "[arrival-bench] suite aborted: pilot left");
				return;
			}
			if (ChunkArrivalMonitor.lastDeficit > 0 && --warmupLeft > 0) return;
			warmingUp = false;
			beginRun(p, suiteYaw);
			return;
		}
		if (settleLeft-- > 0) return;
		int totalRuns = 2 * suiteRunsPerArm;
		if (suiteRunIndex >= totalRuns) {
			finishSuite(server);
			return;
		}
		ServerPlayer player = suitePilot != null
				? server.getPlayerList().getPlayer(suitePilot)
				: firstPlayer(server);
		if (player == null) {
			suite = false;
			broadcast(server, "[arrival-bench] suite aborted: pilot left");
			return;
		}
		if (suitePilot == null) {
			suitePilot = player.getUUID();
			suitePrevAuto = me.apika.apikaprobe.worldgen.chunk.ChunkForcer.AUTO;
		}
		// Even run = baseline, odd = auto; strips advance perpendicular
		// to the heading so every run flies virgin terrain.
		boolean auto = (suiteRunIndex % 2) == 1;
		me.apika.apikaprobe.worldgen.chunk.ChunkForcer.AUTO = auto;
		double yawRad = Math.toRadians(suiteYaw);
		double perpX = Math.cos(yawRad) * STRIP_SPACING * suiteRunIndex;
		double perpZ = Math.sin(yawRad) * STRIP_SPACING * suiteRunIndex;
		player.connection.teleport(suiteStartX + perpX, suiteStartY,
				suiteStartZ + perpZ, suiteYaw, suitePitch);
		broadcast(server, String.format("[arrival-bench] suite run %d/%d (%s), warming strip",
				suiteRunIndex + 1, totalRuns, auto ? "auto" : "baseline"));
		// Monitor on during warmup so lastDeficit tracks the fill; the
		// accumulators reset again in beginRun once counting starts.
		ChunkArrivalMonitor.reset();
		ChunkArrivalMonitor.ENABLED = true;
		// Force a nonzero first reading so warmup does not end before
		// the monitor has sampled the post-teleport state.
		ChunkArrivalMonitor.lastDeficit = 1L;
		warmupLeft = WARMUP_CAP_TICKS;
		warmingUp = true;
	}

	private static void beginRun(ServerPlayer player, float yaw) {
		double yawRad = Math.toRadians(yaw);
		double perTick = speedBps / 20.0;
		stepX = -Math.sin(yawRad) * perTick;
		stepZ = Math.cos(yawRad) * perTick;
		ticksLeft = ticksTotal;
		deficitSum = 0L;
		deficitMax = 0L;
		ticksWithDeficit = 0L;
		sampled = 0L;
		ChunkArrivalMonitor.reset();
		ChunkArrivalMonitor.ENABLED = true;
		pilot = player.getUUID();
	}

	private static void finishSuite(MinecraftServer server) {
		suite = false;
		suitePilot = null;
		me.apika.apikaprobe.worldgen.chunk.ChunkForcer.AUTO = suitePrevAuto;
		double[] mean = new double[2];
		int[] n = new int[2];
		StringBuilder sb = new StringBuilder("[arrival-bench] suite done, ")
				.append(speedBps).append(" b/s:");
		for (double[] r : suiteResults) {
			int arm = (int) r[0];
			mean[arm] += r[1];
			n[arm]++;
			sb.append(String.format(" %s=%.1f", arm == 1 ? "auto" : "base", r[1]));
		}
		if (n[0] > 0 && n[1] > 0) {
			sb.append(String.format(" | mean base=%.1f auto=%.1f",
					mean[0] / n[0], mean[1] / n[1]));
		}
		broadcast(server, sb.toString());
		if (autoShutdown) {
			autoShutdown = false;
			ExampleMod.LOGGER.info("[arrival-bench] autobench complete, halting server");
			server.halt(false);
		}
	}

	private static ServerPlayer firstPlayer(MinecraftServer server) {
		var players = server.getPlayerList().getPlayers();
		return players.isEmpty() ? null : players.get(0);
	}

	private static void broadcast(MinecraftServer server, String msg) {
		ExampleMod.LOGGER.info(msg);
		server.getPlayerList().getPlayers().forEach(
				p -> p.sendSystemMessage(net.minecraft.network.chat.Component.literal(msg)));
	}

	private static void finish(MinecraftServer server, String reason) {
		pilot = null;
		ChunkArrivalMonitor.ENABLED = false;
		double avg = sampled > 0 ? deficitSum / (double) sampled : 0.0;
		String summary = String.format(
				"[arrival-bench] %s: speed=%d b/s ran=%d/%d ticks deficit avg=%.1f max=%d ticksWith=%d/%d force(enabled=%s auto=%s)",
				reason, speedBps, sampled, ticksTotal + 1,
				avg, deficitMax, ticksWithDeficit, sampled,
				me.apika.apikaprobe.worldgen.chunk.ChunkForcer.ENABLED,
				me.apika.apikaprobe.worldgen.chunk.ChunkForcer.AUTO);
		broadcast(server, summary);
		if (suite) {
			suiteResults.add(new double[]{(suiteRunIndex % 2) == 1 ? 1 : 0, avg});
			suiteRunIndex++;
			settleLeft = SETTLE_TICKS;
		}
	}
}
