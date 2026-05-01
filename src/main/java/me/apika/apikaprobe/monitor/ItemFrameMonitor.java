package me.apika.apikaprobe.monitor;

import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.decoration.ItemFrame;
import net.minecraft.server.level.ServerLevel;

/**
 * Counts loaded ItemFrame (and GlowItemFrameEntity, which extends it)
 * across all server worlds so misc-bucket cost can be divided into a per-frame
 * number.
 *
 * Counting runs once per 5-second window. Logs only when the count changes
 * from the previous window (and once on the first observation, so the
 * baseline is visible). Steady counts stay silent to avoid log noise.
 *
 * Iterating world entities every tick would itself cost what we are trying
 * to measure, so the count check is gated to the same 5-second cadence as
 * the other monitors.
 */
public final class ItemFrameMonitor {
	private static final Logger LOGGER = LoggerFactory.getLogger("ferrite");
	private static final long REPORT_INTERVAL_NS = 5_000_000_000L;

	private static volatile long lastReportNs = System.nanoTime();
	private static int lastLoggedCount = -1;

	private ItemFrameMonitor() {}

	public static void register() {
		ServerTickEvents.END_SERVER_TICK.register(server -> {
			long now = System.nanoTime();
			if (now - lastReportNs < REPORT_INTERVAL_NS) return;
			lastReportNs = now;

			int total = 0;
			for (ServerLevel world : server.getWorlds()) {
				for (Entity e : world.iterateEntities()) {
					if (e instanceof ItemFrame) {
						total++;
					}
				}
			}

			if (total != lastLoggedCount) {
				int prev = lastLoggedCount;
				lastLoggedCount = total;
				if (prev < 0) {
					LOGGER.info("[item-frame] count={}", total);
				} else {
					int delta = total - prev;
					String sign = delta >= 0 ? "+" : "";
					LOGGER.info("[item-frame] count={} (was {}, delta {}{})", total, prev, sign, delta);
				}
			}
		});
	}
}
