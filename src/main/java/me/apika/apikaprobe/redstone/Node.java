/*
 * Adapted from Alternate Current (https://github.com/SpaceWalkerRS/alternate-current)
 * Copyright (c) 2022 Space Walker — MIT License
 *
 * Yarn-remapped for Fabric 1.21.11. See docs/AC_YARN_MAPPINGS.md for
 * the mapping table used during this port.
 */
package me.apika.apikaprobe.redstone;

import java.util.Arrays;

import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.core.BlockPos;

/**
 * A {@code Node} represents a block in the world — redstone-related or
 * otherwise. It also holds a few cached flags that speed up lookups in
 * the wire update algorithm.
 *
 * <p>Subclasses: {@link WireNode} wraps a redstone-wire block with
 * additional power-tracking state. Plain {@code Node}s wrap everything
 * else (air, dust-adjacent solid blocks, torches, repeaters, …).
 *
 * @author Space Walker (original, Mojmap)
 */
public class Node {

	// Flags encoding node type. Packed into a single int to minimize
	// per-node memory and because these are checked on every traversal.
	private static final int CONDUCTOR = 0b01;
	private static final int SOURCE    = 0b10;

	final ServerLevel world;
	final Node[] neighbors;

	BlockPos pos;
	BlockState state;
	boolean invalid;

	private int flags;

	/** Previous node in the priority queue (doubly-linked). */
	Node prev_node;
	/** Next node in the priority queue (doubly-linked). */
	Node next_node;
	/** Priority with which this node was queued. */
	int priority;
	/** The wire that enqueued this node for an update. */
	WireNode neighborWire;

	Node(ServerLevel world) {
		this.world = world;
		this.neighbors = new Node[WireConstants.Directions.ALL.length];
	}

	@Override
	public boolean equals(Object obj) {
		if (this == obj) {
			return true;
		}
		if (!(obj instanceof Node)) {
			return false;
		}
		Node node = (Node) obj;
		return world == node.world && pos.equals(node.pos);
	}

	@Override
	public int hashCode() {
		return pos.hashCode();
	}

	/**
	 * Re-bind this node to a new (pos, state). Asserts the state is
	 * not a wire (wire nodes live in {@link WireNode}, which overrides
	 * this to throw).
	 */
	Node set(BlockPos pos, BlockState state, boolean clearNeighbors) {
		if (state.is(Blocks.REDSTONE_WIRE)) {
			throw new IllegalStateException("Cannot update a regular Node to a WireNode!");
		}

		if (clearNeighbors) {
			Arrays.fill(neighbors, null);
		}

		this.pos = pos.immutable();
		this.state = state;
		this.invalid = false;
		this.flags = 0;

		// isSolidBlock in yarn == isRedstoneConductor in Mojmap.
		if (this.state.isRedstoneConductor(this.world, this.pos)) {
			this.flags |= CONDUCTOR;
		}
		if (this.state.isSignalSource()) {
			this.flags |= SOURCE;
		}

		return this;
	}

	/** Priority used when enqueuing; overridden by {@link WireNode}. */
	int priority() {
		return neighborWire.priority;
	}

	public boolean isWire() {
		return false;
	}

	public boolean isConductor() {
		return (flags & CONDUCTOR) != 0;
	}

	public boolean isSignalSource() {
		return (flags & SOURCE) != 0;
	}

	public WireNode asWire() {
		throw new UnsupportedOperationException("Not a WireNode!");
	}
}
