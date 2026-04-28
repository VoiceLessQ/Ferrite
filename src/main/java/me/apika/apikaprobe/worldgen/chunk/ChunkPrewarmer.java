package me.apika.apikaprobe.worldgen.chunk;

import me.apika.apikaprobe.RustBridge;

import me.apika.apikaprobe.bridge.ExampleMod;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Background biome-prediction pre-warmer.
 *
 * <p>Iterates chunks near the player, schedules per-chunk biome
 * predictions on a background pool, and throttles via a semaphore.
 * Each task pre-computes a chunk's biome map using the bit-exact Rust
 * biome lookup. By the time vanilla actually calls
 * {@code MultiNoiseBiomeSource.getBiome}, the answer is already in
 * memory; the chunkgen worker pays only a cache-lookup cost.
 *
 * <p>Cache layout: per (chunkX, chunkZ), an {@code int[]} of biome IDs
 * indexed as {@code (qyLocal * 16) + (qzLocal * 4) + qxLocal}. Y is
 * outer because vanilla's biome supplier walks (qx, qz) inner, qy outer.
 *
 * <p>Toggle: {@code /ferrite prewarm on|off}. Default OFF until
 * measurement confirms a win.
 */
public final class ChunkPrewarmer {
	private ChunkPrewarmer() {}

	public static volatile boolean ENABLED = false;

	/** Y range covered per chunk. Overworld: -64..319 → quart-Y -16..79.
	 *  Other dimensions use this same range; out-of-range queries miss. */
	public static final int MIN_QY = -16;
	public static final int MAX_QY = 79;
	public static final int Y_SLOTS = MAX_QY - MIN_QY + 1; // 96
	public static final int CELLS_PER_SLAB = 16;           // 4×4
	public static final int CELLS_PER_CHUNK = Y_SLOTS * CELLS_PER_SLAB;

	private static final int MAX_INFLIGHT = 16;
	/** Backpressure: stop scheduling when this many tasks are already
	 *  pending. With 4 workers × ~8ms warm = ~500/sec drain, 64 in queue
	 *  is ~125ms of buffer — enough to keep workers fed without dumping
	 *  hundreds of stale entries we'd evict before using. */
	private static final int SCHEDULE_BACKPRESSURE = 64;
	private static final int CACHE_LIMIT = 8192;

	private static final ConcurrentHashMap<Long, int[]> cache = new ConcurrentHashMap<>();

	/** One direct buffer per worker thread, allocated on first use, reused
	 *  for every warm task that thread runs. Replaces per-chunk
	 *  allocateDirect — that pattern produced thousands of off-heap buffers
	 *  whose reference processing caused irregular GC pauses ("freezes"). */
	private static final ThreadLocal<ByteBuffer> bufTl = ThreadLocal.withInitial(
			() -> ByteBuffer.allocateDirect(CELLS_PER_CHUNK * 4).order(ByteOrder.nativeOrder()));
	/** Tracks inflight (cx,cz) keys so we don't double-schedule. */
	private static final ConcurrentHashMap<Long, Boolean> inflight = new ConcurrentHashMap<>();
	private static final Semaphore concurrent = new Semaphore(MAX_INFLIGHT);

	private static final AtomicLong cacheHits = new AtomicLong();
	private static final AtomicLong cacheMisses = new AtomicLong();
	private static final AtomicLong chunksWarmed = new AtomicLong();
	private static final AtomicLong warmTotalNs = new AtomicLong();
	private static final AtomicInteger workerSeq = new AtomicInteger();

	private static volatile ExecutorService pool;

	/** Daemon-thread pool sized to half of available cores, capped at 4.
	 *  Cap protects single-player from thrashing the chunkgen workers. */
	public static synchronized void start() {
		if (pool != null) return;
		int workers = Math.max(1, Math.min(4, Runtime.getRuntime().availableProcessors() / 2));
		ThreadFactory tf = r -> {
			Thread t = new Thread(r, "ferrite-prewarm-" + workerSeq.incrementAndGet());
			t.setDaemon(true);
			t.setPriority(Thread.NORM_PRIORITY - 1);
			return t;
		};
		pool = Executors.newFixedThreadPool(workers, tf);
		ExampleMod.LOGGER.info("[prewarm] started with {} worker(s)", workers);
	}

