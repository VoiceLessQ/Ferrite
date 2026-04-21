package me.apika.apikaprobe;

import java.util.ArrayDeque;
import java.util.HashSet;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicLong;

import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;

import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.RedstoneWireBlock;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.world.DefaultRedstoneController;
import net.minecraft.world.RedstoneController;
import net.minecraft.world.World;
import net.minecraft.world.block.WireOrientation;

import me.apika.apikaprobe.mixin.RedstoneControllerInvoker;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Correctness oracle for the wire-power BFS we intend to port to Rust.
 *
 * Step 2 (this revision): runs a bounded BFS from the snapshot position
 * over the connected wire graph. For each visited wire node:
 *   expected = max(strongPower, wireCalculatedPower)   (clamped 0..15)
 *   actual   = world.getBlockState(pos).get(POWER)
 * Mismatches increment [NODE_MISMATCHES] and log (rate-limited).
 *
 * Critical design choices:
 *   (1) Per-node math calls vanilla's RedstoneController.calculateWirePowerAt
 *       and getStrongPowerAt via [RedstoneControllerInvoker]. Inherits
 *       up-step / down-step / solid-block-occluder rules from vanilla,
 *       so changes in Mojang's future patches don't silently diverge
 *       the oracle.
 *   (2) BFS traversal (our code) mirrors the SAME neighbor-check logic
 *       that calculateWirePowerAt uses: horizontal neighbor, then
 *       up-step iff `neighbor solid && pos-above not solid`, or
 *       down-step iff `neighbor not solid`. So our traversal and
 *       vanilla's math always agree on connectivity.
 *   (3) Bounded cost via sampling + node cap. On a redstone lag machine
 *       we see ~3M wire updates per tick; running a full BFS on each
 *       would crater TPS. SAMPLE_RATE (1-in-N) and MAX_NODES_PER_BFS
 *       cap overhead while preserving coverage — bugs statistically
 *       surface within seconds.
 *
 * Critical mixin-target trap (flagged 2026-04-20):
 *   At RETURN of RedstoneWireBlock.update, the `state` parameter still
 *   references the OLD BlockState. Authoritative post-write power is
 *   `world.getBlockState(pos).get(POWER)`. The BFS reads world state,
 *   so this is handled for the center node; the only place we use the
 *   parameter's old power is the mismatch-log preWrite field (for
 *   debugging context).
 *
 * Recursion handling: only the OUTERMOST RedstoneWireBlock.update entry
 * triggers a BFS run. Inner recursive calls decrement the depth counter
 * and skip — they'd see mid-cascade state which isn't a valid oracle
 * input.
 */
public final class RedstoneOracle {
	private static final Logger LOGGER = LoggerFactory.getLogger("ferrite");
	private static final long REPORT_INTERVAL_NS = 5_000_000_000L;

	// Tunables (volatile so they can be flipped at runtime via a debug
	// command or breakpoint without recompile).
	public static volatile boolean ENABLED = true;
	/** 1-in-N outer cascades trigger a BFS. 1 = every cascade; 0 = disabled. */
	public static volatile int SAMPLE_RATE = 100;
	/** Upper bound on wire nodes visited per BFS, so one pathological
	 *  contraption can't uncap the oracle's cost. */
	public static volatile int MAX_NODES_PER_BFS = 64;

	// Rate-limit mismatch logs — broken algorithm could produce millions
	// of mismatches per tick; 10 per second is enough to diagnose.
	private static final long MISMATCH_LOG_MIN_GAP_NS = 100_000_000L;
	private static volatile long lastMismatchLogNs;

	// Per-outer-cascade counters.
	private static final AtomicLong BFS_RUNS = new AtomicLong();
	private static final AtomicLong BFS_SAMPLED_OUT = new AtomicLong();
	private static final AtomicLong NODE_CHECKS = new AtomicLong();
	private static final AtomicLong NODE_MISMATCHES = new AtomicLong();

