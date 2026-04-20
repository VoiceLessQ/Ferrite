package me.apika.apikaprobe;

import java.util.concurrent.atomic.AtomicLong;

import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;

import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.RedstoneWireBlock;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import net.minecraft.world.block.WireOrientation;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Correctness oracle for the wire-power BFS we intend to port to Rust.
 *
 * Phased rollout:
 *   step 1 (this file): harness only. Snapshot inputs at HEAD of
 *                       RedstoneWireBlock.update, capture vanilla's
 *                       written POWER at RETURN, invoke a stub
 *                       computeExpectedPower() that returns -1 (no
 *                       prediction). Comparison is skipped; metrics
 *                       confirm the harness is running end-to-end.
 *   step 2: replace stub with a pure-Java BFS matching
 *           RedstoneController.calculateWirePowerAt semantics + the
 *           up/down-step rules. Comparison goes live, mismatches log.
 *   step 3: iterate on 20 fixtures until mismatches = 0.
 *   step 4: translate the validated BFS to Rust. Java stays as
 *           oracle-in-chief for regression detection.
 *
 * Critical mixin-target trap (flagged 2026-04-20):
 *   At RETURN of RedstoneWireBlock.update, the `state` parameter still
 *   references the OLD BlockState (parameters aren't mutated). Vanilla
 *   has already written the new POWER via world.setBlockState, so the
 *   authoritative post-write value is `world.getBlockState(pos).get(
 *   RedstoneWireBlock.POWER)`. Reading from the parameter gives
 *   stale data and a false "mismatch" on every change.
 *
 * Recursion handling: RedstoneWireBlock.update is invoked from
 * onBlockAdded/onStateReplaced paths and recursively via neighborUpdate
 * chains. Only the OUTERMOST entry snapshots inputs — inner calls would
 * see mid-cascade state that isn't meaningful as an oracle input.
 * (ThreadLocal depth counter same pattern as [RedstonePhaseMonitor].)
 *
 * Client filter: RedstoneWireBlock.update accepts World, which can be
 * either side. Server-only measurement via !world.isClient().
 */
public final class RedstoneOracle {
	private static final Logger LOGGER = LoggerFactory.getLogger("ferrite");
	private static final long REPORT_INTERVAL_NS = 5_000_000_000L;

	// Rate-limit mismatch logs so a broken BFS in step 2 doesn't flood
	// latest.log with millions of lines — 10 per second is enough signal.
	private static final long MISMATCH_LOG_MIN_GAP_NS = 100_000_000L;
	private static volatile long lastMismatchLogNs;

	private static final AtomicLong STUB_CHECKS = new AtomicLong();
	private static final AtomicLong VERIFIED_CHECKS = new AtomicLong();
	private static final AtomicLong MISMATCHES = new AtomicLong();

	private static volatile long lastReportNs = System.nanoTime();

	// Per-thread recursion depth + snapshot slot.
	private static final ThreadLocal<int[]> DEPTH = ThreadLocal.withInitial(() -> new int[1]);
	private static final ThreadLocal<Snapshot> SNAPSHOT = ThreadLocal.withInitial(Snapshot::new);

	// Mutable per-thread scratch; cleared on outermost entry. Avoids
	// per-call allocation in a path that fires millions of times per
	// tick on a redstone lag machine.
	static final class Snapshot {
		BlockPos pos;
		int preWritePower;        // old POWER read from state parameter at HEAD
		boolean blockAdded;
		WireOrientation orientation;
		String worldKey;          // dimension id for log context
	}

	private RedstoneOracle() {}

	public static void register() {
		ServerTickEvents.END_SERVER_TICK.register(server -> maybeReport());
	}

	// --- Mixin entry points -------------------------------------------------

	public static void onWireUpdateBegin(
			World world, BlockPos pos, BlockState state,
			WireOrientation orientation, boolean blockAdded) {
		if (world.isClient()) return;
		int[] depth = DEPTH.get();
		if (depth[0]++ != 0) return; // inner call — outermost has the snapshot
		Snapshot snap = SNAPSHOT.get();
		snap.pos = pos.toImmutable();
		snap.preWritePower = state.isOf(Blocks.REDSTONE_WIRE)
				? state.get(RedstoneWireBlock.POWER)
				: -1;
		snap.blockAdded = blockAdded;
		snap.orientation = orientation;
		snap.worldKey = world.getRegistryKey().getValue().toString();
	}

	public static void onWireUpdateEnd(World world, BlockPos pos) {
		if (world.isClient()) return;
		int[] depth = DEPTH.get();
		if (--depth[0] != 0) {
			if (depth[0] < 0) depth[0] = 0;
			return;
		}
		Snapshot snap = SNAPSHOT.get();
		if (snap.pos == null) return;

		// Authoritative post-write power: read from the world, NOT from
		// the `state` parameter. Parameter is pre-write (see class javadoc).
		BlockState after = world.getBlockState(pos);
		if (!after.isOf(Blocks.REDSTONE_WIRE)) {
			// Wire was removed mid-update (e.g. replaced by another block).
			// Not an oracle-meaningful event.
			snap.pos = null;
			return;
		}
		int vanillaPower = after.get(RedstoneWireBlock.POWER);

		int predicted = computeExpectedPower(snap);
		if (predicted < 0) {
			// Stub mode: no prediction to compare against.
			STUB_CHECKS.incrementAndGet();
		} else {
			VERIFIED_CHECKS.incrementAndGet();
			if (vanillaPower != predicted) {
				MISMATCHES.incrementAndGet();
				maybeLogMismatch(snap, vanillaPower, predicted);
			}
		}
		snap.pos = null;
	}

	// --- Algorithm slot -----------------------------------------------------

	/**
	 * Step 1 stub — returns -1 so comparison is skipped. Step 2 replaces
	 * this with a pure-Java BFS that reads the snapshot's inputs
	 * (expanded with neighbor wire powers, source contributions, up/down
	 * step solidity) and computes the expected POWER.
	 */
	private static int computeExpectedPower(Snapshot snap) {
		return -1;
	}

	// --- Reporting ----------------------------------------------------------

	private static void maybeLogMismatch(Snapshot snap, int vanilla, int predicted) {
		long now = System.nanoTime();
		if (now - lastMismatchLogNs < MISMATCH_LOG_MIN_GAP_NS) return;
		lastMismatchLogNs = now;
		LOGGER.warn("[redstone-oracle] MISMATCH pos={},{},{} vanilla={} predicted={} delta={} world={} orientation={} blockAdded={} preWrite={}",
				snap.pos.getX(), snap.pos.getY(), snap.pos.getZ(),
				vanilla, predicted, predicted - vanilla,
				snap.worldKey, snap.orientation, snap.blockAdded, snap.preWritePower);
	}

	private static void maybeReport() {
		long now = System.nanoTime();
		if (now - lastReportNs < REPORT_INTERVAL_NS) return;

		long stub = STUB_CHECKS.getAndSet(0L);
		long verified = VERIFIED_CHECKS.getAndSet(0L);
		long mismatches = MISMATCHES.getAndSet(0L);
		lastReportNs = now;

		if (stub == 0L && verified == 0L) return;

		LOGGER.info("[redstone-oracle] stub-checks={} verified-checks={} mismatches={}",
				stub, verified, mismatches);
	}
}
