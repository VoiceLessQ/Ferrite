/*
 * Adapted from Alternate Current (https://github.com/SpaceWalkerRS/alternate-current)
 * Copyright (c) 2022 Space Walker — MIT License
 *
 * Yarn-remapped for Fabric 1.21.11. Key deviations from upstream:
 *   - Dropped the Config file-I/O abstraction; uses the Ferrite-side
 *     FerriteWireConfig static knobs instead. No per-world config.
 *   - Dropped the LevelStorageAccess constructor arg (was only used
 *     to build the Config file path).
 *   - Uses yarn's CollectingNeighborUpdates instead of Mojmap's
 *     InstantNeighborUpdater — same semantics, different name.
 *   - Orientation (Mojmap) → Orientation (yarn), same methods.
 *   - All other renames per docs/AC_YARN_MAPPINGS.md.
 *
 * Original authorship and design remain entirely Space Walker's;
 * this is a translation, not a redesign. See the class javadoc
 * (preserved below) for the algorithm's goals and structure.
 */
package me.apika.apikaprobe.redstone;

import java.util.Iterator;
import java.util.Queue;

import me.apika.apikaprobe.redstone.RedstoneHandoff;

import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectMap.Entry;
import it.unimi.dsi.fastutil.longs.Long2ObjectMaps;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;

import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.redstone.NeighborUpdater;
import net.minecraft.world.level.redstone.CollectingNeighborUpdater;
import net.minecraft.world.level.redstone.Orientation;

import me.apika.apikaprobe.redstone.WireConstants.Directions;

/**
 * Handles redstone-wire power changes for one {@link ServerLevel}.
 * Algorithm by Space Walker — see the AC repository for the original
 * design notes reproduced below.
 *
 * <p><b>Design goals</b>:
 * <ol>
 *   <li>Minimize the number of times a wire checks its surroundings to
 *       determine its power level.</li>
 *   <li>Minimize the number of block and shape updates emitted.</li>
 *   <li>Emit block and shape updates in a deterministic, non-locational
 *       order — fixes MC-11193.</li>
 * </ol>
 *
 * <p>Vanilla wire fails on points 1 and 2 because each wire recalculates
 * its power in isolation and emits 42 block updates + up to 22 shape
 * updates per power change, many of which are redundant. This handler
 * builds a network of connected wires, finds the wires that receive
 * external redstone power, and propagates in a single BFS — each wire's
 * power is calculated at most twice and committed once.
 *
 * <p>For the direction-of-power-flow mechanism that fixes update-order
 * locationality, see the original AC writeup (inspired by theosib's
 * RedstoneWireTurbo).
 *
 * @author Space Walker (original)
 */
public class WireHandler {

	private static final int POWER_MIN = WireConstants.SIGNAL_MIN;
	private static final int POWER_MAX = WireConstants.SIGNAL_MAX;
	private static final int POWER_STEP = WireConstants.POWER_STEP;

	private final ServerLevel world;

	/** Map of wires and neighboring blocks for the current cascade. */
	private final Long2ObjectMap<Node> nodes;
	/** FIFO queue used by the breadth-first search through the network. */
	private final Queue<WireNode> search;
	/** Priority queue of pending wire/neighbor updates. */
	private final Queue<Node> updates;

	private final NeighborUpdater neighborUpdater;

	// Pool of reusable Node objects; grows on demand.
	private Node[] nodeCache;
	private int nodeCount;

	/** True while the handler is draining the updates queue. */
	private boolean updating;

	/**
	 * Reusable scratch for {@link #runRustBatch} — pre-allocated to
	 * the buffer cap so cascades never trigger fresh allocation on
	 * the hot path. Phase 2's first measurement showed that per-cascade
	 * allocation (HashMap, capturing lambda, slot int[]) was the actual
	 * bottleneck, not Rust compute. These two arrays kill all of it.
	 */
	private final WireNode[] rustWires = new WireNode[RedstoneHandoff.MAX_NODES];
	private final int[] rustNeighbors = new int[RedstoneHandoff.NEIGHBOR_SLOTS];
	/** Pre-allocated scratch for the AC path's per-edge direction array. */
	private final byte[] rustNeighborIDir = new byte[RedstoneHandoff.NEIGHBOR_SLOTS];

	public WireHandler(ServerLevel world) {
		this.world = world;

		this.nodes = new Long2ObjectOpenHashMap<>();
		this.search = new SimpleQueue();
		this.updates = new PriorityQueue();

		// CollectingNeighborUpdater collects updates and dispatches them
		// in waves; mojmap construction takes (level, maxChainedDepth).
		// 1_000_000 matches the effectively-unbounded default vanilla
		// uses when no game-rule limit is configured.
		this.neighborUpdater = new CollectingNeighborUpdater(world, 1_000_000);

		this.nodeCache = new Node[16];
		this.fillNodeCache(0, 16);
	}

