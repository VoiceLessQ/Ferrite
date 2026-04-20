/*
 * Adapted from Alternate Current (https://github.com/SpaceWalkerRS/alternate-current)
 * Copyright (c) 2022 Space Walker — MIT License
 *
 * Yarn-remapped for Fabric 1.21.11. Changes from upstream:
 *   - References WireConstants.Directions and WireConstants.NodeProvider
 *     (extracted from AC's WireHandler.Directions / .NodeProvider).
 *   - Otherwise logically identical; this is pure neighbor-iteration
 *     order definition with no vanilla-API touch.
 */
package me.apika.apikaprobe.redstone;

import java.util.Locale;
import java.util.function.Consumer;

import me.apika.apikaprobe.redstone.WireConstants.Directions;
import me.apika.apikaprobe.redstone.WireConstants.NodeProvider;

/**
 * Four deterministic orderings for the 24 neighbors around a wire node
 * (6 direct, 12 diagonal, 6 "far" two-out). Each variant defines a
 * specific traversal pattern to eliminate positional bias that would
 * otherwise make rotationally-symmetric contraptions behave differently
 * depending on orientation (part of fixing MC-11193).
 *
 * <p>The {@code HORIZONTAL_FIRST_*} variants visit horizontal neighbors
 * before vertical; {@code VERTICAL_FIRST_*} are the inverse. Within each
 * family, {@code _OUTWARD} visits direct → diagonal → far; {@code _INWARD}
 * is the reverse.
 *
 * @author Space Walker (original, Mojmap)
 */
public enum UpdateOrder {

	HORIZONTAL_FIRST_OUTWARD(
		new int[][] {
			new int[] { Directions.WEST , Directions.EAST , Directions.NORTH, Directions.SOUTH, Directions.DOWN, Directions.UP },
			new int[] { Directions.NORTH, Directions.SOUTH, Directions.EAST , Directions.WEST , Directions.DOWN, Directions.UP },
			new int[] { Directions.EAST , Directions.WEST , Directions.SOUTH, Directions.NORTH, Directions.DOWN, Directions.UP },
			new int[] { Directions.SOUTH, Directions.NORTH, Directions.WEST , Directions.EAST , Directions.DOWN, Directions.UP }
		},
		new int[][] {
			new int[] { Directions.WEST , Directions.EAST , Directions.NORTH, Directions.SOUTH },
			new int[] { Directions.NORTH, Directions.SOUTH, Directions.EAST , Directions.WEST  },
			new int[] { Directions.EAST , Directions.WEST , Directions.SOUTH, Directions.NORTH },
			new int[] { Directions.SOUTH, Directions.NORTH, Directions.WEST , Directions.EAST  }
		}
	) {
		@Override
		public void forEachNeighbor(NodeProvider nodes, Node source, int forward, Consumer<Node> action) {
			int rightward = (forward + 1) & 0b11;
			int backward  = (forward + 2) & 0b11;
			int leftward  = (forward + 3) & 0b11;
			int downward  = Directions.DOWN;
			int upward    = Directions.UP;

			Node front = nodes.getNeighbor(source, forward);
			Node right = nodes.getNeighbor(source, rightward);
			Node back  = nodes.getNeighbor(source, backward);
			Node left  = nodes.getNeighbor(source, leftward);
			Node below = nodes.getNeighbor(source, downward);
			Node above = nodes.getNeighbor(source, upward);

			// direct neighbors (6)
			action.accept(front);
			action.accept(back);
			action.accept(right);
			action.accept(left);
			action.accept(below);
			action.accept(above);

			// diagonal neighbors (12)
			action.accept(nodes.getNeighbor(front, rightward));
			action.accept(nodes.getNeighbor(back, leftward));
			action.accept(nodes.getNeighbor(front, leftward));
			action.accept(nodes.getNeighbor(back, rightward));
			action.accept(nodes.getNeighbor(front, downward));
			action.accept(nodes.getNeighbor(back, upward));
			action.accept(nodes.getNeighbor(front, upward));
			action.accept(nodes.getNeighbor(back, downward));
			action.accept(nodes.getNeighbor(right, downward));
			action.accept(nodes.getNeighbor(left, upward));
			action.accept(nodes.getNeighbor(right, upward));
			action.accept(nodes.getNeighbor(left, downward));

			// far neighbors (6)
			action.accept(nodes.getNeighbor(front, forward));
			action.accept(nodes.getNeighbor(back, backward));
			action.accept(nodes.getNeighbor(right, rightward));
			action.accept(nodes.getNeighbor(left, leftward));
			action.accept(nodes.getNeighbor(below, downward));
			action.accept(nodes.getNeighbor(above, upward));
		}
	},

