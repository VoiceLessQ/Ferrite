/*
 * Adapted from Alternate Current (https://github.com/SpaceWalkerRS/alternate-current)
 * Copyright (c) 2022 Space Walker — MIT License
 *
 * Yarn-remapped for Fabric 1.21.11. Pure data class — no vanilla API
 * touch, so the port is essentially the original file with package
 * and header updated.
 */
package me.apika.apikaprobe.redstone;

/**
 * Represents a directed connection between a wire (the {@code owner} of
 * this connection, stored on the owning {@link WireConnectionManager})
 * and one of its cardinal neighbors. Each connection records both
 * directions of flow independently via {@link #offer} / {@link #accept}
 * because a solid block between two wires can gate power in one
 * direction but not the other.
 *
 * <p>Connections are linked together in a singly-linked list via
 * {@link #next} so the manager can iterate them without an allocation.
 *
 * @author Space Walker (original, Mojmap)
 */
public class WireConnection {

	/** The connected wire. */
	final WireNode wire;
	/** Cardinal-direction index to the connected wire. */
	final int iDir;
	/** True if the owner of this connection can push power to {@link #wire}. */
	final boolean offer;
	/** True if {@link #wire} can push power back to the owner. */
	final boolean accept;

	/** Next connection in the owning manager's linked sequence. */
	WireConnection next;

	WireConnection(WireNode wire, int iDir, boolean offer, boolean accept) {
		this.wire = wire;
		this.iDir = iDir;
		this.offer = offer;
		this.accept = accept;
	}
}