	// ------------------------------------------------------------------
	// Node cache / lookup
	// ------------------------------------------------------------------

	private Node getOrAddNode(BlockPos pos) {
		return getOrAddNode(pos, null);
	}

	/**
	 * Retrieve the {@link Node} representing the block at {@code pos},
	 * creating one from the cache if necessary. Passing a non-null
	 * {@code state} skips a world lookup.
	 */
	private Node getOrAddNode(BlockPos pos, BlockState state) {
		return nodes.compute(pos.asLong(), (key, node) -> {
			if (node == null) {
				return getNextNode(pos, state != null ? state : world.getBlockState(pos));
			}
			if (node.invalid) {
				return revalidateNode(node);
			}
			return node;
		});
	}

	private Node removeNode(BlockPos pos) {
		return nodes.remove(pos.asLong());
	}

	/**
	 * Build a node for {@code (pos, state)} — either a fresh
	 * {@link WireNode} for redstone dust, or a cached {@link Node} for
	 * anything else.
	 */
	private Node getNextNode(BlockPos pos, BlockState state) {
		return state.is(Blocks.REDSTONE_WIRE)
				? new WireNode(world, pos, state)
				: getNextNode().set(pos, state, true);
	}

	/** Grab the next unused node from the cache, growing the cache if needed. */
	private Node getNextNode() {
		if (nodeCount == nodeCache.length) {
			increaseNodeCache();
		}
		return nodeCache[nodeCount++];
	}

	private void increaseNodeCache() {
		Node[] oldCache = nodeCache;
		nodeCache = new Node[oldCache.length << 1];

		for (int index = 0; index < oldCache.length; index++) {
			nodeCache[index] = oldCache[index];
		}

		fillNodeCache(oldCache.length, nodeCache.length);
	}

	private void fillNodeCache(int start, int end) {
		for (int index = start; index < end; index++) {
			nodeCache[index] = new Node(world);
		}
	}

	/**
	 * Re-check a potentially-stale node against the world. If the block
	 * type changed (wire ↔ non-wire), the node is rebuilt; otherwise
	 * its cached flags are refreshed and wire-specific discovery state
	 * is reset.
	 */
	private Node revalidateNode(Node node) {
		if (!node.invalid) {
			return node;
		}

		BlockPos pos = node.pos;
		BlockState state = world.getBlockState(pos);

		boolean wasWire = node.isWire();
		boolean isWire = state.is(Blocks.REDSTONE_WIRE);

		if (wasWire != isWire) {
			return getNextNode(pos, state);
		}

		node.invalid = false;

		if (isWire) {
			// Wire nodes grab their block state just-in-time when setting
			// power, so we don't need to refresh it here.
			WireNode wire = node.asWire();

			wire.root = false;
			wire.discovered = false;
			wire.searched = false;
		} else {
			node.set(pos, state, false);
		}

		return node;
	}

	/**
	 * Retrieve the neighbor of a node in the given direction, creating
	 * and linking it on first access. The back-link is populated too
	 * so subsequent lookups in either direction are O(1).
	 */
	private Node getNeighbor(Node node, int iDir) {
		Node neighbor = node.neighbors[iDir];

		if (neighbor == null || neighbor.invalid) {
			Direction dir = Directions.ALL[iDir];
			BlockPos pos = node.pos.relative(dir);

			Node oldNeighbor = neighbor;
			neighbor = getOrAddNode(pos);

			if (neighbor != oldNeighbor) {
				int iOpp = Directions.iOpposite(iDir);

				node.neighbors[iDir] = neighbor;
				neighbor.neighbors[iOpp] = node;
			}
		}

		return neighbor;
	}

	// ------------------------------------------------------------------
	// Public entry points
	// ------------------------------------------------------------------

	/** Invoked when a wire at {@code pos} receives a block update. */
	public boolean onWireUpdated(BlockPos pos, BlockState state, Orientation orientation) {
		Node node = getOrAddNode(pos, state);

		if (!node.isWire()) {
			return false; // we should never get here
		}

		WireNode wire = node.asWire();

		invalidate();
		revalidateNode(wire);
		findRoots(wire, orientation);
		tryUpdate();

		return true;
	}