	/** Schedule a prewarm for (cx,cz) if not already cached or inflight,
	 *  AND the queue isn't saturated. Backpressure (SCHEDULE_BACKPRESSURE)
	 *  prevents the trigger from dumping hundreds of stale entries that
	 *  the workers can't drain — the next tick will pick them up if
	 *  still relevant. */
	public static boolean schedule(int cx, int cz) {
		if (!ENABLED) return false;
		if (pool == null) return false;
		if (inflight.size() >= SCHEDULE_BACKPRESSURE) return false;
		long key = key(cx, cz);
		if (cache.containsKey(key)) return false;
		if (inflight.putIfAbsent(key, Boolean.TRUE) != null) return false;
		pool.submit(() -> warmChunk(cx, cz, key));
		return true;
	}

	/** Returns cached biome ID for the cell containing (blockX,blockY,blockZ),
	 *  or -1 on cache miss / out-of-range. Lock-free read path. */
	public static int lookup(int blockX, int blockY, int blockZ) {
		int qy = blockY >> 2;
		if (qy < MIN_QY || qy > MAX_QY) {
			cacheMisses.incrementAndGet();
			return -1;
		}
		int qx = blockX >> 2;
		int qz = blockZ >> 2;
		int cx = qx >> 2;
		int cz = qz >> 2;
		int[] arr = cache.get(key(cx, cz));
		if (arr == null) {
			cacheMisses.incrementAndGet();
			return -1;
		}
		int yIdx = qy - MIN_QY;
		int localX = qx & 3;
		int localZ = qz & 3;
		int idx = (yIdx * CELLS_PER_SLAB) + (localZ * 4) + localX;
		cacheHits.incrementAndGet();
		return arr[idx];
	}

	private static void warmChunk(int cx, int cz, long key) {
		try {
			concurrent.acquire();
			try {
				long t0 = System.nanoTime();
				int[] ids = new int[CELLS_PER_CHUNK];
				ByteBuffer buf = bufTl.get();
				buf.position(0);
				int originX = cx << 4;
				int originZ = cz << 4;
				int originY = MIN_QY << 2;
				// One JNI call fills the entire chunk's 1536 cells. Rust
				// parallelises across Y-slabs internally, so this also
				// uses Rayon — much better than 96 separate slab calls.
				int written = RustBridge.findBiomeRegion3DRust(
						originX, originY, originZ,
						4, Y_SLOTS, 4, 4, buf);
				if (written != CELLS_PER_CHUNK) return; // cache stays unfilled
				buf.position(0);
				buf.asIntBuffer().get(ids, 0, CELLS_PER_CHUNK);
				cache.put(key, ids);
				warmTotalNs.addAndGet(System.nanoTime() - t0);
				chunksWarmed.incrementAndGet();
				maybeEvict();
			} finally {
				concurrent.release();
			}
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
		} finally {
			inflight.remove(key);
		}
	}

	/** Crude eviction: when cache exceeds CACHE_LIMIT, drop ~25% of
	 *  entries via the iterator. Not LRU — random sample is fine because
	 *  the prewarm trigger will refill anything still relevant. */
	private static void maybeEvict() {
		if (cache.size() <= CACHE_LIMIT) return;
		int target = CACHE_LIMIT * 3 / 4;
		java.util.Iterator<java.util.Map.Entry<Long, int[]>> it = cache.entrySet().iterator();
		while (cache.size() > target && it.hasNext()) {
			it.next();
			it.remove();
		}
	}

	/** Pack (cx, cz) into a long key. */
	public static long key(int cx, int cz) {
		return ((long) cx << 32) | (cz & 0xFFFFFFFFL);
	}

	/** Evict cache entry for (cx,cz). Called when vanilla loads the chunk
	 *  (chunk-forcer completion or natural CHUNK_LOAD event) — vanilla
	 *  now owns the authoritative biome data and our prediction would
	 *  just hog memory for chunks the cache will never serve again. */
	public static void evict(int cx, int cz) {
		cache.remove(key(cx, cz));
	}

	public static int cachedChunks() { return cache.size(); }
	public static int inflightCount() { return inflight.size(); }
	public static long hits() { return cacheHits.get(); }
	public static long misses() { return cacheMisses.get(); }
	public static long warmed() { return chunksWarmed.get(); }
	public static long warmAvgUs() {
		long n = chunksWarmed.get();
		return n == 0 ? 0 : (warmTotalNs.get() / n / 1000);
	}

	public static void resetStats() {
		cacheHits.set(0);
		cacheMisses.set(0);
		chunksWarmed.set(0);
		warmTotalNs.set(0);
	}

	public static void clear() {
		cache.clear();
		inflight.clear();
		resetStats();
	}
}