	HORIZONTAL_FIRST_INWARD(
		new int[][] {
			new int[] { Directions.WEST , Directions.EAST , Directions.NORTH, Directions.SOUTH, Directions.DOWN, Directions.UP },
			new int[] { Directions.NORTH, Directions.SOUTH, Directions.EAST , Directions.WEST , Directions.DOWN, Directions.UP },
			new int[] { Directions.EAST , Directions.WEST , Directions.SOUTH, Directions.NORTH, Directions.DOWN, Directions.UP },
			new int[] { Directions.SOUTH, Directions.NORTH, Directions.WEST , Directions.EAST , Directions.DOWN, Directions.UP }
		},
		new int[][] {
			new int[] { Directions.WEST , Directions.EAST , Directions.NORTH, Directions.SOUTH },
			new int[] { Directions.NORTH, Directions.SOUTH, Directions.EAST , Directions.WEST  },
			new int[] { Directions.EAST , Directions.WEST , Directions.SOUTH, Directions.NORTH },
			new int[] { Directions.SOUTH, Directions.NORTH, Directions.WEST , Directions.EAST  }
		}
	) {
		@Override
		public void forEachNeighbor(NodeProvider nodes, Node source, int forward, Consumer<Node> action) {
			int rightward = (forward + 1) & 0b11;
			int backward  = (forward + 2) & 0b11;
			int leftward  = (forward + 3) & 0b11;
			int downward  = Directions.DOWN;
			int upward    = Directions.UP;

			Node front = nodes.getNeighbor(source, forward);
			Node right = nodes.getNeighbor(source, rightward);
			Node back  = nodes.getNeighbor(source, backward);
			Node left  = nodes.getNeighbor(source, leftward);
			Node below = nodes.getNeighbor(source, downward);
			Node above = nodes.getNeighbor(source, upward);

			// far neighbors (6)
			action.accept(nodes.getNeighbor(front, forward));
			action.accept(nodes.getNeighbor(back, backward));
			action.accept(nodes.getNeighbor(right, rightward));
			action.accept(nodes.getNeighbor(left, leftward));
			action.accept(nodes.getNeighbor(below, downward));
			action.accept(nodes.getNeighbor(above, upward));

			// diagonal neighbors (12)
			action.accept(nodes.getNeighbor(front, rightward));
			action.accept(nodes.getNeighbor(back, leftward));
			action.accept(nodes.getNeighbor(front, leftward));
			action.accept(nodes.getNeighbor(back, rightward));
			action.accept(nodes.getNeighbor(front, downward));
			action.accept(nodes.getNeighbor(back, upward));
			action.accept(nodes.getNeighbor(front, upward));
			action.accept(nodes.getNeighbor(back, downward));
			action.accept(nodes.getNeighbor(right, downward));
			action.accept(nodes.getNeighbor(left, upward));
			action.accept(nodes.getNeighbor(right, upward));
			action.accept(nodes.getNeighbor(left, downward));

			// direct neighbors (6)
			action.accept(front);
			action.accept(back);
			action.accept(right);
			action.accept(left);
			action.accept(below);
			action.accept(above);
		}
	},

	VERTICAL_FIRST_OUTWARD(
		new int[][] {
			new int[] { Directions.DOWN, Directions.UP, Directions.WEST , Directions.EAST , Directions.NORTH, Directions.SOUTH },
			new int[] { Directions.DOWN, Directions.UP, Directions.NORTH, Directions.SOUTH, Directions.EAST , Directions.WEST  },
			new int[] { Directions.DOWN, Directions.UP, Directions.EAST , Directions.WEST , Directions.SOUTH, Directions.NORTH },
			new int[] { Directions.DOWN, Directions.UP, Directions.SOUTH, Directions.NORTH, Directions.WEST , Directions.EAST  }
		},
		new int[][] {
			new int[] { Directions.WEST , Directions.EAST , Directions.NORTH, Directions.SOUTH },
			new int[] { Directions.NORTH, Directions.SOUTH, Directions.EAST , Directions.WEST  },
			new int[] { Directions.EAST , Directions.WEST , Directions.SOUTH, Directions.NORTH },
			new int[] { Directions.SOUTH, Directions.NORTH, Directions.WEST , Directions.EAST  }
		}
	) {
		@Override
		public void forEachNeighbor(NodeProvider nodes, Node source, int forward, Consumer<Node> action) {
			int rightward = (forward + 1) & 0b11;
			int backward  = (forward + 2) & 0b11;
			int leftward  = (forward + 3) & 0b11;
			int downward  = Directions.DOWN;
			int upward    = Directions.UP;

			Node front = nodes.getNeighbor(source, forward);
			Node right = nodes.getNeighbor(source, rightward);
			Node back  = nodes.getNeighbor(source, backward);
			Node left  = nodes.getNeighbor(source, leftward);
			Node below = nodes.getNeighbor(source, downward);
			Node above = nodes.getNeighbor(source, upward);

			// direct neighbors (6)
			action.accept(below);
			action.accept(above);
			action.accept(front);
			action.accept(back);
			action.accept(right);
			action.accept(left);

			// diagonal neighbors (12)
			action.accept(nodes.getNeighbor(below, forward));
			action.accept(nodes.getNeighbor(above, backward));
			action.accept(nodes.getNeighbor(below, backward));
			action.accept(nodes.getNeighbor(above, forward));
			action.accept(nodes.getNeighbor(below, rightward));
			action.accept(nodes.getNeighbor(above, leftward));
			action.accept(nodes.getNeighbor(below, leftward));
			action.accept(nodes.getNeighbor(above, rightward));
			action.accept(nodes.getNeighbor(front, rightward));
			action.accept(nodes.getNeighbor(back, leftward));
			action.accept(nodes.getNeighbor(front, leftward));
			action.accept(nodes.getNeighbor(back, rightward));

			// far neighbors (6)
			action.accept(nodes.getNeighbor(below, downward));
			action.accept(nodes.getNeighbor(above, upward));
			action.accept(nodes.getNeighbor(front, forward));
			action.accept(nodes.getNeighbor(back, backward));
			action.accept(nodes.getNeighbor(right, rightward));
			action.accept(nodes.getNeighbor(left, leftward));
		}
	},