	/** Invoked when a wire is placed at {@code pos}. */
	public void onWireAdded(BlockPos pos, BlockState state) {
		Node node = getOrAddNode(pos, state);

		if (!node.isWire()) {
			return; // we should never get here
		}

		WireNode wire = node.asWire();
		wire.added = true;

		invalidate();
		revalidateNode(wire);
		findRoot(wire);
		tryUpdate();
	}

	/** Invoked when a wire at {@code pos} is removed. */
	public void onWireRemoved(BlockPos pos, BlockState state) {
		Node node = removeNode(pos);
		WireNode wire;

		if (node == null || !node.isWire()) {
			wire = new WireNode(world, pos, state);
		} else {
			wire = node.asWire();
		}

		wire.invalid = true;
		wire.removed = true;

		// If both flags are set, this removal is part of an in-flight
		// cascade; it'll be picked up there.
		if (updating && wire.shouldBreak) {
			return;
		}

		invalidate();
		revalidateNode(wire);
		findRoot(wire);
		tryUpdate();
	}

	// ------------------------------------------------------------------
	// Cascade internals
	// ------------------------------------------------------------------

	/**
	 * Mark every cached node as invalid. Called before each cascade so
	 * that stale state from a previous cascade's block changes doesn't
	 * leak into power calculations.
	 */
	private void invalidate() {
		if (updating && !nodes.isEmpty()) {
			Iterator<Entry<Node>> it = Long2ObjectMaps.fastIterator(nodes);

			while (it.hasNext()) {
				Entry<Node> entry = it.next();
				Node node = entry.getValue();
				node.invalid = true;
			}
		}
	}

	/**
	 * Find wires at and around {@code wire} that are in an invalid state
	 * and need power changes. These wires become BFS roots.
	 */
	private void findRoots(WireNode wire, Orientation orientation) {
		int iDirBias = -1;

		if (orientation != null) {
			Direction dir = orientation.getFront().getAxis().isHorizontal()
				? orientation.getFront()
				: orientation.getUp();
			iDirBias = Directions.index(dir);
		}

		findRoot(wire, iDirBias);

		if (!wire.searched) {
			return;
		}

		if (orientation == null) {
			// No neighborChanged orientation — check all sides.
			for (int iDir : FerriteWireConfig.UPDATE_ORDER.directNeighbors(wire.iFlowDir)) {
				findRootsAround(wire, iDir);
			}
		} else {
			// Use the orientation from the neighborChanged update to look behind only.
			findRootsAround(wire, Directions.index(orientation.getFront().getOpposite()));
		}
	}

	/** Look for additional roots reachable through the neighbor at {@code iDir}. */
	private void findRootsAround(WireNode wire, int iDir) {
		Node node = getNeighbor(wire, iDir);

		if (node.isConductor() || node.isSignalSource()) {
			for (int iSide : FerriteWireConfig.UPDATE_ORDER.cardinalNeighbors(wire.iFlowDir)) {
				Node neighbor = getNeighbor(node, iSide);

				if (neighbor.isWire()) {
					findRoot(neighbor.asWire(), iSide);
				}
			}
		}
	}

	private void findRoot(WireNode wire) {
		findRoot(wire, -1);
	}

	/**
	 * If the given wire requires power changes, queue it as a BFS root.
	 */
	private void findRoot(WireNode wire, int iDiscoveryDir) {
		if (wire.discovered) {
			return;
		}

		discover(wire);
		findExternalPower(wire);
		findPower(wire, false);

		if (needsUpdate(wire)) {
			searchRoot(wire, iDiscoveryDir);
		}
	}

	/**
	 * Initialize the wire for the BFS: check whether it should break,
	 * reset virtual/external power, rebuild its connection set.
	 */
	private void discover(WireNode wire) {
		if (wire.discovered) {
			return;
		}

		wire.discovered = true;
		wire.searched = false;

		if (!wire.removed && !wire.shouldBreak && !wire.state.canSurvive(world, wire.pos)) {
			wire.shouldBreak = true;
		}

		wire.virtualPower = wire.currentPower;
		wire.externalPower = POWER_MIN - 1;

		wire.connections.set(this::getNeighbor);
	}

	/**
	 * Determine the power a wire receives from surrounding blocks.
	 * External (non-wire) power is only computed on demand — if power
	 * from neighboring wires is already saturating, there's no need.
	 */
	private void findPower(WireNode wire, boolean ignoreSearched) {
		// Reset flow info before recomputing.
		wire.virtualPower = wire.externalPower;
		wire.flowIn = 0;

		// Removed/breaking wires emit the minimum power — they effectively
		// don't exist, so they can't push power to neighbors.
		if (wire.removed || wire.shouldBreak) {
			return;
		}

		// Neighboring-wire power never exceeds POWER_MAX - POWER_STEP; if
		// external power already meets that, skip the wire-power check.
		if (wire.externalPower < (POWER_MAX - POWER_STEP)) {
			findWirePower(wire, ignoreSearched);
		}
	}

