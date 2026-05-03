package me.apika.apikaprobe.worldgen.chunk;

import me.apika.apikaprobe.bridge.ExampleMod;

import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;

import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerBossEvent;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.BossEvent;
import net.minecraft.world.level.storage.LevelResource;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.UUID;

/**
 * SERVER_STARTED handler that consumes a pending world-creation pre-gen
 * request and drives a boss bar showing progress to the host (or to ops
 * on a dedicated server).
 *
 * <p>Resolution order on each server start:
 * <ol>
 *   <li><b>Resume snapshot</b> at {@code <world>/ferrite_pregen.dat} —
 *       always wins; takes the iterator state from where the previous
 *       run was cancelled or interrupted.
 *   <li><b>Singleplayer UI request</b> from {@link PregenWorldOptions} —
 *       fired by the Create World "More" tab toggle.
 *   <li><b>Dedicated-server property</b> {@code -Dferrite.pregen.radius=N}
 *       <i>iff</i> {@code <world>/ferrite_pregen.done} does not exist
 *       (first-launch detection). On graceful completion the marker is
 *       written so subsequent restarts skip pre-gen.
 * </ol>
 *
 * <p>Boss bar updates are throttled to every 5th chunk (plus completion)
 * to keep packet volume reasonable at ~80 chunks/sec; without throttling
 * each player sees ~80 update packets/second through pre-gen.
 */
public final class PregenLifecycle {
	private PregenLifecycle() {}

	private static final String PROPERTY_RADIUS = "ferrite.pregen.radius";

	private static volatile ServerBossEvent activeBossBar;
	private static volatile MinecraftServer activeServer;

