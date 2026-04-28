package me.apika.apikaprobe.worldgen;

import me.apika.apikaprobe.bridge.ExampleMod;
import me.apika.apikaprobe.RustBridge;

import java.util.concurrent.atomic.AtomicLong;

/**
 * Toggle + diagnostics for the Rust aquifer port.
 *
 * <p>When enabled, {@code AquiferRouteMixin} substitutes vanilla's
 * {@code AquiferSampler.Impl} with {@link RustAquiferSampler} at chunk
 * noise-sampler construction time. Each chunk allocates a per-chunk
 * Rust handle (via {@code RustBridge.initAquifer}) and frees it when
 * the wrapper is no longer reachable.
 *
 * <p>Default OFF until parity-validated. Toggle via
 * {@code /ferrite aquifer rust on|off|status}.
 *
 * <p>Cold-path counters only — per-block apply increments would be
 * cache-line ping-pong under multi-threaded chunkgen (see
 * {@code docs/PARALLELISM_AUDIT.md}). The interesting numbers
 * (handles created/freed, fallback frequency, init-time grid build
 * cost) all naturally live on the cold path.
 */
public final class RustAquiferDispatch {
    private RustAquiferDispatch() {}

    public static volatile boolean ENABLED = false;

    /** When true, every Rust apply() call also runs the vanilla
     *  fallback and compares results. Mismatches are logged (rate-
     *  limited) and counted. ~2× the apply cost — diagnostic only.
     *  Used to surface PRNG/index-math drift in the Rust port. */
    public static volatile boolean PARITY_MODE = false;

    /** Per-chunk wrapper construction count. Bumped once per chunk
     *  when the mixin substitutes our wrapper. */
    public static final AtomicLong wrappersCreated = new AtomicLong();

    /** Wrappers that completed their apply lifecycle and freed the
     *  Rust handle. Should equal {@link #wrappersCreated} at quiescent
     *  state — drift indicates a leak. */
    public static final AtomicLong wrappersFreed = new AtomicLong();

    /** Number of times the wrapper bailed out to the vanilla
     *  AquiferSampler.Impl because Rust returned a 0 handle (worldgen
     *  state not finalized, surface grid invalid, etc.). Should be 0
     *  after the first chunk loads cleanly. */
    public static final AtomicLong constructionFallbacks = new AtomicLong();

    /** Total time spent pre-computing the surface-height grid Java-side
     *  before each {@code initAquifer} call. Per-chunk cold path. */
    public static final AtomicLong gridBuildTotalNs = new AtomicLong();

    /** Parity-mode counters. */
    public static final AtomicLong parityCompared = new AtomicLong();
    public static final AtomicLong parityBlockMismatch = new AtomicLong();
    public static final AtomicLong parityTickMismatch = new AtomicLong();

    public static String diagSummary() {
        long created = wrappersCreated.get();
        long freed = wrappersFreed.get();
        long fb = constructionFallbacks.get();
        long gridNs = gridBuildTotalNs.get();
        double gridAvgUs = created == 0 ? 0.0 : (double) gridNs / created / 1_000.0;
        long compared = parityCompared.get();
        long blockMis = parityBlockMismatch.get();
        long tickMis = parityTickMismatch.get();
        double blockMisPct = compared == 0 ? 0.0 : 100.0 * blockMis / compared;
        return String.format(
                "[aquifer-rust] enabled=%s parity=%s wrappers=%d freed=%d (leak=%d) "
                        + "fallbacks=%d gridBuild=%.1fµs avg | parity n=%d "
                        + "blockMis=%d (%.3f%%) tickMis=%d",
                ENABLED, PARITY_MODE, created, freed, created - freed, fb, gridAvgUs,
                compared, blockMis, blockMisPct, tickMis);
    }

    public static void resetDiag() {
        wrappersCreated.set(0);
        wrappersFreed.set(0);
        constructionFallbacks.set(0);
        gridBuildTotalNs.set(0);
        parityCompared.set(0);
        parityBlockMismatch.set(0);
        parityTickMismatch.set(0);
    }
}