	/** Absorb power from connected wires (with decay). */
	private void findWirePower(WireNode wire, boolean ignoreSearched) {
		wire.connections.forEach(connection -> {
			if (!connection.accept) {
				return;
			}

			WireNode neighbor = connection.wire;

			if (!ignoreSearched || !neighbor.searched) {
				int power = Math.max(POWER_MIN, neighbor.virtualPower - POWER_STEP);
				int iOpp = Directions.iOpposite(connection.iDir);

				wire.offerPower(power, iOpp);
			}
		});
	}

	/**
	 * Absorb power from non-wire components (torches, repeaters, levers,
	 * redstone blocks, etc.). Only computed once per cascade per wire.
	 */
	private void findExternalPower(WireNode wire) {
		if (wire.removed || wire.shouldBreak || wire.externalPower >= POWER_MIN) {
			return;
		}

		wire.externalPower = getExternalPower(wire);

		if (wire.externalPower > wire.virtualPower) {
			wire.virtualPower = wire.externalPower;
		}
	}

	/** Query the world for strong + weak redstone signals around the wire. */
	private int getExternalPower(WireNode wire) {
		int power = POWER_MIN;

		for (int iDir = 0; iDir < Directions.ALL.length; iDir++) {
			Node neighbor = getNeighbor(wire, iDir);

			// Wire-to-wire power is handled separately.
			if (neighbor.isWire()) {
				continue;
			}

			// Target blocks are both conductor AND signal source (since 1.16).
			if (neighbor.isConductor()) {
				power = Math.max(power, getDirectSignalTo(wire, neighbor));
			}
			if (neighbor.isSignalSource()) {
				power = Math.max(power, neighbor.state.getSignal(world, neighbor.pos, Directions.ALL[iDir]));
			}

			if (power >= POWER_MAX) {
				return POWER_MAX;
			}
		}

		return power;
	}

	/**
	 * Power received through a neighboring conductor block (e.g. a wire
	 * running up the side of a torch → the torch strongly powers the
	 * block above → that block indirectly powers adjacent wires).
	 */
	private int getDirectSignalTo(WireNode wire, Node node) {
		int power = POWER_MIN;

		for (int iDir = 0; iDir < Directions.ALL.length; iDir++) {
			Node neighbor = getNeighbor(node, iDir);

			if (neighbor.isSignalSource()) {
				power = Math.max(power, neighbor.state.getDirectSignal(world, neighbor.pos, Directions.ALL[iDir]));

				if (power >= POWER_MAX) {
					return POWER_MAX;
				}
			}
		}

		return power;
	}

	/** True if the wire's current world state doesn't match its resolved power. */
	private boolean needsUpdate(WireNode wire) {
		return wire.removed || wire.shouldBreak || wire.virtualPower != wire.currentPower;
	}

	private void searchRoot(WireNode wire, int iBackupFlowDir) {
		if (wire.connections.iFlowDir >= 0) {
			// Power-flow direction from connections takes precedent.
			iBackupFlowDir = wire.connections.iFlowDir;
		} else if (iBackupFlowDir < 0) {
			iBackupFlowDir = 0;
		}

		search(wire, true, iBackupFlowDir);
	}

	private void search(WireNode wire, boolean root, int iBackupFlowDir) {
		search.offer(wire);

		wire.root = root;
		wire.searched = true;
		// Flow is normally set at power-update time, but networks with
		// multiple power sources need a fallback flow for directionality.
		// Roots use their connections' flow; non-roots use the discovery
		// direction.
		wire.iFlowDir = iBackupFlowDir;
	}

	private void tryUpdate() {
		if (!search.isEmpty()) {
			update();
		}
		if (!updating) {
			nodes.clear();
			nodeCount = 0;
		}
	}

