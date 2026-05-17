package me.apika.apikaprobe.navigation;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

import me.apika.apikaprobe.RustBridge;
import me.apika.apikaprobe.monitor.MonitorLog;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.pathfinder.Node;
import net.minecraft.world.level.pathfinder.Path;
import net.minecraft.world.level.block.CarpetBlock;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.FenceBlock;
import net.minecraft.world.level.block.FenceGateBlock;
import net.minecraft.world.level.block.BigDripleafBlock;
import net.minecraft.world.level.block.LadderBlock;
import net.minecraft.world.level.block.LeavesBlock;
import net.minecraft.world.level.block.LiquidBlock;
import net.minecraft.world.level.block.ScaffoldingBlock;
import net.minecraft.world.level.block.SlabBlock;
import net.minecraft.world.level.block.StairBlock;
import net.minecraft.world.level.block.TrapDoorBlock;
import net.minecraft.world.level.block.WallBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.SlabType;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.level.material.Fluids;

/**
 * Facade over the navigation-cache JNI surface. Translates BlockState
 * objects into the primitive payload that the Rust side dispatches on.
 *
 * Block-kind discriminants encode pathfinding-relevant categories.
 * Properties that do not affect walkability (redstone POWER,
 * WATERLOGGED on shaped blocks, FACING for blocks where facing is
 * cosmetic) are excluded so kind stays stable across those transitions
 * and the kind-diff filter drops the events. The redstone-clock
 * validation gate (session 2 step 4) tests this property directly.
 *
 * Constants here MUST match the Rust side in {@code nav_cache.rs}.
 */
public final class NavigationCacheBridge {
	private NavigationCacheBridge() {}

	public static final byte KIND_AIR             = 0;
	public static final byte KIND_OPAQUE_FULL     = 1;  // stone/dirt/wood — opaque, full collision
	public static final byte KIND_DOOR            = 2;  // open/closed handled via door_state map, not kind
	public static final byte KIND_SLAB_BOTTOM     = 3;
	public static final byte KIND_SLAB_TOP        = 4;
	public static final byte KIND_STAIRS          = 5;
	public static final byte KIND_FENCE           = 6;
	public static final byte KIND_FENCE_GATE      = 7;
	public static final byte KIND_WALL            = 8;
	public static final byte KIND_TRAPDOOR_OPEN   = 9;
	public static final byte KIND_TRAPDOOR_CLOSED = 10;
	public static final byte KIND_LADDER          = 11;
	public static final byte KIND_WATER           = 12;
	public static final byte KIND_LAVA            = 13;
	public static final byte KIND_LEAVES          = 14;
	public static final byte KIND_CARPET          = 15;
	public static final byte KIND_SCAFFOLDING     = 16;
	public static final byte KIND_OTHER           = 17;  // rails, redstone, flat decorations, anything unclassified

	public static byte encodeBlockKind(BlockState state) {
		if (state.isAir()) return KIND_AIR;

		Block block = state.getBlock();

		if (block instanceof DoorBlock) return KIND_DOOR;
		if (block instanceof TrapDoorBlock) {
			return state.getValue(BlockStateProperties.OPEN) ? KIND_TRAPDOOR_OPEN : KIND_TRAPDOOR_CLOSED;
		}
		if (block instanceof FenceGateBlock) return KIND_FENCE_GATE;
		if (block instanceof FenceBlock) return KIND_FENCE;
		if (block instanceof WallBlock) return KIND_WALL;
		if (block instanceof BigDripleafBlock) return KIND_TRAPDOOR_CLOSED; // vanilla returns TRAPDOOR for it
		if (block instanceof LadderBlock) return KIND_LADDER;
		if (block instanceof LeavesBlock) return KIND_LEAVES;
		if (block instanceof CarpetBlock) return KIND_CARPET;
		if (block instanceof ScaffoldingBlock) return KIND_SCAFFOLDING;

		if (block instanceof SlabBlock) {
			SlabType type = state.getValue(BlockStateProperties.SLAB_TYPE);
			return switch (type) {
				case BOTTOM -> KIND_SLAB_BOTTOM;
				case TOP -> KIND_SLAB_TOP;
				case DOUBLE -> KIND_OPAQUE_FULL;  // double slab is structurally a full block
			};
		}
		if (block instanceof StairBlock) return KIND_STAIRS;

		if (block instanceof LiquidBlock) {
			FluidState fluid = state.getFluidState();
			if (fluid.is(Fluids.WATER) || fluid.is(Fluids.FLOWING_WATER)) return KIND_WATER;
			if (fluid.is(Fluids.LAVA) || fluid.is(Fluids.FLOWING_LAVA)) return KIND_LAVA;
		}

		// canOcclude is the cheap proxy for "opaque solid full block" without
		// world context. Not perfectly accurate (some opaque non-full blocks
		// slip through) but adequate for first-cut. Refine when the villager
		// evaluator integration in session 3 reveals specific misses.
		if (state.canOcclude()) return KIND_OPAQUE_FULL;

		return KIND_OTHER;
	}

