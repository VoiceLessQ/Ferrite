package me.apika.apikaprobe.worldgen;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import me.apika.apikaprobe.RustBridge;

import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.registry.tag.FluidTags;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.ChunkRegion;
import net.minecraft.world.Heightmap;
import net.minecraft.world.chunk.Chunk;

/**
 * Hydraulic erosion pass for a freshly noise-populated chunk.
 *
 * Reads a 48×48 heightmap (the 3×3 chunk neighborhood) so droplets
 * have room to travel across chunk borders instead of dying at them.
 * Only the center 16×16 is written back — neighbors are read-only.
 *
 * Called from @Inject(at=HEAD) on ChunkGenerator.buildSurface so that
 * vanilla's surface rules paint biome-correct tops (grass, sand, snow,
 * etc.) on whatever shape we leave behind. This means the writeback
 * needs no biome logic — raised columns get STONE, lowered columns
 * get AIR, and vanilla handles the rest.
 *
 * Water-topped columns are detected at extraction time and skipped at
 * writeback via a sentinel value, preserving oceans and lakes. A
 * delta threshold suppresses sub-block scarring across gentle terrain.
 *
 * Known gaps:
 *   - Seafloor erosion: water columns are skipped entirely, so
 *     seafloors never erode even where they should.
 *   - ByteBuffer allocated per chunk — consider ThreadLocal pool
 *     if profiling shows allocation pressure.
 */
public final class ErosionPass {
	private static final Logger LOGGER = LoggerFactory.getLogger("apikaprobe");

	private static final int CHUNK_SIDE = 16;
	private static final int WINDOW = 48;
	private static final int CENTER_OFFSET = 16; // where the center chunk begins inside the window
	private static final int BUFFER_BYTES = WINDOW * WINDOW * 4;

	private static final int MIN_Y = -64;
	private static final int MAX_Y = 320;
	private static final int MIN_Y_SAFE = MIN_Y + 1;
	private static final int MAX_Y_SAFE = MAX_Y - 1;
	private static final int MAX_LOWER_PER_COLUMN = 3;
	private static final int MIN_DELTA = 2; // skip writeback for sub-2-block moves

	// Sentinel stored in oldHeightsCenter for columns whose top block is water
	// (or any fluid tagged as water). Writeback treats this as "do not modify".
	private static final int WATER_SENTINEL = -1;

	// Dropped from 10_000 — that value scarred the terrain uniformly. 1_500
	// droplets on 48×48 gives ~0.65 per cell; erosion concentrates on slopes
	// and leaves flat terrain alone.
	private static final int ITERATIONS = 1_500;

	private static final BlockState AIR = Blocks.AIR.getDefaultState();
	private static final BlockState STONE = Blocks.STONE.getDefaultState();

	private ErosionPass() {}

	public static void apply(ChunkRegion region, Chunk chunk, long seed) {
		if (!RustBridge.NATIVE_AVAILABLE) {
			return;
		}

		int centerChunkX = chunk.getPos().x;
		int centerChunkZ = chunk.getPos().z;
		int startX = chunk.getPos().getStartX();
		int startZ = chunk.getPos().getStartZ();

		// Cache the 3×3 neighborhood once — avoids 2304 repeated region lookups.
		Chunk[] neighbors = new Chunk[9];
		for (int dcx = 0; dcx < 3; dcx++) {
			for (int dcz = 0; dcz < 3; dcz++) {
				neighbors[dcx * 3 + dcz] =
					region.getChunk(centerChunkX - 1 + dcx, centerChunkZ - 1 + dcz);
			}
		}

		ByteBuffer buf = ByteBuffer
				.allocateDirect(BUFFER_BYTES)
				.order(ByteOrder.nativeOrder());

		int[] oldHeightsCenter = new int[CHUNK_SIDE * CHUNK_SIDE];
		BlockPos.Mutable checkPos = new BlockPos.Mutable();

		// Extract 48×48 heightmap. For center-chunk cells, also probe the top
		// block: water-topped columns store WATER_SENTINEL so writeback skips
		// them. Non-center cells only need the height for erosion's sake.
		for (int dz = 0; dz < WINDOW; dz++) {
			for (int dx = 0; dx < WINDOW; dx++) {
				int chunkIdxX = dx / CHUNK_SIDE;
				int chunkIdxZ = dz / CHUNK_SIDE;
				int localX = dx % CHUNK_SIDE;
				int localZ = dz % CHUNK_SIDE;

				Chunk c = neighbors[chunkIdxX * 3 + chunkIdxZ];
				Heightmap hm = c.getHeightmap(Heightmap.Type.WORLD_SURFACE_WG);
				int y = hm.get(localX, localZ) - 1;
				if (y < MIN_Y_SAFE) y = MIN_Y_SAFE;
				if (y > MAX_Y_SAFE) y = MAX_Y_SAFE;

				buf.putFloat((dz * WINDOW + dx) * 4, (float) y);

				if (chunkIdxX == 1 && chunkIdxZ == 1) {
					int worldX = startX + localX;
					int worldZ = startZ + localZ;
					checkPos.set(worldX, y, worldZ);
					BlockState top = c.getBlockState(checkPos);
					if (top.getFluidState().isIn(FluidTags.WATER)) {
						oldHeightsCenter[localZ * CHUNK_SIDE + localX] = WATER_SENTINEL;
					} else {
						oldHeightsCenter[localZ * CHUNK_SIDE + localX] = y;
					}
				}
			}
		}

		long t0 = System.nanoTime();
		RustBridge.erodeHeightmap(buf, WINDOW, WINDOW, ITERATIONS, seed);
		long elapsedUs = (System.nanoTime() - t0) / 1_000L;
		LOGGER.info("[rust-erosion] {}x{} eroded in {} us", WINDOW, WINDOW, elapsedUs);

		// Writeback — center chunk only. Vanilla's surface rules run next and
		// will paint biome-correct tops on whatever shape we produced.
		BlockPos.Mutable pos = new BlockPos.Mutable();

		for (int lx = 0; lx < CHUNK_SIDE; lx++) {
			for (int lz = 0; lz < CHUNK_SIDE; lz++) {
				int oldY = oldHeightsCenter[lz * CHUNK_SIDE + lx];

				// Skip water-topped columns — the sentinel was set at extraction.
				if (oldY == WATER_SENTINEL) {
					continue;
				}

				int bufX = lx + CENTER_OFFSET;
				int bufZ = lz + CENTER_OFFSET;
				int idx = bufZ * WINDOW + bufX;

				float raw = buf.getFloat(idx * 4);
				int newY = Math.round(raw);
				if (newY < MIN_Y_SAFE) newY = MIN_Y_SAFE;
				if (newY > MAX_Y_SAFE) newY = MAX_Y_SAFE;
				if (newY < oldY - MAX_LOWER_PER_COLUMN) newY = oldY - MAX_LOWER_PER_COLUMN;

				// Ignore sub-MIN_DELTA moves — 1-block noise was scarring gentle terrain.
				if (Math.abs(newY - oldY) < MIN_DELTA) {
					continue;
				}

				int worldX = startX + lx;
				int worldZ = startZ + lz;

				if (newY > oldY) {
					// Raised — fill new material with stone.
					for (int y = oldY + 1; y <= newY; y++) {
						pos.set(worldX, y, worldZ);
						chunk.setBlockState(pos, STONE, 0);
					}
				} else {
					// Lowered — blow away everything above the new surface.
					for (int y = newY + 1; y <= oldY; y++) {
						pos.set(worldX, y, worldZ);
						chunk.setBlockState(pos, AIR, 0);
					}
				}
			}
		}
	}
}