	/**
	 * Main cascade entry — search the network, depower all wires,
	 * then power them in priority order, emitting block/shape updates
	 * inline.
	 */
	private void update() {
		searchNetwork();

		// Cascade-size + per-bucket timing for the BFS sweep experiment.
		// Capture size before runRustBatch drains `search`; time covers
		// the propagation + emission phase (the only part that differs
		// between Java and Rust paths).
		int cascadeWires = search.size();
		long cascadeStart = System.nanoTime();

		// Phase 2 of the AC Rust core port (docs/REDSTONE_PORT_PLAN.md).
		// Selection priority:
		//   1. RUST_AC: full AC offer-based propagation kernel. Replaces
		//      depowerNetwork + powerNetwork's wire-application step.
		//      Java still does setBlockState + queueNeighbors +
		//      updateNeighborShapes per result, in returned order.
		//   2. RUST_BFS: relaxation kernel (computes powers only, Java's
		//      priority queue still owns ordering).
		//   3. Pure Java (depowerNetwork + powerNetwork).
		// If a kernel bails (overflow / native missing / link error),
		// next path in the chain runs.
		boolean batched = false;
		boolean acHandledEmission = false;
		boolean eligible = cascadeWires >= FerriteWireConfig.RUST_BFS_MIN_NODES
				&& cascadeWires <= RedstoneHandoff.MAX_NODES
				&& me.apika.apikaprobe.RustBridge.NATIVE_AVAILABLE;
		if (FerriteWireConfig.RUST_AC && eligible) {
			batched = runRustAcBatch();
			acHandledEmission = batched;
		}
		if (!batched && FerriteWireConfig.RUST_BFS && eligible) {
			batched = runRustBatch();
		}
		if (!batched) {
			depowerNetwork();
		}

		try {
			// AC path emits inline; powerNetwork() drains any non-wire
			// neighbor updates that queueNeighbors fed into `updates`.
			powerNetwork();
		} catch (Throwable t) {
			// Leave the handler in a consistent state on exception;
			// otherwise subsequent cascades will be locked out.
			updating = false;
			throw t;
		} finally {
			if (cascadeWires > 0) {
				me.apika.apikaprobe.monitor.RedstonePhaseMonitor.onCascade(
						cascadeWires, System.nanoTime() - cascadeStart, batched);
			}
		}

		// Suppress unused-variable warning when the AC emission path is
		// inert (RUST_AC off). The flag exists for future telemetry.
		if (acHandledEmission) {
			// no-op; placeholder for path-specific monitor in Phase 3
		}
	}

	/**
	 * Phase 2 batch path: drains {@link #search}, serializes all wires
	 * + connectivity into the shared request buffer, calls the Rust
	 * BFS kernel, and seeds the {@link #updates} queue with wires whose
	 * power changed. Returns true on success; false on any failure
	 * (caller falls back to the Java depower path).
	 */
	private boolean runRustBatch() {
		int n = search.size();

		// Drain `search` into the pre-allocated scratch array; tag each
		// wire with its slot index. No HashMap, no boxing — neighbor
		// resolution below reads w.rustIndex directly.
		int idx = 0;
		while (!search.isEmpty()) {
			WireNode w = search.poll();
			rustWires[idx] = w;
			w.rustIndex = idx;
			idx++;
		}

		// Resolve external power for every wire — depowerNetwork would
		// normally do this; on the batch path we do it ourselves so the
		// Rust kernel sees correct source contributions.
		for (int i = 0; i < n; i++) {
			findExternalPower(rustWires[i]);
		}

		// Serialize. Direct linked-list walk avoids the per-wire lambda
		// allocation that connections.forEach(c -> ...) costs.
		RedstoneHandoff.resetRequestBuffer();
		for (int i = 0; i < n; i++) {
			WireNode w = rustWires[i];
			int slot = 0;
			for (WireConnection c = w.connections.head(); c != null; c = c.next) {
				if (slot >= RedstoneHandoff.NEIGHBOR_SLOTS) break;
				int ni = c.wire.rustIndex;
				if (ni >= 0) {
					rustNeighbors[slot++] = ni;
				}
			}
			// Fill the rest with NO_NEIGHBOR sentinel.
			for (int k = slot; k < RedstoneHandoff.NEIGHBOR_SLOTS; k++) {
				rustNeighbors[k] = RedstoneHandoff.NO_NEIGHBOR;
			}
			RedstoneHandoff.writeNode(
					i,
					w.pos.getX(), w.pos.getY(), w.pos.getZ(),
					w.currentPower,
					w.externalPower,
					RedstoneHandoff.FLAG_IS_WIRE,
					rustNeighbors);
		}

		// One JNI call.
		int changed;
		try {
			changed = me.apika.apikaprobe.RustBridge.computeRedstoneBfs(
					RedstoneHandoff.REQUEST_BUF,
					RedstoneHandoff.RESULT_BUF,
					n);
		} catch (UnsatisfiedLinkError | RuntimeException e) {
			// Native blew up — re-seed search with our drained wires so
			// the Java fallback path runs cleanly. Clear rustIndex marks
			// to keep state clean.
			for (int i = 0; i < n; i++) {
				rustWires[i].rustIndex = -1;
				search.offer(rustWires[i]);
				rustWires[i] = null;
			}
			return false;
		}

		// Read deltas. Result format is (x, y, z, newPower) — look up the
		// wire via the existing fastutil Long2ObjectMap (primitive-keyed,
		// no Long boxing) instead of our own HashMap.
		for (int i = 0; i < changed; i++) {
			long key = net.minecraft.core.BlockPos.asLong(
					RedstoneHandoff.readResultX(i),
					RedstoneHandoff.readResultY(i),
					RedstoneHandoff.readResultZ(i));
			Node node = nodes.get(key);
			if (!(node instanceof WireNode wire)) continue;
			wire.virtualPower = RedstoneHandoff.readResultNewPower(i);
			queueWire(wire);
		}

		// Clear rustIndex marks + scratch slots so subsequent cascades
		// don't see stale tags. Cheap — straight-line write.
		for (int i = 0; i < n; i++) {
			rustWires[i].rustIndex = -1;
			rustWires[i] = null;
		}
		me.apika.apikaprobe.monitor.RedstonePhaseMonitor.onRustBfsActivation();
		return true;
	}