	VERTICAL_FIRST_INWARD(
		new int[][] {
			new int[] { Directions.DOWN, Directions.UP, Directions.WEST , Directions.EAST , Directions.NORTH, Directions.SOUTH },
			new int[] { Directions.DOWN, Directions.UP, Directions.NORTH, Directions.SOUTH, Directions.EAST , Directions.WEST  },
			new int[] { Directions.DOWN, Directions.UP, Directions.EAST , Directions.WEST , Directions.SOUTH, Directions.NORTH },
			new int[] { Directions.DOWN, Directions.UP, Directions.SOUTH, Directions.NORTH, Directions.WEST , Directions.EAST  }
		},
		new int[][] {
			new int[] { Directions.WEST , Directions.EAST , Directions.NORTH, Directions.SOUTH },
			new int[] { Directions.NORTH, Directions.SOUTH, Directions.EAST , Directions.WEST  },
			new int[] { Directions.EAST , Directions.WEST , Directions.SOUTH, Directions.NORTH },
			new int[] { Directions.SOUTH, Directions.NORTH, Directions.WEST , Directions.EAST  }
		}
	) {
		@Override
		public void forEachNeighbor(NodeProvider nodes, Node source, int forward, Consumer<Node> action) {
			int rightward = (forward + 1) & 0b11;
			int backward  = (forward + 2) & 0b11;
			int leftward  = (forward + 3) & 0b11;
			int downward  = Directions.DOWN;
			int upward    = Directions.UP;

			Node front = nodes.getNeighbor(source, forward);
			Node right = nodes.getNeighbor(source, rightward);
			Node back  = nodes.getNeighbor(source, backward);
			Node left  = nodes.getNeighbor(source, leftward);
			Node below = nodes.getNeighbor(source, downward);
			Node above = nodes.getNeighbor(source, upward);

			// far neighbors (6)
			action.accept(nodes.getNeighbor(below, downward));
			action.accept(nodes.getNeighbor(above, upward));
			action.accept(nodes.getNeighbor(front, forward));
			action.accept(nodes.getNeighbor(back, backward));
			action.accept(nodes.getNeighbor(right, rightward));
			action.accept(nodes.getNeighbor(left, leftward));

			// diagonal neighbors (12)
			action.accept(nodes.getNeighbor(below, forward));
			action.accept(nodes.getNeighbor(above, backward));
			action.accept(nodes.getNeighbor(below, backward));
			action.accept(nodes.getNeighbor(above, forward));
			action.accept(nodes.getNeighbor(below, rightward));
			action.accept(nodes.getNeighbor(above, leftward));
			action.accept(nodes.getNeighbor(below, leftward));
			action.accept(nodes.getNeighbor(above, rightward));
			action.accept(nodes.getNeighbor(front, rightward));
			action.accept(nodes.getNeighbor(back, leftward));
			action.accept(nodes.getNeighbor(front, leftward));
			action.accept(nodes.getNeighbor(back, rightward));

			// direct neighbors (6)
			action.accept(below);
			action.accept(above);
			action.accept(front);
			action.accept(back);
			action.accept(right);
			action.accept(left);
		}
	};

	private final int[][] directNeighbors;
	private final int[][] cardinalNeighbors;

	UpdateOrder(int[][] directNeighbors, int[][] cardinalNeighbors) {
		this.directNeighbors = directNeighbors;
		this.cardinalNeighbors = cardinalNeighbors;
	}

	public String id() {
		return name().toLowerCase(Locale.ENGLISH);
	}

	public static UpdateOrder byId(String id) {
		return valueOf(id.toUpperCase(Locale.ENGLISH));
	}

	public int[] directNeighbors(int forward) {
		return directNeighbors[forward];
	}

	public int[] cardinalNeighbors(int forward) {
		return cardinalNeighbors[forward];
	}

	/**
	 * Iterate over neighbor positions around {@code source}, applying
	 * {@code action} in this order's deterministic sequence. Direct
	 * neighbors are always included; diagonals and far-neighbors are
	 * included per variant.
	 *
	 * <p>The iteration order is built from <em>relative</em> directions
	 * based on {@code forward}, not absolute compass directions — this
	 * is what eliminates the positional bias that plagues vanilla's
	 * redstone behavior.
	 */
	public abstract void forEachNeighbor(NodeProvider nodes, Node source, int forward, Consumer<Node> action);
}
