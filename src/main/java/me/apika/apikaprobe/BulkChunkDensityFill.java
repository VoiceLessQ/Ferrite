package me.apika.apikaprobe;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Phase 2.5 step 4 — chunk-level density buffer substitution.
 *
 * <p>The "drive yourself" path. At each chunk's NoiseChunk construction,
 * vanilla's wrap chain produces a {@code CellCache} for the synthetic
 * {@code cacheAllInCell(add(finalDensity, beardifier))} that drives every
 * per-block {@code BlockStateFiller.calculate} call. We swap that
 * CellCache with {@link RustFinalDensityBufferWrapper} — backed by Rust's
 * DI-aware corner-sample + outer-compose pipeline (see
 * {@code populateNoiseBufferRust}). Per-block samples become array
 * lookups; the entire DF tree walk per block goes away.
 *
 * <p>Default off — opt in via {@code -Dferrite.bulkChunkDensity=true}.
 * When active, parity is enforced by Rust's compute_with_di_lerp matching
 * vanilla's selective interpolation (corner-sample DI subtrees only,
 * compose outer per-block).
 */
public final class BulkChunkDensityFill {
	private BulkChunkDensityFill() {}

	/** Default OFF — opt in via {@code -Dferrite.bulkChunkDensity=true}.
	 *  Live measurement showed ~56 ms/chunk JNI cost vs vanilla's ~2 ms
	 *  steady-state per-block (with JIT + CacheOnce). Architecture is
	 *  correct (0 fallbacks across 233M buffer hits) but perf-regressed
	 *  by ~30-90 ms/chunk because eager bulk fill loses to vanilla's
	 *  lazy per-block evaluation with caching. */
	public static volatile boolean ENABLED = Boolean.parseBoolean(
			System.getProperty("ferrite.bulkChunkDensity", "false"));

	public static final AtomicLong substitutions = new AtomicLong();
	public static final AtomicLong fingerprintMisses = new AtomicLong();
	public static final AtomicLong nonCellCacheSeen = new AtomicLong();

	// Step-by-step debug counters to localize where the mixin path falls
	// out. Increments at each gate so we can read off the dropoff.
	public static final AtomicLong mixinFires = new AtomicLong();
	public static final AtomicLong wrappingSeen = new AtomicLong();
	public static final AtomicLong typeAccessOk = new AtomicLong();
	public static final AtomicLong cellCacheTypeSeen = new AtomicLong();
	public static final AtomicLong fingerprintAttempted = new AtomicLong();

	private static final ConcurrentHashMap<String, Boolean> seenTypeNames = new ConcurrentHashMap<>();

	/** One-shot log per distinct typeName so we can see what yarn's
	 *  Wrapping.Type.toString actually returns at runtime. */
	public static void recordTypeNameOnce(String typeName) {
		if (typeName == null) return;
		if (seenTypeNames.putIfAbsent(typeName, Boolean.TRUE) == null) {
			ExampleMod.LOGGER.info("[bulk-chunk-density] saw Wrapping.type.toString() = {}", typeName);
		}
	}

	public static String diagSummary() {
		return String.format(
				"[bulk-chunk-density] enabled=%s fires=%d wrap=%d typeOk=%d cellCache=%d fpAttempt=%d subst=%d fp-miss=%d non-cellCache=%d",
				ENABLED,
				mixinFires.get(),
				wrappingSeen.get(),
				typeAccessOk.get(),
				cellCacheTypeSeen.get(),
				fingerprintAttempted.get(),
				substitutions.get(),
				fingerprintMisses.get(),
				nonCellCacheSeen.get());
	}

	public static void resetDiag() {
		substitutions.set(0);
		fingerprintMisses.set(0);
		nonCellCacheSeen.set(0);
	}
}