	/**
	 * AC offer-based batch path. Drains {@link #search}, serializes the
	 * richer payload (initialFlowIn, removed/shouldBreak/root/added
	 * flags, per-edge iDir) into {@link RedstoneHandoff#AC_REQUEST_BUF},
	 * calls the Rust AC kernel, and applies results in returned priority
	 * order via {@code setPower + queueNeighbors + updateNeighborShapes}.
	 *
	 * <p>Differs from {@link #runRustBatch()} in three ways:
	 * <ul>
	 *   <li>Serializes flow-direction state (initial flow-in mask, per-
	 *       edge direction) so Rust can compute new flow directions.</li>
	 *   <li>Skips the Java {@code depowerNetwork} step — Rust does the
	 *       depower + propagate as one offer-based simulation.</li>
	 *   <li>Iterates results in returned order, calling setPower and
	 *       neighbor emission directly. Bypasses {@code updates} priority
	 *       queue for wires; non-wire neighbor updates still go through
	 *       {@code updates} via {@link #queueNeighbors}.</li>
	 * </ul>
	 *
	 * <p>Returns {@code true} on success; {@code false} on overflow,
	 * native unavailability, or kernel link error (caller falls back
	 * to {@link #runRustBatch()} or the Java path).
	 */
	private boolean runRustAcBatch() {
		int n = search.size();

		// Drain `search` into scratch, tag each wire with its slot index.
		int idx = 0;
		while (!search.isEmpty()) {
			WireNode w = search.poll();
			rustWires[idx] = w;
			w.rustIndex = idx;
			idx++;
		}

		// Resolve external power for every wire — same step the Phase-2
		// BFS path does. Lazy-resolution semantics in AC's findWirePower
		// don't translate to the static-snapshot kernel, so all wires
		// must have correct externalPower before serialization.
		for (int i = 0; i < n; i++) {
			findExternalPower(rustWires[i]);
		}

		// Serialize richer payload.
		RedstoneHandoff.resetAcRequestBuffer();
		for (int i = 0; i < n; i++) {
			WireNode w = rustWires[i];

			// Walk connections, fill neighborIndices + neighborIDir.
			int slot = 0;
			for (WireConnection c = w.connections.head(); c != null; c = c.next) {
				if (slot >= RedstoneHandoff.NEIGHBOR_SLOTS) break;
				int ni = c.wire.rustIndex;
				if (ni >= 0) {
					rustNeighbors[slot] = ni;
					rustNeighborIDir[slot] = (byte) c.iDir;
					slot++;
				}
			}
			for (int k = slot; k < RedstoneHandoff.NEIGHBOR_SLOTS; k++) {
				rustNeighbors[k] = RedstoneHandoff.NO_NEIGHBOR;
				rustNeighborIDir[k] = RedstoneHandoff.AC_DIR_NONE;
			}

			int flags = 0;
			if (w.removed)     flags |= RedstoneHandoff.AC_FLAG_REMOVED;
			if (w.shouldBreak) flags |= RedstoneHandoff.AC_FLAG_SHOULD_BREAK;
			if (w.root)        flags |= RedstoneHandoff.AC_FLAG_ROOT;
			if (w.added)       flags |= RedstoneHandoff.AC_FLAG_ADDED;

			RedstoneHandoff.writeAcNode(
					i,
					w.pos.getX(), w.pos.getY(), w.pos.getZ(),
					w.currentPower,
					w.externalPower,
					w.flowIn,
					flags,
					rustNeighbors,
					rustNeighborIDir);
		}

		// One JNI call.
		int changed;
		try {
			changed = me.apika.apikaprobe.RustBridge.computeRedstoneAc(
					RedstoneHandoff.AC_REQUEST_BUF,
					RedstoneHandoff.AC_RESULT_BUF,
					n);
		} catch (UnsatisfiedLinkError | RuntimeException e) {
			// Native blew up — re-seed search with our drained wires so
			// the fallback path runs cleanly. Clear rustIndex marks.
			for (int i = 0; i < n; i++) {
				rustWires[i].rustIndex = -1;
				search.offer(rustWires[i]);
				rustWires[i] = null;
			}
			return false;
		}

		// Apply results in returned (priority) order. setPower writes
		// to world; queueNeighbors and updateNeighborShapes emit block
		// + shape updates.
		for (int i = 0; i < changed; i++) {
			long key = net.minecraft.core.BlockPos.asLong(
					RedstoneHandoff.readAcResultX(i),
					RedstoneHandoff.readAcResultY(i),
					RedstoneHandoff.readAcResultZ(i));
			Node node = nodes.get(key);
			if (!(node instanceof WireNode wire)) continue;

			int newPower    = RedstoneHandoff.readAcResultNewPower(i);
			int newFlowIn   = RedstoneHandoff.readAcResultFlowIn(i);
			int rustIFlow   = RedstoneHandoff.readAcResultIFlowDir(i);
			// resultFlags read for future diagnostics; Java's setPower
			// already routes via wire.removed / wire.shouldBreak set
			// before serialization, so we don't need to act on it here.
			// int resultFlags = RedstoneHandoff.readAcResultFlags(i);

			wire.virtualPower = newPower;
			wire.flowIn       = newFlowIn;

			// findPowerFlow fallback: AC-Java falls back to
			// connections.iFlowDir when the flow-in mask is ambiguous
			// (FLOW_IN_TO_FLOW_OUT returns -1). Rust returns -1 in that
			// case; mirror the fallback here.
			if (rustIFlow >= 0) {
				wire.iFlowDir = rustIFlow;
			} else if (wire.connections.iFlowDir >= 0) {
				wire.iFlowDir = wire.connections.iFlowDir;
			}

			// setPower routes removed -> no-op, shouldBreak -> dropStacks
			// + AIR, normal -> setBlockState(POWER, newPower).
			if (wire.setPower()) {
				queueNeighbors(wire);
				updateNeighborShapes(wire);
			}
		}

		// Clear rustIndex marks + scratch slots.
		for (int i = 0; i < n; i++) {
			rustWires[i].rustIndex = -1;
			rustWires[i] = null;
		}
		me.apika.apikaprobe.monitor.RedstonePhaseMonitor.onRustBfsActivation();
		return true;
	}

