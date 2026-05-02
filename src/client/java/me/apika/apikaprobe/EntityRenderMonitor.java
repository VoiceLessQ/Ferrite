package me.apika.apikaprobe;

import java.util.concurrent.atomic.AtomicLong;

import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;

/**
 * Sampled timer for EntityRenderDispatcher.render(...).
 *
 * render() is called once per visible entity per frame. At 300 entities
 * × 60 fps = ~18K calls/sec on dev hardware; even more on bursty frames.
 * Full timing would add measurement overhead comparable to the function
 * itself.
 *
 * Strategy mirrors AquiferMonitor:
 *   - atomic counter increments on every call (~10ns)
 *   - every Nth call records ThreadLocal start + timed duration on RETURN
 *   - 5-second windows aggregated and reset
 *
 * Log line includes an extrapolated low-end estimate using a crude
 * 50x GPU throughput ratio (RTX 4090 → Intel HD 620, approximate for
 * draw-call-bound workloads). The estimate is explicitly labeled so
 * nobody mistakes it for a real measurement.
 */
public final class EntityRenderMonitor {
	private static final Logger LOGGER = LoggerFactory.getLogger("ferrite");

	private static final long REPORT_INTERVAL_NS = 5_000_000_000L;
	private static final int SAMPLE_EVERY = 100;

	/** Crude GPU throughput ratio: RTX 4090 vs Intel HD 620 for draw-call-bound work. */
	private static final double LOW_END_SLOWDOWN_FACTOR = 50.0;

	private static final ThreadLocal<Long> RENDER_TS = ThreadLocal.withInitial(() -> 0L);

	private static final AtomicLong CALL_COUNT = new AtomicLong();
	private static final AtomicLong SAMPLED_COUNT = new AtomicLong();
	private static final AtomicLong SAMPLED_TOTAL_NS = new AtomicLong();
	private static final AtomicLong SAMPLED_MAX_NS = new AtomicLong();

	private static volatile long lastReportNs = System.nanoTime();

	private EntityRenderMonitor() {}

	public static void register() {
		ClientTickEvents.END_CLIENT_TICK.register(client -> maybeReport(client));
	}

	public static void onRenderBegin() {
		long n = CALL_COUNT.getAndIncrement();
		if (n % SAMPLE_EVERY == 0) {
			RENDER_TS.set(System.nanoTime());
		}
	}

	public static void onRenderEnd() {
		long start = RENDER_TS.get();
		if (start == 0L) {
			return;
		}
		RENDER_TS.set(0L);
		long duration = System.nanoTime() - start;
		SAMPLED_COUNT.incrementAndGet();
		SAMPLED_TOTAL_NS.addAndGet(duration);
		SAMPLED_MAX_NS.updateAndGet(prev -> Math.max(prev, duration));
	}

	private static void maybeReport(Minecraft client) {
		long now = System.nanoTime();
		if (now - lastReportNs < REPORT_INTERVAL_NS) {
			return;
		}

		long calls = CALL_COUNT.getAndSet(0L);
		long sampled = SAMPLED_COUNT.getAndSet(0L);
		long total = SAMPLED_TOTAL_NS.getAndSet(0L);
		long max = SAMPLED_MAX_NS.getAndSet(0L);

		lastReportNs = now;

		if (calls == 0L || sampled == 0L) {
			return;
		}

		ClientLevel world = client.level;
		int entities = world == null ? 0 : world.getEntityCount();
		int fps = client.getFps();

		double avgNs = total / (double) sampled;
		double maxMs = max / 1_000_000.0;

		// Per-frame entity-render cost on current hardware:
		//   avg ns/call × (calls / 5s) / fps / 1_000_000 → ms/frame
		// Falls back to entity_count when fps unavailable.
		double framesInWindow = fps > 0 ? fps * 5.0 : 1.0;
		double frameMsHere = (avgNs * calls) / framesInWindow / 1_000_000.0;

		// Extrapolated low-end per-frame cost at `entities` visible mobs:
		//   avg ns/call × 50 × entity_count / 1_000_000 → ms
		double lowEndMs = (avgNs * LOW_END_SLOWDOWN_FACTOR * entities) / 1_000_000.0;

		LOGGER.info("[entity-render] calls={} sampled={} avg={} ns max={} ms  frame≈{} ms @yours  low-end≈{} ms (est @HD620)",
				calls,
				sampled,
				String.format("%.0f", avgNs),
				String.format("%.2f", maxMs),
				String.format("%.2f", frameMsHere),
				String.format("%.2f", lowEndMs));
	}
}
