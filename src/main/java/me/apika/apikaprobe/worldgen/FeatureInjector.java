package me.apika.apikaprobe.worldgen;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

import me.apika.apikaprobe.RustBridge;

import net.minecraft.block.BlockState;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.Heightmap;
import net.minecraft.world.chunk.Chunk;

/**
 * Applies Rust-decided block placements to a chunk.
 *
 * Rust writes into a shared direct ByteBuffer using the protocol:
 *     [count: i32] [localX: i32, y: i32, localZ: i32, blockId: i32] * count
 * with localX / localZ in chunk-local [0..16) coordinates.
 *
 * Thread-safety note: the shared static BUFFER is safe today because
 * MC's chunk-gen workers don't call this method concurrently on the
 * same chunk, and each call fills-then-reads the buffer before returning.
 * If we ever parallelize further or discover contention, switch BUFFER
 * to a ThreadLocal. Flagged as a known architectural risk.
 */
public final class FeatureInjector {
	private static final int MAX_PLACEMENTS = 256;
	private static final int PLACEMENT_BYTES = 16; // 4 × int32
	private static final int BUFFER_BYTES = 4 + MAX_PLACEMENTS * PLACEMENT_BYTES;

	private static final ByteBuffer BUFFER =
		ByteBuffer.allocateDirect(BUFFER_BYTES).order(ByteOrder.nativeOrder());

	private FeatureInjector() {}

	public static void apply(Chunk chunk, long seed) {
		if (!RustBridge.NATIVE_AVAILABLE) {
			return;
		}

		BUFFER.clear();
		BUFFER.rewind();

		int chunkX = chunk.getPos().x;
		int chunkZ = chunk.getPos().z;
		RustBridge.injectFeatures(BUFFER, seed, chunkX, chunkZ);

		int count = BUFFER.getInt(0);
		if (count <= 0) {
			return;
		}
		if (count > MAX_PLACEMENTS) {
			count = MAX_PLACEMENTS;
		}

		int startX = chunk.getPos().getStartX();
		int startZ = chunk.getPos().getStartZ();
		BlockPos.Mutable pos = new BlockPos.Mutable();

		Heightmap hm = chunk.getHeightmap(Heightmap.Type.WORLD_SURFACE_WG);
		BlockPos.Mutable scanPos = new BlockPos.Mutable();

		for (int i = 0; i < count; i++) {
			int base = 4 + i * PLACEMENT_BYTES;
			int localX = BUFFER.getInt(base);
			int y = BUFFER.getInt(base + 4);
			int localZ = BUFFER.getInt(base + 8);
			int blockId = BUFFER.getInt(base + 12);

			if (blockId == 0) {
				continue; // air — nothing to place
			}

			// Start from the heightmap's recorded surface, then scan down past
			// any air that erosion (or other post-noise passes) may have carved
			// out but the heightmap hasn't yet been updated to reflect.
			int surfaceY = hm.get(localX, localZ) - 1;
			int worldX = startX + localX;
			int worldZ = startZ + localZ;
			while (surfaceY > -64
					&& chunk.getBlockState(scanPos.set(worldX, surfaceY, worldZ)).isAir()) {
				surfaceY--;
			}

			if (surfaceY < 63) {
				continue; // underwater (or bottom of world) — skip
			}

			// Rust computes y relative to nominal surface of 64; offset to real surface.
			int adjustedY = y + (surfaceY - 64);

			BlockState state = BlockRegistry.get(blockId);
			pos.set(worldX, adjustedY, worldZ);
			chunk.setBlockState(pos, state, 0);
		}
	}
}