	/**
	 * Breadth-first search through connected wires to collect every
	 * wire that will be affected by the cascade.
	 */
	private void searchNetwork() {
		for (WireNode wire : search) {
			wire.connections.forEach(connection -> {
				if (!connection.offer) {
					return;
				}

				WireNode neighbor = connection.wire;

				if (neighbor.searched) {
					return;
				}

				discover(neighbor);
				findPower(neighbor, false);

				// If wire power decreased, probe non-wire sources so
				// we know how low the power can fall.
				if (neighbor.virtualPower < neighbor.currentPower) {
					findExternalPower(neighbor);
				}

				if (needsUpdate(neighbor)) {
					search(neighbor, false, connection.iDir);
				}
			}, FerriteWireConfig.UPDATE_ORDER, wire.iFlowDir);
		}
	}

	/** Reset every wire in the BFS queue to minimum power. */
	private void depowerNetwork() {
		while (!search.isEmpty()) {
			WireNode wire = search.poll();
			findPower(wire, true);

			if (wire.root || wire.removed || wire.shouldBreak || wire.virtualPower > POWER_MIN) {
				queueWire(wire);
			} else {
				// Wires with no incoming power don't queue until a
				// neighbor offers them power. Forcing virtualPower
				// below the minimum ensures they accept any offer.
				wire.virtualPower--;
			}
		}
	}