	public static void register() {
		ServerLifecycleEvents.SERVER_STARTED.register(PregenLifecycle::onServerStarted);
		ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> {
			ServerBossEvent bar = activeBossBar;
			if (bar != null) {
				bar.addPlayer(handler.player);
			}
		});
	}

	private static void onServerStarted(MinecraftServer server) {
		ServerLevel overworld = server.overworld();
		if (overworld == null) return;
		String worldName = server.getWorldData().getLevelName();
		Path worldDir = server.getWorldPath(LevelResource.ROOT);
		Path snapshotFile = worldDir.resolve(PregenSnapshot.SNAPSHOT_FILE);
		Path doneMarker = worldDir.resolve(PregenSnapshot.DONE_MARKER);
		ExampleMod.LOGGER.info(
				"[ferrite-pregen] SERVER_STARTED: worldName='{}' worldDir={} snapshotExists={} doneMarkerExists={}",
				worldName, worldDir, Files.exists(snapshotFile), Files.exists(doneMarker));

		// 1. Resume snapshot (always wins)
		Optional<PregenSnapshot> resume = PregenSnapshot.readFrom(snapshotFile);
		if (resume.isPresent()) {
			PregenSnapshot snap = resume.get();
			ExampleMod.LOGGER.info(
					"[ferrite-pregen] resuming from snapshot @ chunk ({}, {}) radius={} (already done {}/{})",
					snap.centerX(), snap.centerZ(), snap.radius(),
					snap.iteratorState().emitted(),
					(2 * snap.radius() + 1) * (2 * snap.radius() + 1));
			startBossBar(server, snap.centerX(), snap.centerZ(), snap.radius());
			PregenDriver.runFromSnapshot(overworld, snap, new BossBarListener(),
					new FileCheckpointer(snapshotFile, doneMarker, worldName,
							snap.centerX(), snap.centerZ(), snap.radius()));
			// Clear any singleplayer request for this world too — we are
			// already pre-genning, no double-fire.
			PregenWorldOptions.consumeRequestFor(worldName);
			return;
		}

		// 2. Singleplayer UI request
		OptionalInt uiRadius = PregenWorldOptions.consumeRequestFor(worldName);
		if (uiRadius.isPresent()) {
			startFresh(server, overworld, worldName, uiRadius.getAsInt(),
					snapshotFile, doneMarker, "singleplayer UI");
			return;
		}

		// 3. Dedicated-server system property, gated by done-marker
		Integer propRadius = parseRadiusProperty();
		if (propRadius != null && !Files.exists(doneMarker)) {
			startFresh(server, overworld, worldName, propRadius,
					snapshotFile, doneMarker,
					"-D" + PROPERTY_RADIUS + "=" + propRadius);
		}
	}

	private static Integer parseRadiusProperty() {
		String raw = System.getProperty(PROPERTY_RADIUS);
		if (raw == null) return null;
		try {
			int r = Integer.parseInt(raw.trim());
			if (r < PregenWorldOptions.RADIUS_MIN || r > PregenWorldOptions.RADIUS_MAX) {
				ExampleMod.LOGGER.warn(
						"[ferrite-pregen] {}={} out of range [{}-{}]; ignoring",
						PROPERTY_RADIUS, r,
						PregenWorldOptions.RADIUS_MIN, PregenWorldOptions.RADIUS_MAX);
				return null;
			}
			return r;
		} catch (NumberFormatException e) {
			ExampleMod.LOGGER.warn(
					"[ferrite-pregen] {}={} is not an integer; ignoring",
					PROPERTY_RADIUS, raw);
			return null;
		}
	}

	private static void startFresh(MinecraftServer server, ServerLevel overworld,
			String worldName, int radius, Path snapshotFile, Path doneMarker,
			String source) {
		BlockPos spawn = overworld.getRespawnData().pos();
		int cx = spawn.getX() >> 4;
		int cz = spawn.getZ() >> 4;
		int total = (2 * radius + 1) * (2 * radius + 1);

		ExampleMod.LOGGER.info(
				"[ferrite-pregen] starting pre-gen ({}) @ chunk ({}, {}) radius={} ({} chunks)",
				source, cx, cz, radius, total);

		startBossBar(server, cx, cz, radius);
		PregenDriver.run(overworld, cx, cz, radius, new BossBarListener(),
				new FileCheckpointer(snapshotFile, doneMarker, worldName, cx, cz, radius));
	}

	private static void startBossBar(MinecraftServer server, int cx, int cz, int radius) {
		ServerBossEvent bar = new ServerBossEvent(
				UUID.randomUUID(),
				Component.literal("Pre-generating spawn area..."),
				BossEvent.BossBarColor.BLUE,
				BossEvent.BossBarOverlay.PROGRESS);
		bar.setProgress(0f);
		for (ServerPlayer p : server.getPlayerList().getPlayers()) {
			bar.addPlayer(p);
		}
		activeBossBar = bar;
		activeServer = server;
	}

	private static void clearBossBar() {
		ServerBossEvent b = activeBossBar;
		MinecraftServer s = activeServer;
		activeBossBar = null;
		activeServer = null;
		if (b != null && s != null) {
			s.execute(b::removeAllPlayers);
		}
	}

	private static final class BossBarListener implements PregenProgressListener {
		@Override
		public void onProgress(int done, int total, double rate) {
			if (done % 5 == 0 || done == total) {
				MinecraftServer s = activeServer;
				ServerBossEvent b = activeBossBar;
				if (s != null && b != null) {
					double eta = rate > 0 ? (total - done) / rate : 0;
					float fraction = (float) done / total;
					String label = String.format(
							"Pre-generating spawn — %d/%d (%.0f/s, ETA %.0fs)",
							done, total, rate, eta);
					s.execute(() -> {
						b.setProgress(fraction);
						b.setName(Component.literal(label));
					});
				}
			}
			if (done % 100 == 0 || done == total) {
				double eta = rate > 0 ? (total - done) / rate : 0;
				ExampleMod.LOGGER.info(String.format(
						"[ferrite-pregen] %d/%d chunks (%.1f/s, ETA %.0fs)",
						done, total, rate, eta));
			}
		}

		@Override
		public void onComplete(int total) {
			ExampleMod.LOGGER.info("[ferrite-pregen] complete -- {} chunks", total);
			clearBossBar();
		}

		@Override
		public void onCancelled(int done, int total) {
			ExampleMod.LOGGER.info("[ferrite-pregen] cancelled -- {}/{} chunks (resume on next launch)", done, total);
			clearBossBar();
		}
	}

	private static final class FileCheckpointer implements PregenCheckpointer {
		private final Path snapshotFile;
		private final Path doneMarker;
		private final String worldName;
		private final int centerX;
		private final int centerZ;
		private final int radius;

		FileCheckpointer(Path snapshotFile, Path doneMarker, String worldName,
				int centerX, int centerZ, int radius) {
			this.snapshotFile = snapshotFile;
			this.doneMarker = doneMarker;
			this.worldName = worldName;
			this.centerX = centerX;
			this.centerZ = centerZ;
			this.radius = radius;
		}

		@Override
		public void onCheckpoint(ConcentricChunkIterator.State state) {
			try {
				new PregenSnapshot(worldName, centerX, centerZ, radius, state)
						.writeTo(snapshotFile);
			} catch (IOException e) {
				ExampleMod.LOGGER.warn("[ferrite-pregen] checkpoint write failed: {}",
						e.getMessage());
			}
		}

		@Override
		public void onFinalize(boolean completedNormally) {
			if (completedNormally) {
				try {
					Files.deleteIfExists(snapshotFile);
					Files.createDirectories(doneMarker.getParent());
					Files.writeString(doneMarker,
							"Ferrite pre-gen completed for radius=" + radius
									+ " centered @ chunk (" + centerX + "," + centerZ + ")\n");
				} catch (IOException e) {
					ExampleMod.LOGGER.warn(
							"[ferrite-pregen] finalize file ops failed: {}", e.getMessage());
				}
			}
			// On cancel: keep snapshot file as-is for next launch resume.
		}
	}
}
