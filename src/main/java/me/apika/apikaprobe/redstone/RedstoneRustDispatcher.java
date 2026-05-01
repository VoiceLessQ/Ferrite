package me.apika.apikaprobe.redstone;

import me.apika.apikaprobe.RustBridge;

import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.HashMap;
import java.util.concurrent.atomic.AtomicLong;

import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;

import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.RedStoneWireBlock;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.redstone.DefaultRedstoneWireEvaluator;
import net.minecraft.world.level.redstone.RedstoneWireEvaluator;

import me.apika.apikaprobe.mixin.RedstoneControllerInvoker;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Serializes a connected wire network into [RedstoneHandoff]'s request
 * buffer, invokes the Rust BFS via [RustBridge.computeRedstoneBfs],
 * and applies the returned power deltas.
 *
 * BFS neighbor-resolution mirrors
 * {@code RedstoneWireEvaluator.calculateWirePowerAt}'s connectivity rules:
 *   horizontal neighbor always;
 *   up-step iff neighbor is solid AND pos-above is NOT solid;
 *   down-step iff neighbor is NOT solid.
 * Using the same traversal as the oracle guarantees the node set we
 * hand to Rust matches what vanilla considers connected.
 *
 * Re-entry guard: when we apply deltas via `setBlockState` +
 * `updateNeighbors`, those can trigger further RedStoneWireBlock.update
 * calls (downstream wires/consumers). The [isActive] ThreadLocal lets
 * the interceptor mixin recognize recursive entries and fall through
 * to vanilla — which is a no-op at that point because all wire powers
 * are already at their correct values.
 *
 * Overflow: if the network exceeds MAX_NODES, we abort and let vanilla
 * handle the cascade. A warning is logged at most once per second so
 * the operator knows to raise MAX_NODES or partition the network.
 */
public final class RedstoneRustDispatcher {

	private static final Logger LOGGER = LoggerFactory.getLogger("ferrite");
	private static final long OVERFLOW_LOG_MIN_GAP_NS = 1_000_000_000L;
	private static volatile long lastOverflowLogNs;

	// Lazy controller handle — same pattern as RedstoneOracle.
	private static volatile RedstoneWireEvaluator dispatchController;

	// Dispatch counters — exposed via the 5s [redstone-rust] report so the
	// next log read confirms whether the Rust path is actually firing.
	// dispatched: outermost calls that entered runBfsAndApply (went to Rust)
	// bailed:     cases that returned false (overflow / native missing)
	// applied:    sum of deltas applied by Rust across the window
	private static final AtomicLong DISPATCHED = new AtomicLong();
	private static final AtomicLong BAILED = new AtomicLong();
	private static final AtomicLong APPLIED_DELTAS = new AtomicLong();
	private static final long REPORT_INTERVAL_NS = 5_000_000_000L;
	private static volatile long lastReportNs = System.nanoTime();

	public static void register() {
		ServerTickEvents.END_SERVER_TICK.register(server -> maybeReport());
	}

	private static void maybeReport() {
		long now = System.nanoTime();
		if (now - lastReportNs < REPORT_INTERVAL_NS) return;
		long d = DISPATCHED.getAndSet(0L);
		long b = BAILED.getAndSet(0L);
		long a = APPLIED_DELTAS.getAndSet(0L);
		lastReportNs = now;
		if (d == 0L && b == 0L) return;
		LOGGER.info("[redstone-rust] dispatched={} bailed={} deltas-applied={}  (USE_RUST={})",
				d, b, a, RedstoneHandoff.USE_RUST);
	}

	private static final ThreadLocal<boolean[]> ACTIVE =
			ThreadLocal.withInitial(() -> new boolean[1]);

	// Reusable scratch — repopulated per dispatch, avoids per-call allocation.
	// Bounded at MAX_NODES so the serializer can bail cleanly on overflow.
	private static final ThreadLocal<ScratchBuffers> SCRATCH =
			ThreadLocal.withInitial(ScratchBuffers::new);

	private static final class ScratchBuffers {
		final ArrayDeque<BlockPos> frontier = new ArrayDeque<>();
		final HashMap<BlockPos, Integer> indexByPos = new HashMap<>();
		final BlockPos[] posByIndex = new BlockPos[RedstoneHandoff.MAX_NODES];
		final int[] neighborScratch = new int[RedstoneHandoff.NEIGHBOR_SLOTS];

		void reset() {
			frontier.clear();
			indexByPos.clear();
			// posByIndex entries are overwritten, no need to null out
		}
	}