	/**
	 * Drain the update queue: set each wire's new power, then queue
	 * block updates to its non-wire neighbors and emit shape updates.
	 */
	private void powerNetwork() {
		// Re-entrant cascades fold into the in-flight one; exit early.
		if (updating) {
			return;
		}

		updating = true;

		while (!updates.isEmpty()) {
			Node node = updates.poll();

			if (node.isWire()) {
				WireNode wire = node.asWire();

				if (!needsUpdate(wire)) {
					continue;
				}

				// Re-validate world state at commit time — another mod or
				// a destructive cascade-internal change could have replaced
				// this wire between discovery and now. Skip the wasted
				// findPowerFlow/transmitPower work if the snapshot is
				// stale. Mirrors vanilla ExperimentalRedstoneWireEvaluator
				// line 46-56's iterator.remove() pattern.
				if (!wire.removed && !world.getBlockState(wire.pos).is(Blocks.REDSTONE_WIRE)) {
					continue;
				}

				findPowerFlow(wire);
				transmitPower(wire);

				if (wire.setPower()) {
					queueNeighbors(wire);

					// For backwards-compat with pre-1.19 observer
					// behavior (neighbor updates became queued in 1.19),
					// emit extra shape updates so observers still fire.
					updateNeighborShapes(wire);
				}
			} else {
				WireNode neighborWire = node.neighborWire;

				if (neighborWire != null) {
					BlockPos neighborPos = neighborWire.pos;
					Block neighborBlock = neighborWire.state.getBlock();

					updateBlock(node, neighborPos, neighborBlock);
				}
			}
		}

		updating = false;
	}

	/**
	 * Resolve the direction of power flow through the wire from its
	 * {@code flowIn} mask, falling back to the connection-derived flow,
	 * then to the backup flow set when the wire was added to the network.
	 */
	private void findPowerFlow(WireNode wire) {
		int flow = WireConstants.FLOW_IN_TO_FLOW_OUT[wire.flowIn];

		if (flow >= 0) {
			wire.iFlowDir = flow;
		} else if (wire.connections.iFlowDir >= 0) {
			wire.iFlowDir = wire.connections.iFlowDir;
		}
	}

	/** Push power to neighboring wires, queueing each that accepts. */
	private void transmitPower(WireNode wire) {
		wire.connections.forEach(connection -> {
			if (!connection.offer) {
				return;
			}

			WireNode neighbor = connection.wire;

			int power = Math.max(POWER_MIN, wire.virtualPower - POWER_STEP);
			int iDir = connection.iDir;

			if (neighbor.offerPower(power, iDir)) {
				queueWire(neighbor);
			}
		}, FerriteWireConfig.UPDATE_ORDER, wire.iFlowDir);
	}

	/** Emit shape updates to non-wire, non-air neighbors of {@code wire}. */
	private void updateNeighborShapes(WireNode wire) {
		BlockPos wirePos = wire.pos;
		BlockState wireState = wire.state;

		for (int iDir : WireConstants.SHAPE_UPDATE_ORDER) {
			Node neighbor = getNeighbor(wire, iDir);

			// Shape updates to redstone wire are very expensive and
			// unnecessary for power changes; shape updates to air do
			// nothing.
			if (!neighbor.isWire() && !neighbor.state.isAir()) {
				int iOpp = Directions.iOpposite(iDir);
				Direction opp = Directions.ALL[iOpp];

				updateShape(neighbor, opp, wirePos, wireState);
			}
		}
	}

	private void updateShape(Node node, Direction dir, BlockPos neighborPos, BlockState neighborState) {
		neighborUpdater.shapeUpdate(dir, neighborState, node.pos, neighborPos, Block.UPDATE_CLIENTS, 512);
	}

	/** Queue block updates to nodes around the given wire. */
	private void queueNeighbors(WireNode wire) {
		FerriteWireConfig.UPDATE_ORDER.forEachNeighbor(this::getNeighbor, wire, wire.iFlowDir, neighbor -> queueNeighbor(neighbor, wire));
	}

	private void queueNeighbor(Node node, WireNode neighborWire) {
		// Wires are updated through transmitPower; skip them here.
		// Skip air neighbors too — block updates to air do nothing.
		// The state may be slightly stale but the skip is safe because
		// only wire- or air-changes would invalidate it mid-cascade,
		// and those we handle elsewhere.
		if (!node.isWire() && !node.state.isAir()) {
			node.neighborWire = neighborWire;
			updates.offer(node);
		}
	}

	/** Queue a wire for a power change, or transmit power if no change is needed. */
	private void queueWire(WireNode wire) {
		if (needsUpdate(wire)) {
			updates.offer(wire);
		} else {
			findPowerFlow(wire);
			transmitPower(wire);
		}
	}

	/** Emit a block update to a non-wire neighbor. */
	private void updateBlock(Node node, BlockPos neighborPos, Block neighborBlock) {
		// Redstone wire is the only block that uses neighborChanged's
		// orientation arg, and we never deliver block updates to wires
		// through this path, so null is safe.
		neighborUpdater.neighborChanged(node.pos, neighborBlock, null);
	}
}
