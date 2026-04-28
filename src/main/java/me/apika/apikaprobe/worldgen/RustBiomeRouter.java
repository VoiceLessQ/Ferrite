package me.apika.apikaprobe.worldgen;

import me.apika.apikaprobe.worldgen.chunk.ChunkPrewarmer;
import me.apika.apikaprobe.bridge.ExampleMod;
import me.apika.apikaprobe.RustBridge;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

import net.minecraft.registry.entry.RegistryEntry;
import net.minecraft.world.biome.Biome;

/**
 * Routes vanilla biome lookups to the Rust path during chunk gen.
 *
 * <p>{@link WorldgenStateBootstrap} populates {@link #holdersById} with
 * the per-Rust-ID {@code RegistryEntry<Biome>} extracted from the
 * overworld's MultiNoiseBiomeSource parameter list. The mixin on
 * {@code MultiNoiseBiomeSource.getBiome(...)} consults this router
 * whenever {@link #ENABLED} is true.
 *
 * <p>Toggle: {@code /ferrite worldgen rust on|off}. Default OFF until
 * we've measured a chunkgen win; bit-exact correctness is independently
 * proven by {@code /ferrite biome rust} matching vanilla.
 */
public final class RustBiomeRouter {
	private RustBiomeRouter() {}

	public static volatile boolean ENABLED = false;

	/** Set once at world load by {@link WorldgenStateBootstrap}. Index =
	 *  Rust biome ID; entry = the live holder vanilla would have returned. */
	private static volatile RegistryEntry<Biome>[] holdersById = null;

	// Diagnostic counters live on the COLD path only. Per-call hit
	// tracking was removed: under vanilla's parallel chunkgen worker pool,
	// hot-path AtomicLong incrs cause cache-line ping-pong across
	// Worker-Main-N cores. JNI cost (fired once per 16-cell slab refill)
	// remains tracked. See docs/PARALLELISM_AUDIT.md.
	private static final java.util.concurrent.atomic.AtomicLong missCount =
			new java.util.concurrent.atomic.AtomicLong();
	private static final java.util.concurrent.atomic.AtomicLong totalNs =
			new java.util.concurrent.atomic.AtomicLong();
	private static final java.util.concurrent.atomic.AtomicLong slabRefillCount =
			new java.util.concurrent.atomic.AtomicLong();

	@SuppressWarnings("unchecked")
	public static void install(RegistryEntry<?>[] entries) {
		holdersById = (RegistryEntry<Biome>[]) entries;
	}

	/** Per-thread 4×4 slab cache. Vanilla's biome supplier is called
	 *  ~256 times per chunk during populateBiomes (4×4 quart cells × N
	 *  Y-slabs). Caching the 4×4 grid for the active (chunkX,chunkZ,qY)
	 *  amortizes JNI dispatch + Rayon parallelism across 16 cells per
	 *  Rust call. Single-slot per thread because the dominant access
	 *  pattern is Y-outer, XZ-inner — hit rate ~15/16 in that case. */
	private static final ThreadLocal<SlabCache> slabTl =
			ThreadLocal.withInitial(SlabCache::new);

	private static final class SlabCache {
		int chunkX = Integer.MIN_VALUE;
		int chunkZ = Integer.MIN_VALUE;
		int qY = Integer.MIN_VALUE;
		final int[] ids = new int[16];
		final ByteBuffer buf = ByteBuffer.allocateDirect(16 * 4)
				.order(ByteOrder.nativeOrder());
		boolean filled;
	}

	/** Per-call hot path. Returns null if router not installed, Rust
	 *  state not finalized, ID out of range, or rust returned -1.
	 *
	 *  <p>Lookup order:
	 *  <ol>
	 *    <li>Prewarm cache (if enabled) — pure memory read, no JNI</li>
	 *    <li>Per-thread slab cache — 1 JNI per 16-cell slab</li>
	 *  </ol>
	 *  The prewarm cache is populated off the chunkgen worker by
	 *  {@link ChunkPrewarmer} as the player approaches; on hit, vanilla's
	 *  biome supplier returns at memory speed. */
	public static RegistryEntry<Biome> tryRoute(int blockX, int blockY, int blockZ) {
		if (!ENABLED) return null;
		RegistryEntry<Biome>[] table = holdersById;
		if (table == null) return null;

		// Cache-first: try prewarm cache. Lock-free read; on hit we
		// skip JNI entirely.
		int prewarmId = ChunkPrewarmer.lookup(blockX, blockY, blockZ);
		if (prewarmId >= 0 && prewarmId < table.length) {
			return table[prewarmId];
		}

		// quart and chunk coords. blockX>>2 = quart-X; quart>>2 = chunk-X.
		int qx = blockX >> 2;
		int qy = blockY >> 2;
		int qz = blockZ >> 2;
		int cx = qx >> 2;
		int cz = qz >> 2;

		SlabCache c = slabTl.get();
		if (!c.filled || c.chunkX != cx || c.chunkZ != cz || c.qY != qy) {
			long t0 = System.nanoTime();
			int written = RustBridge.findBiomeRegionRust(
					cx << 4,        // origin block-X
					qy << 2,        // origin block-Y (back to block from quart)
					cz << 4,        // origin block-Z
					4, 4,           // 4×4 cells
					4,              // step = 4 blocks (one quart cell)
					c.buf);
			long elapsed = System.nanoTime() - t0;
			totalNs.addAndGet(elapsed);
			slabRefillCount.incrementAndGet();
			if (written != 16) {
				missCount.incrementAndGet();
				return null;
			}
			c.buf.position(0);
			c.buf.asIntBuffer().get(c.ids, 0, 16);
			c.chunkX = cx;
			c.chunkZ = cz;
			c.qY = qy;
			c.filled = true;
		}
		int localX = qx & 3;
		int localZ = qz & 3;
		int id = c.ids[localZ * 4 + localX];
		if (id < 0 || id >= table.length) {
			missCount.incrementAndGet();
			return null;
		}
		return table[id];
	}

	public static int size() {
		RegistryEntry<Biome>[] table = holdersById;
		return table == null ? 0 : table.length;
	}

	/** Returns {slabRefills, misses, totalNs} and resets counters.
	 *  Per-call hit count was removed because hot-path AtomicLong incrs
	 *  cause cache-line ping-pong under vanilla's parallel chunkgen
	 *  worker pool. Slab-refill count gives a useful diagnostic at
	 *  ~1/16 the call rate without touching the hot path. */
	public static long[] drainStats() {
		long r = slabRefillCount.getAndSet(0);
		long m = missCount.getAndSet(0);
		long t = totalNs.getAndSet(0);
		return new long[]{r, m, t};
	}
}