	public static void onBlockChanged(BlockPos pos, BlockState oldState, BlockState newState) {
		if (!RustBridge.NATIVE_AVAILABLE) return;

		byte oldKind = encodeBlockKind(oldState);
		byte newKind = encodeBlockKind(newState);
		int newOpen = (newKind == KIND_DOOR)
				? (newState.getValue(BlockStateProperties.OPEN) ? 1 : 0)
				: -1;

		RustBridge.navOnBlockChanged(
			pos.getX(), pos.getY(), pos.getZ(),
			oldKind, newKind,
			newOpen
		);
	}

	public static void updateDoorState(long sectionId, int cellIdx, boolean isOpen) {
		if (!RustBridge.NATIVE_AVAILABLE) return;
		RustBridge.navUpdateDoorState(sectionId, cellIdx, isOpen);
	}

	// ── Section snapshot ──────────────────────────────────────────────────────

	// Reused across all snapshots; pathfinding is single-threaded (server tick).
	private static final ByteBuffer SNAPSHOT_BUF =
		ByteBuffer.allocateDirect(4096 * 4).order(ByteOrder.LITTLE_ENDIAN);

	/** Walk every cell in the 16×16×16 section, encode block kind, hand off
	 *  to Rust. Idempotent — call before path requests; eviction on block
	 *  change (via onBlockChanged) ensures stale sections are refilled. */
	public static void snapshotSection(int chunkX, int sectionY, int chunkZ, BlockGetter level) {
		if (!RustBridge.NATIVE_AVAILABLE) return;
		SNAPSHOT_BUF.clear();
		BlockPos.MutableBlockPos mpos = new BlockPos.MutableBlockPos();
		int baseX = chunkX << 4;
		int baseY = sectionY << 4;
		int baseZ = chunkZ << 4;
		// Cell index order matches Rust: (ly<<8)|(lz<<4)|lx
		for (int ly = 0; ly < 16; ly++) {
			for (int lz = 0; lz < 16; lz++) {
				for (int lx = 0; lx < 16; lx++) {
					byte kind = encodeBlockKind(
						level.getBlockState(mpos.set(baseX + lx, baseY + ly, baseZ + lz)));
					SNAPSHOT_BUF.put(kind);
					SNAPSHOT_BUF.put((byte) 0); // hazard_kind
					SNAPSHOT_BUF.put((byte) 0); // movement_cost
					SNAPSHOT_BUF.put((byte) 0); // top_face_y
				}
			}
		}
		SNAPSHOT_BUF.flip();
		RustBridge.navFillSection(chunkX, sectionY, chunkZ, SNAPSHOT_BUF);
	}

	// ── Parity comparison ─────────────────────────────────────────────────────

	/** Returns the block_kind byte for a world position, or -1 if uncached. */
	public static byte getCellKind(int x, int y, int z) {
		if (!RustBridge.NATIVE_AVAILABLE) return -1;
		return RustBridge.navGetCellKind(x, y, z);
	}

