/*
 * Adapted from Alternate Current (https://github.com/SpaceWalkerRS/alternate-current)
 * Copyright (c) 2022 Space Walker — MIT License
 *
 * Yarn-remapped for Fabric 1.21.11. Key yarn renames:
 *   net.minecraft.core.BlockPos                  -> net.minecraft.core.BlockPos
 *   net.minecraft.server.level.ServerLevel       -> net.minecraft.server.level.ServerLevel
 *   net.minecraft.world.level.chunk.ChunkAccess  -> net.minecraft.world.level.chunk.Chunk
 *   net.minecraft.world.level.chunk.LevelChunkSection -> net.minecraft.world.level.chunk.LevelChunkSection
 *   net.minecraft.world.level.chunk.status.ChunkStatus -> net.minecraft.world.level.chunk.ChunkStatus
 *   level.getChunkSource().blockChanged(pos)     -> world.getChunkManager().markForUpdate(pos)
 *   chunk.markUnsaved()                          -> chunk.markNeedsSaving()
 *   chunk.getSections()                          -> chunk.getSectionArray()
 *   prevState.updateIndirectNeighbourShapes      -> prevState.prepare
 *   state.updateNeighbourShapes                  -> state.updateNeighbors
 *   Block.UPDATE_CLIENTS                         -> Block.NOTIFY_LISTENERS
 *   level.getMinY()                              -> world.getBottomY()
 *   level.getMaxY()                              -> world.getTopYInclusive()
 */
package me.apika.apikaprobe.redstone;

import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.BlockState;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.chunk.Chunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.ChunkStatus;

/**
 * Small bypass for vanilla's {@code Level.setBlockState} optimized for
 * the wire-only case. Skips lighting checks, heightmap updates, and
 * block-entity bookkeeping — none of which apply to redstone dust —
 * and writes straight through to the chunk section.
 *
 * @author Space Walker (original, Mojmap)
 */
final class LevelHelper {

	private LevelHelper() {}

	/**
	 * Direct-to-section state write for redstone wire. Returns true
	 * if the block was actually changed (caller uses this to decide
	 * whether to trigger shape updates, etc.).
	 *
	 * @param updateNeighborShapes when true, emits the same sequence
	 *        of indirect + direct + indirect shape updates that
	 *        vanilla's setBlockState would have (needed if the wire
	 *        was newly added / removed, skipped for simple power
	 *        changes which don't alter connection shape).
	 */
	static boolean setWireState(ServerLevel world, BlockPos pos, BlockState state, boolean updateNeighborShapes) {
		int y = pos.getY();

		if (y < world.getBottomY() || y > world.getTopYInclusive()) {
			return false;
		}

		int x = pos.getX();
		int z = pos.getZ();
		int index = world.getSectionIndex(y);

		Chunk chunk = world.getChunk(x >> 4, z >> 4, ChunkStatus.FULL, true);
		LevelChunkSection section = chunk.getSectionArray()[index];

		if (section == null) {
			return false; // should never get here
		}

		BlockState prevState = section.setBlockState(x & 15, y & 15, z & 15, state);

		if (state == prevState) {
			return false;
		}

		// Notify clients of the block-state change.
		world.getChunkManager().markForUpdate(pos);
		// Mark the chunk for saving.
		chunk.markNeedsSaving();

		if (updateNeighborShapes) {
			prevState.prepare(world, pos, Block.NOTIFY_LISTENERS);
			state.updateNeighbors(world, pos, Block.NOTIFY_LISTENERS);
			state.prepare(world, pos, Block.NOTIFY_LISTENERS);
		}

		return true;
	}
}
