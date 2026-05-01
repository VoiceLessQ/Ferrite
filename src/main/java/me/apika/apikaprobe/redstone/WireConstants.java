/*
 * Adapted from Alternate Current (https://github.com/SpaceWalkerRS/alternate-current)
 * Copyright (c) 2022 Space Walker — MIT License
 *
 * Extracts constants and the NodeProvider interface originally nested
 * inside alternate.current.wire.WireHandler so session 1 of Ferrite's
 * AC port can compile the graph representation without pulling in the
 * full ~1,000-line update algorithm. Session 2 will port the algorithm
 * and reference these same constants.
 */
package me.apika.apikaprobe.redstone;

import net.minecraft.core.Direction;

/**
 * Shared constants for the redstone wire graph: cardinal direction
 * indexing, the flow-in-to-flow-out lookup table, and the vanilla
 * shape-update order.
 *
 * <p>Also houses the {@link NodeProvider} interface — AC keeps this
 * inside {@code WireHandler} as a nested type, but Ferrite splits it
 * so data-structure files can reference it without depending on the
 * algorithm file.
 */
public final class WireConstants {

	private WireConstants() {}

	/** Minimum power level a wire can hold. */
	public static final int SIGNAL_MIN = 0;
	/** Maximum power level a wire can hold. */
	public static final int SIGNAL_MAX = 15;
	/** Decay per wire hop. */
	public static final int POWER_STEP = 1;

	/**
	 * Cardinal + vertical direction indexing used throughout the AC
	 * algorithm. The horizontal indices 0-3 are ordered clockwise,
	 * which lets the algorithm convert between relative and absolute
	 * directions with arithmetic:
	 * if {@code forward} is an index, {@code (forward + 1) & 0b11} is
	 * {@code right}, {@code (forward + 2) & 0b11} is {@code back}, etc.
	 */
	public static final class Directions {

		private Directions() {}

		public static final Direction[] ALL        = { Direction.WEST, Direction.NORTH, Direction.EAST, Direction.SOUTH, Direction.DOWN, Direction.UP };
		public static final Direction[] HORIZONTAL = { Direction.WEST, Direction.NORTH, Direction.EAST, Direction.SOUTH };

		public static final int WEST  = 0b000; // 0
		public static final int NORTH = 0b001; // 1
		public static final int EAST  = 0b010; // 2
		public static final int SOUTH = 0b011; // 3
		public static final int DOWN  = 0b100; // 4
		public static final int UP    = 0b101; // 5

		/** Returns the index of the opposite direction. */
		public static int iOpposite(int iDir) {
			return iDir ^ (0b10 >>> (iDir >>> 2));
		}

		/** Reverse lookup: Direction → index. Returns -1 if not found. */
		public static int index(Direction dir) {
			for (int i = 0; i < ALL.length; i++) {
				if (dir == ALL[i]) {
					return i;
				}
			}
			return -1;
		}
	}

	/**
	 * Takes a 4-bit mask of incoming-flow cardinal directions and
	 * returns the determined outgoing-flow direction index, or -1 if
	 * ambiguous.
	 *
	 * <p>Rules (same as AC):
	 * <ul>
	 *   <li>1 incoming direction: that direction flows out.</li>
	 *   <li>2 non-opposite directions: the more-clockwise one flows out.</li>
	 *   <li>3 directions with two opposites: opposites cancel, remainder flows.</li>
	 *   <li>Otherwise: ambiguous, return -1.</li>
	 * </ul>
	 */
	public static final int[] FLOW_IN_TO_FLOW_OUT = {
		-1,                // 0b0000: -                     -> x
		Directions.WEST,   // 0b0001: west                  -> west
		Directions.NORTH,  // 0b0010: north                 -> north
		Directions.NORTH,  // 0b0011: west/north            -> north
		Directions.EAST,   // 0b0100: east                  -> east
		-1,                // 0b0101: west/east             -> x
		Directions.EAST,   // 0b0110: north/east            -> east
		Directions.NORTH,  // 0b0111: west/north/east       -> north
		Directions.SOUTH,  // 0b1000: south                 -> south
		Directions.WEST,   // 0b1001: west/south            -> west
		-1,                // 0b1010: north/south           -> x
		Directions.WEST,   // 0b1011: west/north/south      -> west
		Directions.SOUTH,  // 0b1100: east/south            -> south
		Directions.SOUTH,  // 0b1101: west/east/south       -> south
		Directions.EAST,   // 0b1110: north/east/south      -> east
		-1,                // 0b1111: west/north/east/south -> x
	};

	/**
	 * Order in which shape updates are emitted, matching vanilla's
	 * behavior so MC-11193 and related positional-bias bugs stay
	 * absent in AC's output.
	 */
	public static final int[] SHAPE_UPDATE_ORDER = {
		Directions.WEST, Directions.EAST,
		Directions.NORTH, Directions.SOUTH,
		Directions.DOWN, Directions.UP
	};

	/**
	 * Provides neighbor lookups during graph traversal. The algorithm
	 * doesn't care how neighbors are cached (by pos hash, by array
	 * index, etc.) — it just needs {@code getNeighbor(node, iDir)} to
	 * return the neighbor in the given direction index.
	 */
	@FunctionalInterface
	public interface NodeProvider {
		Node getNeighbor(Node node, int iDir);
	}
}
