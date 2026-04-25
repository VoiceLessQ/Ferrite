package me.apika.apikaprobe;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Pure-observational counters for the Phase 2.5 step 2a checkpoint.
 *
 * <p>When vanilla's {@code ChunkNoiseSampler#getActualDensityFunctionImpl}
 * (yarn name for {@code NoiseChunk.wrapNew}) returns a cache wrapper
 * (FlatCache / NoiseInterpolator / CacheAllInCell), the
 * {@link me.apika.apikaprobe.mixin.CacheRouteCaptureMixin} looks up the
 * input Marker in {@link WorldgenStateBootstrap#identifiedRouterDfs()}
 * and increments the appropriate matched/unmatched counter here.
 *
 * <p>This confirms — without changing chunkgen behavior — whether the
 * deep-walker identity map (step 1) actually finds the same Marker
 * instances vanilla's {@code mapAll(this::wrap)} hands to the wrapper
 * constructors. If matched > 0 across all three cache types, step 2b
 * (real fill mixin) is unblocked. If matched stays at 0 for some type,
 * we know there's a Marker re-instantiation gap to investigate.
 *
 * <p>First-seen log line per (cacheType, rustName) pair gives a quick
 * sanity check that mappings look reasonable (e.g. an Interpolated
 * marker maps to {@code ferrite:terrain/final_density}, not
 * {@code ferrite:climate/depth}).
 */
public final class CacheRouteStats {
	private CacheRouteStats() {}

	public static final AtomicLong flatCacheMatched = new AtomicLong();
	public static final AtomicLong flatCacheUnmatched = new AtomicLong();
	public static final AtomicLong interpolatorMatched = new AtomicLong();
	public static final AtomicLong interpolatorUnmatched = new AtomicLong();
	public static final AtomicLong cellCacheMatched = new AtomicLong();
	public static final AtomicLong cellCacheUnmatched = new AtomicLong();
	public static final AtomicLong otherSeen = new AtomicLong();

	private static final ConcurrentHashMap<String, Boolean> firstSeenPair =
			new ConcurrentHashMap<>();

	private static final AtomicLong totalCaptures = new AtomicLong();
	private static final long SUMMARY_EVERY = 5000;

	public static void record(String cacheTypeSimpleName, String rustName) {
		boolean matched = rustName != null;
		AtomicLong counter;
		if (cacheTypeSimpleName.contains("FlatCache")) {
			counter = matched ? flatCacheMatched : flatCacheUnmatched;
		} else if (cacheTypeSimpleName.contains("Interpolator")
				|| cacheTypeSimpleName.contains("DensityInterpolator")) {
			counter = matched ? interpolatorMatched : interpolatorUnmatched;
		} else if (cacheTypeSimpleName.contains("CacheAllInCell")
				|| cacheTypeSimpleName.contains("CellCache")) {
			counter = matched ? cellCacheMatched : cellCacheUnmatched;
		} else {
			otherSeen.incrementAndGet();
			return;
		}
		counter.incrementAndGet();
		if (matched) {
			String key = cacheTypeSimpleName + ":" + rustName;
			if (firstSeenPair.putIfAbsent(key, Boolean.TRUE) == null) {
				ExampleMod.LOGGER.info("[cache-route] {} <-> {}", cacheTypeSimpleName, rustName);
			}
		}
		long n = totalCaptures.incrementAndGet();
		if (n % SUMMARY_EVERY == 0) {
			ExampleMod.LOGGER.info(summary());
		}
	}

	public static String summary() {
		return String.format(
				"[cache-route] flatCache matched=%d unmatched=%d | interp matched=%d unmatched=%d | cellCache matched=%d unmatched=%d | other=%d",
				flatCacheMatched.get(), flatCacheUnmatched.get(),
				interpolatorMatched.get(), interpolatorUnmatched.get(),
				cellCacheMatched.get(), cellCacheUnmatched.get(),
				otherSeen.get());
	}

	public static void reset() {
		flatCacheMatched.set(0);
		flatCacheUnmatched.set(0);
		interpolatorMatched.set(0);
		interpolatorUnmatched.set(0);
		cellCacheMatched.set(0);
		cellCacheUnmatched.set(0);
		otherSeen.set(0);
		firstSeenPair.clear();
	}
}