	/** Coarse PathType category from our cache, used for parity comparison.
	 *  Returns one of: WALKABLE, BLOCKED, DOOR, FENCE, WATER, LAVA,
	 *  LEAVES, OPEN, TRAPDOOR, UNKNOWN. */
	public static String predictCategory(byte cellKind, byte floorKind) {
		switch (cellKind) {
			case KIND_AIR:
				switch (floorKind) {
					case KIND_OPAQUE_FULL: case KIND_STAIRS:
					case KIND_SLAB_TOP: case KIND_SLAB_BOTTOM: case KIND_CARPET:
						return "WALKABLE";
					case KIND_DOOR:   return "DOOR";
					case KIND_WATER:  return "BLOCKED"; // land mob can't stand on water surface
					case KIND_LAVA:   return "OPEN"; // AIR above lava
					case KIND_FENCE: case KIND_FENCE_GATE: case KIND_WALL:
						return "FENCE";
					default:          return "OPEN";
				}
			case KIND_OPAQUE_FULL:   return "BLOCKED";
			case KIND_DOOR:          return "DOOR";
			case KIND_FENCE:
			case KIND_FENCE_GATE:
			case KIND_WALL:          return "FENCE";
			case KIND_LEAVES:        return "LEAVES";
			// LiquidBlock.isPathfindable(LAND) = false, so pure water blocks
			// return BLOCKED from getPathTypeFromState for land mobs.
			case KIND_WATER:         return "BLOCKED";
			case KIND_LAVA:          return "LAVA"; // lava has early-return before isPathfindable
			case KIND_TRAPDOOR_OPEN:
			case KIND_TRAPDOOR_CLOSED: return "TRAPDOOR";
			case KIND_SLAB_BOTTOM:
			case KIND_SLAB_TOP:
			case KIND_STAIRS:        return "BLOCKED";
			case KIND_LADDER:
			case KIND_CARPET:
			case KIND_SCAFFOLDING:   return "OPEN";
			// KIND_OTHER blocks are unclassified non-occluding blocks (flowers, rails,
			// pressure plates, glass pane, etc.) — passable for land mobs → OPEN.
			default:                 return "OPEN";
		}
	}

	/** Map vanilla PathType name to a coarse category matching predictCategory. */
	private static String vanillaCategory(String typeName) {
		if (typeName.equals("WALKABLE") || typeName.equals("ON_TOP_OF_TRAPDOOR"))
			return "WALKABLE";
		if (typeName.startsWith("DOOR") || typeName.equals("WALKABLE_DOOR"))
			return "DOOR";
		if (typeName.equals("BLOCKED"))   return "BLOCKED";
		if (typeName.equals("FENCE"))     return "FENCE";
		if (typeName.equals("OPEN"))      return "OPEN";
		if (typeName.equals("WATER"))     return "WATER";
		if (typeName.equals("LAVA"))      return "LAVA";
		if (typeName.equals("LEAVES"))    return "LEAVES";
		if (typeName.equals("TRAPDOOR"))  return "TRAPDOOR";
		return "OTHER";
	}

	/** For each node in the vanilla path, compare our cache prediction
	 *  against the PathType vanilla assigned. Logs a summary line via
	 *  MonitorLog. Call after vanilla findPath returns. */
	public static void checkPathParity(Path path) {
		if (!RustBridge.NATIVE_AVAILABLE || path == null) return;
		int total = path.getNodeCount();
		int ok = 0, fail = 0, uncached = 0;
		for (int i = 0; i < total; i++) {
			Node node = path.getNode(i);
			byte cellKind  = getCellKind(node.x, node.y, node.z);
			if (cellKind == -1) { uncached++; continue; }
			byte floorKind = getCellKind(node.x, node.y - 1, node.z);
			String predicted = predictCategory(cellKind, floorKind);
			String actual    = vanillaCategory(node.type.name());
			if (predicted.equals(actual)) {
				ok++;
			} else {
				fail++;
				MonitorLog.info(
					"[nav-cache] parity FAIL node=({},{},{}) predicted={} vanilla={} cellKind={} floorKind={}",
					node.x, node.y, node.z, predicted, actual, cellKind, floorKind);
			}
		}
		MonitorLog.info(
			"[nav-cache] parity {} path_len={} ok={} fail={} uncached={}",
			fail == 0 ? "OK" : "FAIL", total, ok, fail, uncached);
	}
}
