/*
 * Adapted from Alternate Current (https://github.com/SpaceWalkerRS/alternate-current)
 * Copyright (c) 2022 Space Walker — MIT License
 *
 * Yarn-remapped for Fabric 1.21.11. Pure graph bookkeeping — no
 * vanilla-API touch, so the port is mostly a re-home to this package
 * and its extracted constants (WireConstants.Directions /
 * NodeProvider / FLOW_IN_TO_FLOW_OUT originally lived inside
 * alternate.current.wire.WireHandler).
 */
package me.apika.apikaprobe.redstone;

import java.util.Arrays;
import java.util.function.Consumer;

import me.apika.apikaprobe.redstone.WireConstants.Directions;
import me.apika.apikaprobe.redstone.WireConstants.NodeProvider;

/**
 * Manages the set of {@link WireConnection}s owned by a single
 * {@link WireNode}. Connections are built once per wire-state change
 * via {@link #set(NodeProvider)}, which walks the four horizontal
 * neighbors and their up/down variants and records which wires
 * receive/emit power through the owner.
 *
 * <p>Iteration comes in two flavors:
 * <ul>
 *   <li>{@link #forEach(Consumer)} — order-independent traversal.</li>
 *   <li>{@link #forEach(Consumer, UpdateOrder, int)} — ordered by the
 *       update-order's cardinal-neighbor sequence, used when the
 *       iteration order affects behavior (e.g. shape-update emission).</li>
 * </ul>
 *
 * @author Space Walker (original, Mojmap)
 */
public class WireConnectionManager {

	/** The wire that owns these connections. */
	final WireNode owner;

	/** Head of each horizontal direction's sub-list within the linked chain. */
	private final WireConnection[] heads;

	private WireConnection head;
	private WireConnection tail;

	/** Total number of connections, for quick emptiness checks. */
	int total;

	/** 4-bit mask of cardinal directions that have at least one outgoing wire connection. */
	private int flowTotal;
	/** Resolved outgoing flow direction index, computed from {@code flowTotal}; -1 if ambiguous. */
	int iFlowDir;

	WireConnectionManager(WireNode owner) {
		this.owner = owner;
		this.heads = new WireConnection[Directions.HORIZONTAL.length];
		this.total = 0;
		this.flowTotal = 0;
		this.iFlowDir = -1;
	}

	/**
	 * Rebuild the connection set from the owner's current neighbors.
	 * Same semantics as AC: each horizontal neighbor contributes either
	 * a direct same-level connection (if it's a wire) or an up-step /
	 * down-step connection gated by solid-block conductance.
	 */
	void set(NodeProvider nodes) {
		if (total > 0) {
			clear();
		}

		boolean belowIsConductor = nodes.getNeighbor(owner, Directions.DOWN).isConductor();
		boolean aboveIsConductor = nodes.getNeighbor(owner, Directions.UP).isConductor();

		for (int iDir = 0; iDir < Directions.HORIZONTAL.length; iDir++) {
			Node neighbor = nodes.getNeighbor(owner, iDir);

			if (neighbor.isWire()) {
				add(neighbor.asWire(), iDir, true, true);
			} else {
				boolean sideIsConductor = neighbor.isConductor();

				if (!sideIsConductor) {
					Node node = nodes.getNeighbor(neighbor, Directions.DOWN);
					if (node.isWire()) {
						add(node.asWire(), iDir, belowIsConductor, true);
					}
				}
				if (!aboveIsConductor) {
					Node node = nodes.getNeighbor(neighbor, Directions.UP);
					if (node.isWire()) {
						add(node.asWire(), iDir, true, sideIsConductor);
					}
				}
			}
		}

		if (total > 0) {
			iFlowDir = WireConstants.FLOW_IN_TO_FLOW_OUT[flowTotal];
		}
	}

	private void clear() {
		Arrays.fill(heads, null);
		head = null;
		tail = null;
		total = 0;
		flowTotal = 0;
		iFlowDir = -1;
	}

	private void add(WireNode wire, int iDir, boolean offer, boolean accept) {
		add(new WireConnection(wire, iDir, offer, accept));
	}

	private void add(WireConnection connection) {
		if (head == null) {
			head = connection;
			tail = connection;
		} else {
			tail.next = connection;
			tail = connection;
		}

		total++;

		if (heads[connection.iDir] == null) {
			heads[connection.iDir] = connection;
			flowTotal |= (1 << connection.iDir);
		}
	}

	/**
	 * Order-independent iteration over all connections.
	 */
	void forEach(Consumer<WireConnection> consumer) {
		for (WireConnection c = head; c != null; c = c.next) {
			consumer.accept(c);
		}
	}

	/**
	 * Direct access to the head of the connection linked list. Lets
	 * the hot Rust-batch path walk connections without allocating a
	 * lambda + capture per call. Only intended for that callsite —
	 * normal traversal should still use {@link #forEach}.
	 */
	WireConnection head() {
		return head;
	}

	/**
	 * Ordered iteration: visits connections grouped by the given
	 * update order's cardinal-neighbor sequence, so shape updates and
	 * other order-sensitive work observe vanilla-compatible sequencing.
	 */
	void forEach(Consumer<WireConnection> consumer, UpdateOrder updateOrder, int iFlowDir) {
		for (int iDir : updateOrder.cardinalNeighbors(iFlowDir)) {
			for (WireConnection c = heads[iDir]; c != null && c.iDir == iDir; c = c.next) {
				consumer.accept(c);
			}
		}
	}
}
