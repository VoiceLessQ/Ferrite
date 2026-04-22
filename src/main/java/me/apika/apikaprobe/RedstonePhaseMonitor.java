package me.apika.apikaprobe;

import java.util.concurrent.atomic.AtomicLong;

import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Redstone-cost instrumentation.
 *
 * Two independent metric streams:
 *
 * 1. Wire cascades — RedstoneWireBlock.update is the single private
 *    dispatcher that routes to DefaultRedstoneController or
 *    ExperimentalRedstoneController. Wire power propagation is recursive:
 *    controller.update() → world.updateNeighbors(...) → neighbor's
 *    neighborUpdate → potentially another RedstoneWireBlock.update. To
 *    avoid double-counting, a ThreadLocal depth counter makes only the
 *    outermost entry (0 → 1) start the timer, and only the matching exit
 *    (1 → 0) records duration. Inner calls are counted as part of their
 *    enclosing cascade's wall time.
 *
 * 2. Gate scheduled-ticks — AbstractRedstoneGateBlock.scheduledTick.
 *    Repeaters and comparators fire this via the vanilla block tick
 *    scheduler. Single-level timing (no recursion concern — gates never
 *    re-enter their own scheduledTick within the same call stack).
 *
 * Report format every 5s:
 *   [redstone] wire: avg=Xms max=Yms cascades=N  gates: avg=Xms max=Yms ticks=M
 *
 * Writers: any server-side thread. Readers: server thread via the 5s
 * END_SERVER_TICK handler. AtomicLongs keep the accumulator lock-free;
 * ThreadLocals isolate per-thread recursion state.
 */
public final class RedstonePhaseMonitor {
	private static final Logger LOGGER = LoggerFactory.getLogger("ferrite");
	private static final long REPORT_INTERVAL_NS = 5_000_000_000L;

	// Wire cascade state — ThreadLocal depth guards against recursive
	// self-entry. Start-ns captured once, at the outermost call.
	private static final ThreadLocal<int[]> WIRE_DEPTH = ThreadLocal.withInitial(() -> new int[1]);
	private static final ThreadLocal<long[]> WIRE_START_NS = ThreadLocal.withInitial(() -> new long[1]);

	private static final AtomicLong WIRE_CASCADES = new AtomicLong();
	private static final AtomicLong WIRE_TOTAL_NS = new AtomicLong();
	private static final AtomicLong WIRE_MAX_NS = new AtomicLong();

	// Gate-driven vs direct wire split. Flag is set by RedstoneGateMixin
	// around scheduledTick; onWireBegin reads it at the outermost (0→1)
	// cascade entry to classify the cascade origin.
	public static final ThreadLocal<boolean[]> GATE_ACTIVE = ThreadLocal.withInitial(() -> new boolean[1]);
	private static final AtomicLong WIRE_GATE_DRIVEN = new AtomicLong();
	private static final AtomicLong WIRE_DIRECT = new AtomicLong();

	/**
	 * Phase 2 / 2b activation counter — increments only when a cascade
	 * actually runs through {@code WireHandler.runRustBatch}. Distinct
	 * from the oracle's {@code bfs-runs} (which counts shadow-compute
	 * samples). Together with {@code cascades} this lets us compute the
	 * fraction "Rust path / total cascades" — needed to interpret the
	 * neutral perf result honestly.
	 */
	private static final AtomicLong RUST_BFS_ACTIVATIONS = new AtomicLong();

	// Controller split — which RedstoneController impl handled the update.
	// Lets us verify at a glance whether the world is running default or
	// experimental redstone; crucial because the two have very different
	// cost profiles and a Rust port should benchmark against the default
	// (slow) path, not experimental (already Mojang-optimized).
	private static final AtomicLong DEFAULT_CONTROLLER_CALLS = new AtomicLong();
	private static final AtomicLong EXPERIMENTAL_CONTROLLER_CALLS = new AtomicLong();

	// Gate scheduledTick state — no recursion, simple start-time ThreadLocal.
	private static final ThreadLocal<long[]> GATE_START_NS = ThreadLocal.withInitial(() -> new long[1]);

	private static final AtomicLong GATE_TICKS = new AtomicLong();
	private static final AtomicLong GATE_TOTAL_NS = new AtomicLong();
	private static final AtomicLong GATE_MAX_NS = new AtomicLong();

	private static final AtomicLong TICKS_IN_WINDOW = new AtomicLong();

	private static volatile long lastReportNs = System.nanoTime();

	private RedstonePhaseMonitor() {}

