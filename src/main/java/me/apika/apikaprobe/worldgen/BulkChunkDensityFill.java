package me.apika.apikaprobe.worldgen;

import me.apika.apikaprobe.ExampleMod;
import me.apika.apikaprobe.RustBridge;

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
	 *  Live measurement (post-JIT-strip): noise-sync ~100-118 ms/chunk vs
	 *  vanilla baseline ~55-79 ms. JIT-optimized hot path closed ~30ms of
	 *  the original 50-90ms regression. Remaining gap is the upfront ~50ms
	 *  Rust JNI fill — the Rust DF *interpreter* at the corner-sample
	 *  stage. Closing it would need a DF *compiler* (mirroring the
	 *  surface-rule bytecode evaluator approach). */
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
