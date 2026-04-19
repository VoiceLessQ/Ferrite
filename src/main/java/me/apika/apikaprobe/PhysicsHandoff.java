package me.apika.apikaprobe;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.ChainBlock;
import net.minecraft.block.FenceBlock;
import net.minecraft.block.FenceGateBlock;
import net.minecraft.block.PaneBlock;
import net.minecraft.block.ScaffoldingBlock;
import net.minecraft.block.WallBlock;
import net.minecraft.entity.Entity;
import net.minecraft.util.function.BooleanBiFunction;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import net.minecraft.util.shape.VoxelShape;
import net.minecraft.util.shape.VoxelShapes;
import net.minecraft.world.World;

/**
 * Zero-copy handoff between Java and Rust for Entity.adjustMovementForCollisions.
 *
 * Three pre-allocated direct ByteBuffers, native byte order, reused every
 * tick. Sizing is worst-case: 2048 entities, 64×64×64 snapshot region,
 * 1024 palette entries, 4096 cell-local AABBs.
 *
 * Buffer layouts (must match rust/mod/src/physics.rs):
 *
 * snapshotBuf (header = 36 bytes, then variable):
 *   +0   i32   originX / originY / originZ
 *   +12  u32   sizeX / sizeY / sizeZ
 *   +24  u32   paletteCount (P)
 *   +28  u32   aabbTableLen (A)   — total AABB records in palette
 *   +32  u32   snapshotTickId     — Java increments on rebuild
 *   +36         cells[V × u16]    — Y-major: (y*sz + z)*sx + x
 *   +…          paletteOffsets[P × u32]
 *   +…          paletteCounts[P × u8]    (0 = empty/air, 255 = complex)
 *   +…          aabbTable[6A × f32]      — cell-local [0,1] coords
 *
 * requestBuf (stride = 96 bytes per entity):
 *   +0   u32   entityId        +4   u32 pad
 *   +8   f64×6 aabbMin/aabbMax
 *   +56  f64×3 motion
 *   +80  f32   maxStepUp
 *   +84  u8    flags           +85 3B pad
 *   +88  u32   snapshotTickId  +92 u32 pad
 *
 * resultBuf (stride = 40 bytes per entity — f64 alignment forces 40, not 32):
 *   +0   u32   entityId        +4   u32 pad
 *   +8   f64×3 adjustedMotion
 *   +32  u8    flags           +33 [7B pad for 40B stride]
 */
public final class PhysicsHandoff {

	// --- Worst-case sizing -------------------------------------------------

	public static final int MAX_ENTITIES = 2048;
	// 2,097,152 cells × 2 B = 4 MB cell array. Accommodates a horde spread
	// over a 128-block region on any axis — covers the stress test and
	// realistic mob farms without clustering logic.
	public static final int MAX_SNAPSHOT_DIM = 128;
	public static final int MAX_SNAPSHOT_CELLS = MAX_SNAPSHOT_DIM * MAX_SNAPSHOT_DIM * MAX_SNAPSHOT_DIM;
	public static final int MAX_PALETTE_ENTRIES = 1024;
	public static final int MAX_AABBS_IN_PALETTE = 4096;

	/** Last rejected dimensions, for diagnostics. */
	public static volatile int LAST_REJECTED_SX = 0;
	public static volatile int LAST_REJECTED_SY = 0;
	public static volatile int LAST_REJECTED_SZ = 0;

	// --- Strides (must match Rust) -----------------------------------------

	public static final int SNAPSHOT_HEADER_BYTES = 36;
	public static final int REQUEST_STRIDE = 96;
	public static final int RESULT_STRIDE = 40;

	// --- Snapshot AABB mark meanings ---------------------------------------

	public static final int PALETTE_EMPTY = 0;
	public static final int PALETTE_COMPLEX_COUNT = 255;

	// --- Request / result flag bits (must match Rust) ----------------------

	public static final int REQ_FLAG_ON_GROUND    = 1 << 0;
	public static final int REQ_FLAG_SKIP_STEP_UP = 1 << 1;

	public static final int RES_FLAG_HORIZONTAL = 1 << 0;
	public static final int RES_FLAG_VERTICAL   = 1 << 1;
	public static final int RES_FLAG_STEPPED_UP = 1 << 2;
	public static final int RES_FLAG_FALLBACK   = 1 << 3;

	// --- Pre-allocated buffers ---------------------------------------------