	private static volatile long lastReportNs = System.nanoTime();

	private static final ThreadLocal<int[]> DEPTH = ThreadLocal.withInitial(() -> new int[1]);
	private static final ThreadLocal<Snapshot> SNAPSHOT = ThreadLocal.withInitial(Snapshot::new);

	// Lazy — registry may not be ready when this class first loads; defer
	// until the first mixin invocation (mod init has certainly run by then).
	private static volatile RedstoneController oracleController;

	static final class Snapshot {
		BlockPos pos;
		int preWritePower;
		boolean blockAdded;
		WireOrientation orientation;
		String worldKey;
	}

	private RedstoneOracle() {}

	public static void register() {
		ServerTickEvents.END_SERVER_TICK.register(server -> maybeReport());
	}

	private static volatile boolean controllerInitFailed;

	private static RedstoneController controller() {
		RedstoneController c = oracleController;
		if (c == null && !controllerInitFailed) {
			// Defensive: Blocks.REDSTONE_WIRE should be non-null long before
			// any wire update fires, but if a mod-load ordering quirk leaves
			// it unregistered at our first call we catch it here, log once,
			// and disable the oracle for the rest of the session rather than
			// rethrowing into the cascade hot path.
			try {
				c = new DefaultRedstoneController((RedstoneWireBlock) Blocks.REDSTONE_WIRE);
				oracleController = c;
			} catch (RuntimeException e) {
				controllerInitFailed = true;
				LOGGER.warn("[redstone-oracle] controller init failed; oracle disabled for this session: {}",
						e.getMessage());
				return null;
			}
		}
		return c;
	}

	// --- Mixin entry points -------------------------------------------------

	public static void onWireUpdateBegin(
			World world, BlockPos pos, BlockState state,
			WireOrientation orientation, boolean blockAdded) {
		if (!ENABLED || world.isClient()) return;
		int[] depth = DEPTH.get();
		if (depth[0]++ != 0) return;
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
		if (!ENABLED || world.isClient()) return;
		int[] depth = DEPTH.get();
		if (--depth[0] != 0) {
			if (depth[0] < 0) depth[0] = 0;
			return;
		}
		Snapshot snap = SNAPSHOT.get();
		if (snap.pos == null) return;

		int rate = SAMPLE_RATE;
		if (rate <= 0) {
			snap.pos = null;
			return;
		}
		if (rate > 1 && ThreadLocalRandom.current().nextInt(rate) != 0) {
			BFS_SAMPLED_OUT.incrementAndGet();
			snap.pos = null;
			return;
		}
		runBfsVerification(world, snap);
		snap.pos = null;
	}

	// --- BFS verification ---------------------------------------------------

	private static void runBfsVerification(World world, Snapshot snap) {
		BFS_RUNS.incrementAndGet();
		RedstoneController c = controller();
		if (c == null) return; // controller init failed — silently skip
		RedstoneControllerInvoker inv = (RedstoneControllerInvoker) c;

		ArrayDeque<BlockPos> frontier = new ArrayDeque<>();
		HashSet<BlockPos> visited = new HashSet<>();
		BlockPos start = snap.pos;
		frontier.add(start);
		visited.add(start);

		int cap = MAX_NODES_PER_BFS;
		int visitedCount = 0;

		while (!frontier.isEmpty() && visitedCount < cap) {
			BlockPos pos = frontier.poll();
			visitedCount++;

			BlockState state = world.getBlockState(pos);
			if (!state.isOf(Blocks.REDSTONE_WIRE)) continue;

			int actual = state.get(RedstoneWireBlock.POWER);
			int expected = computePowerAt(inv, world, pos);
			NODE_CHECKS.incrementAndGet();
			if (actual != expected) {
				NODE_MISMATCHES.incrementAndGet();
				maybeLogMismatch(pos, actual, expected, snap);
			}

			enqueueConnectedWireNeighbors(world, pos, visited, frontier);
		}
	}