	private RedstoneRustDispatcher() {}

	public static boolean isActive() {
		return ACTIVE.get()[0];
	}

	private static RedstoneWireEvaluator controller() {
		RedstoneWireEvaluator c = dispatchController;
		if (c == null) {
			c = new DefaultRedstoneWireEvaluator((RedStoneWireBlock) Blocks.REDSTONE_WIRE);
			dispatchController = c;
		}
		return c;
	}

	/**
	 * Runs the Rust BFS on the wire network reachable from `startPos`
	 * and applies the resulting power deltas to the world. Returns true
	 * if Rust handled the cascade (caller should cancel the vanilla
	 * update); false if we bailed (too large / native unavailable —
	 * caller should let vanilla run).
	 */
	public static boolean runBfsAndApply(ServerLevel world, BlockPos startPos) {
		if (!RustBridge.NATIVE_AVAILABLE) {
			BAILED.incrementAndGet();
			return false;
		}

		ScratchBuffers scratch = SCRATCH.get();
		scratch.reset();
		RedstoneControllerInvoker inv = (RedstoneControllerInvoker) controller();

		int nodeCount = discover(world, startPos, scratch);
		if (nodeCount < 0) {
			BAILED.incrementAndGet();
			return false;
		}
		if (nodeCount == 0) {
			BAILED.incrementAndGet();
			return false;
		}

		serializeToBuffer(world, scratch, nodeCount, inv);

		int deltaCount = RustBridge.computeRedstoneBfs(
				RedstoneHandoff.REQUEST_BUF, RedstoneHandoff.RESULT_BUF, nodeCount);

		applyDeltas(world, deltaCount);
		DISPATCHED.incrementAndGet();
		APPLIED_DELTAS.addAndGet(deltaCount);
		return true;
	}

	/**
	 * BFS-discovery pass: walk connected wire neighbors from `startPos`,
	 * assigning each a stable index into the request buffer. Returns
	 * node count, or -1 on overflow.
	 */
	private static int discover(ServerLevel world, BlockPos startPos, ScratchBuffers s) {
		if (!world.getBlockState(startPos).is(Blocks.REDSTONE_WIRE)) return 0;

		BlockPos startImm = startPos.immutable();
		s.indexByPos.put(startImm, 0);
		s.posByIndex[0] = startImm;
		s.frontier.add(startImm);

		int count = 1;
		while (!s.frontier.isEmpty()) {
			BlockPos pos = s.frontier.poll();
			BlockPos above = pos.above();
			BlockState aboveState = world.getBlockState(above);
			boolean aboveSolid = aboveState.isSolidBlock(world, above);

			for (Direction dir : Direction.Plane.HORIZONTAL) {
				BlockPos neighbor = pos.offset(dir);
				BlockState neighborState = world.getBlockState(neighbor);

				int c = tryAdd(world, neighbor, s, count);
				if (c < 0) return -1;
				count = c;

				boolean neighborSolid = neighborState.isSolidBlock(world, neighbor);
				if (neighborSolid && !aboveSolid) {
					c = tryAdd(world, neighbor.above(), s, count);
					if (c < 0) return -1;
					count = c;
				} else if (!neighborSolid) {
					c = tryAdd(world, neighbor.below(), s, count);
					if (c < 0) return -1;
					count = c;
				}
			}
		}
		return count;
	}

	/**
	 * Adds `pos` to the node set if it's a wire and not already seen.
	 * Returns new node count, or -1 on overflow.
	 */
	private static int tryAdd(ServerLevel world, BlockPos pos, ScratchBuffers s, int count) {
		if (s.indexByPos.containsKey(pos)) return count;
		if (!world.getBlockState(pos).is(Blocks.REDSTONE_WIRE)) return count;
		if (count >= RedstoneHandoff.MAX_NODES) {
			maybeLogOverflow();
			return -1;
		}
		// `pos` is always immutable here: callers pass either a fresh BlockPos
		// from offset()/up()/down() (all of which return immutable instances)
		// or an already-immutable position. Skip the redundant toImmutable().
		s.indexByPos.put(pos, count);
		s.posByIndex[count] = pos;
		s.frontier.add(pos);
		return count + 1;
	}

