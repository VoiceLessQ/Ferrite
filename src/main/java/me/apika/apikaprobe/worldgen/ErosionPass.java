package me.apika.apikaprobe.worldgen;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import me.apika.apikaprobe.RustBridge;

import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.Heightmap;
import net.minecraft.world.chunk.Chunk;

/**
 * Hydraulic erosion post-pass for a vanilla-populated chunk.
 *
 * Runs per-chunk, 16x16 window (Phase A — known tight; will produce
 * border seams because droplets fall off the edge quickly). If the
 * visual result is too subtle, next step is to widen to 48x48 including
 * neighbor columns.
 */
public final class ErosionPass {
	private static final Logger LOGGER = LoggerFactory.getLogger("apikaprobe");

	private static final int CHUNK_X = 16;
	private static final int CHUNK_Z = 16;
	private static final int MIN_Y = -64;
	private static final int MAX_Y = 320;
	private static final int MIN_Y_SAFE = MIN_Y + 1;
	private static final int MAX_Y_SAFE = MAX_Y - 1;
	private static final int BUFFER_BYTES = CHUNK_X * CHUNK_Z * 4;

	// Empirical starting point — 2000 droplets in a 16x16 grid is enough
	// to see *some* smoothing per chunk while staying fast. Tune after test.
	private static final int ITERATIONS = 2000;

	private static final BlockState AIR = Blocks.AIR.getDefaultState();
	private static final BlockState DIRT = Blocks.DIRT.getDefaultState();
	private static final BlockState GRASS = Blocks.GRASS_BLOCK.getDefaultState();

	private ErosionPass() {}

	public static void apply(Chunk chunk, long seed) {
		if (!RustBridge.NATIVE_AVAILABLE) {
			return;
		}

		ByteBuffer buf = ByteBuffer
				.allocateDirect(BUFFER_BYTES)
				.order(ByteOrder.nativeOrder());

		int[] oldHeights = new int[CHUNK_X * CHUNK_Z];
		Heightmap hm = chunk.getHeightmap(Heightmap.Type.WORLD_SURFACE_WG);

		for (int x = 0; x < CHUNK_X; x++) {
			for (int z = 0; z < CHUNK_Z; z++) {
				// Heightmap.get returns the Y of the first air block *above*
				// the surface — the solid top sits at one less than that.
				int y = hm.get(x, z) - 1;
				if (y < MIN_Y_SAFE) y = MIN_Y_SAFE;
				if (y > MAX_Y_SAFE) y = MAX_Y_SAFE;

				int idx = x * CHUNK_Z + z;
				oldHeights[idx] = y;
				buf.putFloat(idx * 4, (float) y);
			}
		}

		long t0 = System.nanoTime();
		RustBridge.erodeHeightmap(buf, CHUNK_X, CHUNK_Z, ITERATIONS, seed);
		long elapsedUs = (System.nanoTime() - t0) / 1_000L;
		LOGGER.info("[rust-erosion] {}x{} eroded in {} us", CHUNK_X, CHUNK_Z, elapsedUs);

		BlockPos.Mutable pos = new BlockPos.Mutable();
		int startX = chunk.getPos().getStartX();
		int startZ = chunk.getPos().getStartZ();

		for (int x = 0; x < CHUNK_X; x++) {
			for (int z = 0; z < CHUNK_Z; z++) {
				int idx = x * CHUNK_Z + z;
				int oldY = oldHeights[idx];

				float raw = buf.getFloat(idx * 4);
				int newY = Math.round(raw);
				if (newY < MIN_Y_SAFE) newY = MIN_Y_SAFE;
				if (newY > MAX_Y_SAFE) newY = MAX_Y_SAFE;

				if (newY == oldY) {
					continue;
				}

				int worldX = startX + x;
				int worldZ = startZ + z;

				if (newY > oldY) {
					// Raised — fill new column with dirt, cap with grass.
					for (int y = oldY + 1; y < newY; y++) {
						pos.set(worldX, y, worldZ);
						chunk.setBlockState(pos, DIRT, 0);
					}
					pos.set(worldX, newY, worldZ);
					chunk.setBlockState(pos, GRASS, 0);
				} else {
					// Lowered — blow away everything from newY+1 up to old surface,
					// put a fresh grass cap at newY.
					for (int y = newY + 1; y <= oldY; y++) {
						pos.set(worldX, y, worldZ);
						chunk.setBlockState(pos, AIR, 0);
					}
					pos.set(worldX, newY, worldZ);
					chunk.setBlockState(pos, GRASS, 0);
				}
			}
		}
	}
}