	private static final int SNAPSHOT_CAPACITY =
			SNAPSHOT_HEADER_BYTES
			+ MAX_SNAPSHOT_CELLS * 2                  // cells u16
			+ MAX_PALETTE_ENTRIES * 4                 // paletteOffsets u32
			+ MAX_PALETTE_ENTRIES                     // paletteCounts u8
			+ MAX_AABBS_IN_PALETTE * 6 * 4;           // aabbTable f32

	private static final int REQUEST_CAPACITY = MAX_ENTITIES * REQUEST_STRIDE;
	private static final int RESULT_CAPACITY  = MAX_ENTITIES * RESULT_STRIDE;

	public static final ByteBuffer SNAPSHOT_BUF =
			ByteBuffer.allocateDirect(SNAPSHOT_CAPACITY).order(ByteOrder.nativeOrder());
	public static final ByteBuffer REQUEST_BUF =
			ByteBuffer.allocateDirect(REQUEST_CAPACITY).order(ByteOrder.nativeOrder());
	public static final ByteBuffer RESULT_BUF =
			ByteBuffer.allocateDirect(RESULT_CAPACITY).order(ByteOrder.nativeOrder());

	// --- Scratch state (reused each buildSnapshot call) --------------------
	// Temporaries kept as fields to avoid per-tick allocation. Not thread-safe
	// — server tick is single-threaded by design.

	private static final Map<BlockState, Integer> STATE_TO_PALETTE = new HashMap<>(128);
	private static final List<float[]> PALETTE_AABBS = new ArrayList<>(128);
	private static final int[] PALETTE_COUNTS_TMP = new int[MAX_PALETTE_ENTRIES];
	private static final BlockPos.Mutable SCRATCH_POS = new BlockPos.Mutable();

	private PhysicsHandoff() {}

	// =========================================================================
	// Public API
	// =========================================================================