	/**
	 * Second pass: for every discovered node, resolve its neighbor
	 * indices (via the index map) and compute its source power via
	 * vanilla's getStrongPowerAt. Writes directly into
	 * REQUEST_BUF at `index * REQUEST_STRIDE`.
	 */
	private static void serializeToBuffer(
			ServerLevel world, ScratchBuffers s, int nodeCount,
			RedstoneControllerInvoker inv) {
		RedstoneHandoff.resetRequestBuffer();
		for (int idx = 0; idx < nodeCount; idx++) {
			BlockPos pos = s.posByIndex[idx];
			BlockState state = world.getBlockState(pos);
			int current = state.get(RedStoneWireBlock.POWER);
			int source = inv.apikaprobe$getStrongPowerAt(world, pos);

			Arrays.fill(s.neighborScratch, RedstoneHandoff.NO_NEIGHBOR);
			resolveNeighbors(world, pos, s, s.neighborScratch);

			RedstoneHandoff.writeNode(
					idx, pos.getX(), pos.getY(), pos.getZ(),
					current, source,
					RedstoneHandoff.FLAG_IS_WIRE,
					s.neighborScratch);
		}
	}

	/**
	 * Fills `out` with indices of `pos`'s connected wire neighbors,
	 * following the same horizontal / up-step / down-step rules as
	 * calculateWirePowerAt. Empty slots remain NO_NEIGHBOR.
	 */
	private static void resolveNeighbors(
			ServerLevel world, BlockPos pos, ScratchBuffers s, int[] out) {
		BlockPos above = pos.above();
		boolean aboveSolid = world.getBlockState(above).isSolidBlock(world, above);
		int slot = 0;

		for (Direction dir : Direction.Plane.HORIZONTAL) {
			BlockPos neighbor = pos.offset(dir);
			BlockState neighborState = world.getBlockState(neighbor);

			slot = recordIfKnown(s, neighbor, out, slot);

			boolean neighborSolid = neighborState.isSolidBlock(world, neighbor);
			if (neighborSolid && !aboveSolid) {
				slot = recordIfKnown(s, neighbor.above(), out, slot);
			} else if (!neighborSolid) {
				slot = recordIfKnown(s, neighbor.below(), out, slot);
			}
		}
	}

	private static int recordIfKnown(ScratchBuffers s, BlockPos pos, int[] out, int slot) {
		Integer idx = s.indexByPos.get(pos);
		if (idx != null && slot < out.length) {
			out[slot] = idx;
			return slot + 1;
		}
		return slot;
	}

	/**
	 * Writes the Rust-computed new power into each changed wire, then
	 * fires updateNeighbors so non-wire consumers (repeaters, lamps,
	 * pistons, etc.) see the change. The ACTIVE flag prevents our own
	 * update-interceptor mixin from re-entering during these calls.
	 */
	private static void applyDeltas(ServerLevel world, int deltaCount) {
		if (deltaCount == 0) return;
		ACTIVE.get()[0] = true;
		// Single reusable Mutable so the per-delta hot path doesn't allocate
		// two BlockPos per result. setBlockState/updateNeighbors only read
		// coordinates from the parameter, so a Mutable is safe to reuse.
		BlockPos.MutableBlockPos scratchPos = new BlockPos.MutableBlockPos();
		try {
			for (int i = 0; i < deltaCount; i++) {
				scratchPos.set(
						RedstoneHandoff.readResultX(i),
						RedstoneHandoff.readResultY(i),
						RedstoneHandoff.readResultZ(i));
				int newPower = RedstoneHandoff.readResultNewPower(i);
				BlockState state = world.getBlockState(scratchPos);
				if (!state.is(Blocks.REDSTONE_WIRE)) continue;
				world.setBlockState(
						scratchPos,
						state.with(RedStoneWireBlock.POWER, newPower),
						Block.UPDATE_CLIENTS);
			}
			for (int i = 0; i < deltaCount; i++) {
				scratchPos.set(
						RedstoneHandoff.readResultX(i),
						RedstoneHandoff.readResultY(i),
						RedstoneHandoff.readResultZ(i));
				world.updateNeighbors(scratchPos, Blocks.REDSTONE_WIRE);
			}
		} finally {
			ACTIVE.get()[0] = false;
		}
	}

	private static void maybeLogOverflow() {
		long now = System.nanoTime();
		if (now - lastOverflowLogNs < OVERFLOW_LOG_MIN_GAP_NS) return;
		lastOverflowLogNs = now;
		LOGGER.warn("[redstone-rust] network exceeded MAX_NODES={}, falling back to vanilla",
				RedstoneHandoff.MAX_NODES);
	}
}