	/**
	 * Mirrors {@code DefaultRedstoneController.calculateTotalPowerAt}: if
	 * a strong source here is 15, no wire contribution can exceed it;
	 * otherwise max of strong and wire-calculated.
	 */
	private static int computePowerAt(RedstoneControllerInvoker inv, World world, BlockPos pos) {
		int strong = inv.apikaprobe$getStrongPowerAt(world, pos);
		if (strong >= 15) return 15;
		int wire = inv.apikaprobe$calculateWirePowerAt(world, pos);
		return Math.max(strong, wire);
	}

	/**
	 * Matches the neighbor-check inside RedstoneController.calculateWirePowerAt:
	 *   horizontal neighbor always;
	 *   up-step iff neighbor solid AND pos-above not solid;
	 *   down-step iff neighbor NOT solid.
	 * Only enqueues REDSTONE_WIRE blocks at the resolved neighbor position.
	 */
	private static void enqueueConnectedWireNeighbors(
			World world, BlockPos pos, HashSet<BlockPos> visited, ArrayDeque<BlockPos> frontier) {
		BlockPos above = pos.up();
		BlockState aboveState = world.getBlockState(above);
		boolean aboveSolid = aboveState.isSolidBlock(world, above);

		for (Direction dir : Direction.Type.HORIZONTAL) {
			BlockPos neighbor = pos.offset(dir);
			BlockState neighborState = world.getBlockState(neighbor);

			tryEnqueue(world, neighbor, visited, frontier);

			boolean neighborSolid = neighborState.isSolidBlock(world, neighbor);
			if (neighborSolid && !aboveSolid) {
				tryEnqueue(world, neighbor.up(), visited, frontier);
			} else if (!neighborSolid) {
				tryEnqueue(world, neighbor.down(), visited, frontier);
			}
		}
	}

	private static void tryEnqueue(
			World world, BlockPos pos, HashSet<BlockPos> visited, ArrayDeque<BlockPos> frontier) {
		if (visited.contains(pos)) return;
		if (!world.getBlockState(pos).isOf(Blocks.REDSTONE_WIRE)) return;
		BlockPos im = pos.toImmutable();
		visited.add(im);
		frontier.add(im);
	}

	// --- Reporting ----------------------------------------------------------

	private static void maybeLogMismatch(BlockPos pos, int actual, int expected, Snapshot snap) {
		long now = System.nanoTime();
		if (now - lastMismatchLogNs < MISMATCH_LOG_MIN_GAP_NS) return;
		lastMismatchLogNs = now;
		LOGGER.warn("[redstone-oracle] MISMATCH pos={},{},{} vanilla={} predicted={} delta={} world={} cascadeStart={},{},{} orientation={} blockAdded={}",
				pos.getX(), pos.getY(), pos.getZ(),
				actual, expected, expected - actual,
				snap.worldKey,
				snap.pos.getX(), snap.pos.getY(), snap.pos.getZ(),
				snap.orientation, snap.blockAdded);
	}

	private static void maybeReport() {
		long now = System.nanoTime();
		if (now - lastReportNs < REPORT_INTERVAL_NS) return;

		long runs = BFS_RUNS.getAndSet(0L);
		long sampledOut = BFS_SAMPLED_OUT.getAndSet(0L);
		long checks = NODE_CHECKS.getAndSet(0L);
		long mismatches = NODE_MISMATCHES.getAndSet(0L);
		lastReportNs = now;

		if (runs == 0L && sampledOut == 0L) return;

		LOGGER.info("[redstone-oracle] bfs-runs={} sampled-out={} node-checks={} node-mismatches={}  (rate=1/{} cap={} nodes)",
				runs, sampledOut, checks, mismatches,
				SAMPLE_RATE, MAX_NODES_PER_BFS);
	}
}