	public static void register() {
		ServerTickEvents.END_SERVER_TICK.register(server -> {
			TICKS_IN_WINDOW.incrementAndGet();
			maybeReport();
		});
	}

	// --- Wire hooks ---------------------------------------------------------

	public static void onWireBegin() {
		int[] depth = WIRE_DEPTH.get();
		if (depth[0]++ == 0) {
			WIRE_START_NS.get()[0] = System.nanoTime();
			if (GATE_ACTIVE.get()[0]) {
				WIRE_GATE_DRIVEN.incrementAndGet();
			} else {
				WIRE_DIRECT.incrementAndGet();
			}
		}
	}

	public static void onWireEnd() {
		int[] depth = WIRE_DEPTH.get();
		if (--depth[0] == 0) {
			long duration = System.nanoTime() - WIRE_START_NS.get()[0];
			WIRE_CASCADES.incrementAndGet();
			WIRE_TOTAL_NS.addAndGet(duration);
			updateMax(WIRE_MAX_NS, duration);
		}
		if (depth[0] < 0) depth[0] = 0; // guard against asymmetric HEAD/RETURN (e.g. early return)
	}

	// --- Controller identity hooks ------------------------------------------

	public static void onDefaultController() {
		DEFAULT_CONTROLLER_CALLS.incrementAndGet();
	}

	public static void onExperimentalController() {
		EXPERIMENTAL_CONTROLLER_CALLS.incrementAndGet();
	}

	/**
	 * Called from {@code WireHandler.runRustBatch} on every successful
	 * activation. Used to compute Rust-path activation fraction in the
	 * window report.
	 */
	public static void onRustBfsActivation() {
		RUST_BFS_ACTIVATIONS.incrementAndGet();
	}

	// --- Gate hooks ---------------------------------------------------------

	public static void onGateBegin() {
		GATE_START_NS.get()[0] = System.nanoTime();
	}

	public static void onGateEnd() {
		long start = GATE_START_NS.get()[0];
		if (start == 0L) return;
		GATE_START_NS.get()[0] = 0L;
		long duration = System.nanoTime() - start;
		GATE_TICKS.incrementAndGet();
		GATE_TOTAL_NS.addAndGet(duration);
		updateMax(GATE_MAX_NS, duration);
	}

	// --- Reporting ----------------------------------------------------------

	private static void updateMax(AtomicLong max, long candidate) {
		max.updateAndGet(prev -> Math.max(prev, candidate));
	}

	private static void maybeReport() {
		long now = System.nanoTime();
		if (now - lastReportNs < REPORT_INTERVAL_NS) return;

		long wCount = WIRE_CASCADES.getAndSet(0L);
		long wTotal = WIRE_TOTAL_NS.getAndSet(0L);
		long wMax = WIRE_MAX_NS.getAndSet(0L);
		long wGate = WIRE_GATE_DRIVEN.getAndSet(0L);
		long wDirect = WIRE_DIRECT.getAndSet(0L);
		long wRust = RUST_BFS_ACTIVATIONS.getAndSet(0L);
		long cDefault = DEFAULT_CONTROLLER_CALLS.getAndSet(0L);
		long cExp = EXPERIMENTAL_CONTROLLER_CALLS.getAndSet(0L);
		long gCount = GATE_TICKS.getAndSet(0L);
		long gTotal = GATE_TOTAL_NS.getAndSet(0L);
		long gMax = GATE_MAX_NS.getAndSet(0L);
		long ticks = TICKS_IN_WINDOW.getAndSet(0L);
		lastReportNs = now;

		if (wCount == 0L && gCount == 0L) return;

		String rustPct = wCount == 0L ? "0.0" : String.format("%.1f", (100.0 * wRust) / wCount);
		LOGGER.info("[redstone] wire: avg={}ms max={}ms cascades={} (gate-driven={} direct={}, default={} exp={})  rust-bfs: activations={} ({}% of cascades)  gates: avg={}ms max={}ms ticks={}  n={} server-ticks",
				formatAvg(wCount, wTotal), formatMs(wMax), wCount, wGate, wDirect, cDefault, cExp,
				wRust, rustPct,
				formatAvg(gCount, gTotal), formatMs(gMax), gCount,
				ticks);
	}

	private static String formatAvg(long count, long totalNs) {
		return count == 0L ? "0.00" : formatMs(totalNs / count);
	}

	private static String formatMs(long nanos) {
		return String.format("%.3f", nanos / 1_000_000.0);
	}
}