	/**
	 * Builds the world snapshot into SNAPSHOT_BUF. Returns true on success,
	 * false if the region would exceed MAX_SNAPSHOT_CELLS or the palette
	 * would exceed MAX_PALETTE_ENTRIES — caller must fall back to vanilla
	 * for this tick.
	 */
	public static boolean buildSnapshot(World world, List<? extends Entity> mobs, int snapshotTickId) {
		if (mobs.isEmpty()) {
			// Empty snapshot still valid — write a zero-size header so requests
			// sharing this tickId with no colliders all fall back cleanly.
			writeEmptyHeader(snapshotTickId);
			return true;
		}

		// 1. AABB union of all mob bounding boxes.
		double minX = Double.POSITIVE_INFINITY, minY = Double.POSITIVE_INFINITY, minZ = Double.POSITIVE_INFINITY;
		double maxX = Double.NEGATIVE_INFINITY, maxY = Double.NEGATIVE_INFINITY, maxZ = Double.NEGATIVE_INFINITY;
		for (Entity e : mobs) {
			Box b = e.getBoundingBox();
			if (b.minX < minX) minX = b.minX;
			if (b.minY < minY) minY = b.minY;
			if (b.minZ < minZ) minZ = b.minZ;
			if (b.maxX > maxX) maxX = b.maxX;
			if (b.maxY > maxY) maxY = b.maxY;
			if (b.maxZ > maxZ) maxZ = b.maxZ;
		}

		// 2. Expand by 4 blocks (max query radius per design).
		int ox = (int) Math.floor(minX) - 4;
		int oy = (int) Math.floor(minY) - 4;
		int oz = (int) Math.floor(minZ) - 4;
		int ex = (int) Math.floor(maxX) + 4;
		int ey = (int) Math.floor(maxY) + 4;
		int ez = (int) Math.floor(maxZ) + 4;
		int sx = ex - ox + 1;
		int sy = ey - oy + 1;
		int sz = ez - oz + 1;

		// Hard per-bucket clamp: one chunk column + 4-block expansion on each
		// side stays under 24×384×24. A bucket that blows this limit means
		// a mob moved >20 blocks since pre-tick (teleport, long-range
		// knockback) — that mob falls back to vanilla rather than growing
		// the snapshot unbounded.
		if (sx > 24 || sz > 24 || sy > 384) {
			LAST_REJECTED_SX = sx;
			LAST_REJECTED_SY = sy;
			LAST_REJECTED_SZ = sz;
			return false;
		}

		if ((long) sx * sy * sz > MAX_SNAPSHOT_CELLS) {
			LAST_REJECTED_SX = sx;
			LAST_REJECTED_SY = sy;
			LAST_REJECTED_SZ = sz;
			return false;
		}

		// 3. Reset palette scratch. Index 0 reserved for empty/air.
		STATE_TO_PALETTE.clear();
		PALETTE_AABBS.clear();
		PALETTE_AABBS.add(null); // slot 0: empty
		PALETTE_COUNTS_TMP[0] = 0;
		int aabbTableLen = 0;

		// 4. Iterate cells Y-major, populate palette + cells array.
		int cellCount = sx * sy * sz;
		int[] cells = borrowCellScratch(cellCount);

		for (int ly = 0; ly < sy; ly++) {
			int wy = oy + ly;
			for (int lz = 0; lz < sz; lz++) {
				int wz = oz + lz;
				for (int lx = 0; lx < sx; lx++) {
					int wx = ox + lx;
					SCRATCH_POS.set(wx, wy, wz);
					BlockState state = world.getBlockState(SCRATCH_POS);
					int pidx;
					if (state.isAir()) {
						pidx = 0;
					} else {
						Integer cached = STATE_TO_PALETTE.get(state);
						if (cached != null) {
							pidx = cached;
						} else {
							pidx = registerPaletteEntry(state, world, SCRATCH_POS);
							if (pidx < 0) {
								// Palette overflow → fallback.
								return false;
							}
							if (PALETTE_COUNTS_TMP[pidx] != PALETTE_COMPLEX_COUNT) {
								aabbTableLen += PALETTE_COUNTS_TMP[pidx];
								if (aabbTableLen > MAX_AABBS_IN_PALETTE) {
									return false;
								}
							}
						}
					}
					cells[(ly * sz + lz) * sx + lx] = pidx;
				}
			}
		}

		// 5. Write header + sections in order.
		int paletteCount = PALETTE_AABBS.size();
		SNAPSHOT_BUF.clear();
		SNAPSHOT_BUF.putInt(ox);
		SNAPSHOT_BUF.putInt(oy);
		SNAPSHOT_BUF.putInt(oz);
		SNAPSHOT_BUF.putInt(sx);
		SNAPSHOT_BUF.putInt(sy);
		SNAPSHOT_BUF.putInt(sz);
		SNAPSHOT_BUF.putInt(paletteCount);
		SNAPSHOT_BUF.putInt(aabbTableLen);
		SNAPSHOT_BUF.putInt(snapshotTickId);

		// cells
		for (int i = 0; i < cellCount; i++) {
			SNAPSHOT_BUF.putShort((short) cells[i]);
		}

		// paletteOffsets — computed while we write paletteCounts below.
		int paletteOffsetsPos = SNAPSHOT_BUF.position();
		SNAPSHOT_BUF.position(paletteOffsetsPos + paletteCount * 4);

		// paletteCounts
		for (int i = 0; i < paletteCount; i++) {
			SNAPSHOT_BUF.put((byte) PALETTE_COUNTS_TMP[i]);
		}

		// aabbTable — concat all per-palette AABB arrays, recording offsets.
		int aabbStart = SNAPSHOT_BUF.position();
		int runningOffset = 0;
		for (int i = 0; i < paletteCount; i++) {
			float[] arr = PALETTE_AABBS.get(i);
			int savedPos = SNAPSHOT_BUF.position();
			SNAPSHOT_BUF.position(paletteOffsetsPos + i * 4);
			SNAPSHOT_BUF.putInt(runningOffset);
			SNAPSHOT_BUF.position(savedPos);
			if (arr != null) {
				for (float f : arr) SNAPSHOT_BUF.putFloat(f);
				runningOffset += arr.length / 6;
			}
		}

		// Unused variable warning-suppressor: aabbStart is informational.
		assert aabbStart > paletteOffsetsPos;

		SNAPSHOT_BUF.flip();
		return true;
	}

	/**
	 * Fills the request buffer with one 96B entry per input. `motions`,
	 * `stepUps`, and `flags` are parallel arrays to `mobs`. Caller supplies
	 * the same snapshotTickId used in the most recent buildSnapshot.
	 */
	public static void buildRequests(
			List<? extends Entity> mobs,
			Vec3d[] motions,
			float[] stepUps,
			byte[] flags,
			int snapshotTickId) {
		int n = mobs.size();
		if (n > MAX_ENTITIES) {
			throw new IllegalStateException("too many entities: " + n + " > " + MAX_ENTITIES);
		}

		REQUEST_BUF.clear();
		for (int i = 0; i < n; i++) {
			Entity e = mobs.get(i);
			Box aabb = e.getBoundingBox();
			Vec3d m = motions[i];

			REQUEST_BUF.putInt(e.getId());     // +0  entityId
			REQUEST_BUF.putInt(0);             // +4  pad
			REQUEST_BUF.putDouble(aabb.minX);  // +8
			REQUEST_BUF.putDouble(aabb.minY);  // +16
			REQUEST_BUF.putDouble(aabb.minZ);  // +24
			REQUEST_BUF.putDouble(aabb.maxX);  // +32
			REQUEST_BUF.putDouble(aabb.maxY);  // +40
			REQUEST_BUF.putDouble(aabb.maxZ);  // +48
			REQUEST_BUF.putDouble(m.x);        // +56
			REQUEST_BUF.putDouble(m.y);        // +64
			REQUEST_BUF.putDouble(m.z);        // +72
			REQUEST_BUF.putFloat(stepUps[i]);  // +80
			REQUEST_BUF.put(flags[i]);         // +84
			REQUEST_BUF.put((byte) 0);         // +85 pad
			REQUEST_BUF.put((byte) 0);         // +86 pad
			REQUEST_BUF.put((byte) 0);         // +87 pad
			REQUEST_BUF.putInt(snapshotTickId);// +88
			REQUEST_BUF.putInt(0);             // +92 pad
		}
		REQUEST_BUF.flip();
	}

	/**
	 * Reads `count` result entries from RESULT_BUF. Returns parallel arrays.
	 * `adjusted[i]` is null iff result[i] has the FALLBACK flag set — caller
	 * should run vanilla adjustMovementForCollisions for that entity.
	 */
	public static Results readResults(int count) {
		if (count > MAX_ENTITIES) {
			throw new IllegalStateException("count exceeds MAX_ENTITIES");
		}
		RESULT_BUF.position(0);
		RESULT_BUF.limit(count * RESULT_STRIDE);

		Vec3d[] adjusted = new Vec3d[count];
		byte[] flags = new byte[count];
		int[] entityIds = new int[count];
		int fallbackCount = 0;

		for (int i = 0; i < count; i++) {
			int base = i * RESULT_STRIDE;
			entityIds[i] = RESULT_BUF.getInt(base);
			double x = RESULT_BUF.getDouble(base + 8);
			double y = RESULT_BUF.getDouble(base + 16);
			double z = RESULT_BUF.getDouble(base + 24);
			byte f = RESULT_BUF.get(base + 32);
			flags[i] = f;
			if ((f & RES_FLAG_FALLBACK) == 0) {
				adjusted[i] = new Vec3d(x, y, z);
			} else {
				fallbackCount++;
			}
		}
		return new Results(adjusted, flags, entityIds, fallbackCount);
	}

	/**
	 * Single-entity fast path for the per-entity dispatch model. Writes one
	 * 96B request entry to the head of REQUEST_BUF. Server tick is single-
	 * threaded, so overwriting on every call is safe.
	 */
	public static void fillSingleRequest(
			Entity e,
			Vec3d motion,
			float maxStepUp,
			byte flags,
			int snapshotTickId) {
		Box aabb = e.getBoundingBox();
		REQUEST_BUF.clear();
		REQUEST_BUF.putInt(e.getId());
		REQUEST_BUF.putInt(0);
		REQUEST_BUF.putDouble(aabb.minX);
		REQUEST_BUF.putDouble(aabb.minY);
		REQUEST_BUF.putDouble(aabb.minZ);
		REQUEST_BUF.putDouble(aabb.maxX);
		REQUEST_BUF.putDouble(aabb.maxY);
		REQUEST_BUF.putDouble(aabb.maxZ);
		REQUEST_BUF.putDouble(motion.x);
		REQUEST_BUF.putDouble(motion.y);
		REQUEST_BUF.putDouble(motion.z);
		REQUEST_BUF.putFloat(maxStepUp);
		REQUEST_BUF.put(flags);
		REQUEST_BUF.put((byte) 0);
		REQUEST_BUF.put((byte) 0);
		REQUEST_BUF.put((byte) 0);
		REQUEST_BUF.putInt(snapshotTickId);
		REQUEST_BUF.putInt(0);
		REQUEST_BUF.flip();
	}

	/**
	 * Reads the single-entity result at offset 0. Returns null when the
	 * FALLBACK flag is set — caller must invoke vanilla.
	 */
	public static Vec3d readSingleResult() {
		byte f = RESULT_BUF.get(32);
		if ((f & RES_FLAG_FALLBACK) != 0) return null;
		double x = RESULT_BUF.getDouble(8);
		double y = RESULT_BUF.getDouble(16);
		double z = RESULT_BUF.getDouble(24);
		return new Vec3d(x, y, z);
	}

	public static final class Results {
		public final Vec3d[] adjusted;     // null entry = must fall back
		public final byte[] flags;         // raw result flag byte per entity
		public final int[] entityIds;      // echoed from request
		public final int fallbackCount;
		Results(Vec3d[] adjusted, byte[] flags, int[] entityIds, int fallbackCount) {
			this.adjusted = adjusted;
			this.flags = flags;
			this.entityIds = entityIds;
			this.fallbackCount = fallbackCount;
		}
	}

	// =========================================================================
	// Internals
	// =========================================================================

	/**
	 * Resolves a BlockState to a palette index, allocating a new one if not
	 * already present. Returns -1 if the palette is full. Blocks with
	 * neighbor-dependent shapes are marked PALETTE_COMPLEX_COUNT.
	 */
	private static int registerPaletteEntry(BlockState state, World world, BlockPos pos) {
		int idx = PALETTE_AABBS.size();
		if (idx >= MAX_PALETTE_ENTRIES) return -1;

		if (isNeighborDependent(state.getBlock())) {
			STATE_TO_PALETTE.put(state, idx);
			PALETTE_AABBS.add(null);                            // no AABBs for complex
			PALETTE_COUNTS_TMP[idx] = PALETTE_COMPLEX_COUNT;
			return idx;
		}

		VoxelShape shape = state.getCollisionShape(world, pos);
		if (shape.isEmpty()) {
			// Non-air but empty collision (e.g., flowers). Treat as empty.
			STATE_TO_PALETTE.put(state, idx);
			PALETTE_AABBS.add(null);
			PALETTE_COUNTS_TMP[idx] = 0;
			return idx;
		}

		// Extract axis-aligned boxes from the shape. VoxelShape.getBoundingBoxes()
		// returns a List<Box> in world coords (but because we queried at `pos`,
		// boxes are translated by pos — we need cell-local [0,1] coords).
		List<Box> boxes = shape.getBoundingBoxes();
		int count = boxes.size();
		if (count > 32) {
			// Pathological shape — fall back rather than serialize 200 AABBs.
			STATE_TO_PALETTE.put(state, idx);
			PALETTE_AABBS.add(null);
			PALETTE_COUNTS_TMP[idx] = PALETTE_COMPLEX_COUNT;
			return idx;
		}

		float[] arr = new float[count * 6];
		double ox = pos.getX(), oy = pos.getY(), oz = pos.getZ();
		for (int i = 0; i < count; i++) {
			Box b = boxes.get(i);
			int base = i * 6;
			arr[base    ] = (float) (b.minX - ox);
			arr[base + 1] = (float) (b.minY - oy);
			arr[base + 2] = (float) (b.minZ - oz);
			arr[base + 3] = (float) (b.maxX - ox);
			arr[base + 4] = (float) (b.maxY - oy);
			arr[base + 5] = (float) (b.maxZ - oz);
		}
		STATE_TO_PALETTE.put(state, idx);
		PALETTE_AABBS.add(arr);
		PALETTE_COUNTS_TMP[idx] = count;
		return idx;
	}

	/**
	 * Whitelist of block classes whose collision shape depends on neighbor
	 * state. These get a fallback marker in the palette so Rust skips them
	 * and Java handles that entity normally.
	 */
	private static boolean isNeighborDependent(Block block) {
		return block instanceof FenceBlock
				|| block instanceof WallBlock
				|| block instanceof PaneBlock
				|| block instanceof FenceGateBlock
				|| block instanceof ChainBlock
				|| block instanceof ScaffoldingBlock;
	}

	private static int[] cellScratch = new int[MAX_SNAPSHOT_CELLS];
	private static int[] borrowCellScratch(int needed) {
		if (cellScratch.length < needed) {
			cellScratch = new int[needed];
		}
		return cellScratch;
	}

	private static void writeEmptyHeader(int snapshotTickId) {
		SNAPSHOT_BUF.clear();
		for (int i = 0; i < 6; i++) SNAPSHOT_BUF.putInt(0); // origin+size
		SNAPSHOT_BUF.putInt(0); // paletteCount
		SNAPSHOT_BUF.putInt(0); // aabbTableLen
		SNAPSHOT_BUF.putInt(snapshotTickId);
		SNAPSHOT_BUF.flip();
	}

	// Silence unused-import tooling; VoxelShapes + BooleanBiFunction are
	// kept in case we later need VoxelShapes.matchesAnywhere for complex-
	// shape normalization.
	@SuppressWarnings("unused")
	private static final Class<?> KEEP_VOXEL_SHAPES = VoxelShapes.class;
	@SuppressWarnings("unused")
	private static final Class<?> KEEP_BOOLEAN_BIFUNCTION = BooleanBiFunction.class;
}
