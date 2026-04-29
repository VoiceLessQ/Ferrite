package me.apika.apikaprobe.entity;

import java.nio.ByteBuffer;
import java.nio.LongBuffer;
import java.util.concurrent.atomic.AtomicLong;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Parity validator for the cramming fingerprint cache.
 *
 * Validates the core invariant: if the input fingerprint matches the previous
 * tick's fingerprint, the outputs (push deltas + crowded counts) must be
 * identical. A cache hit on a matching fingerprint would return the stored
 * results; this validator confirms they would have been correct.
 *
 * Always runs the full compute path (no caching yet). On fingerprint match,
 * diffs current results against the previous tick's stored results. Any
 * divergence is counted and logged. Pass criterion before the cache ships:
 * 100% result-match rate on fingerprint-hit ticks across a stable pile,
 * a moving pile, and a mixed scene.
 *
 * Toggle: /ferrite cramming parity on|off|stats|reset
 * Default OFF.
 */
public final class CrammingParityValidator {

    private CrammingParityValidator() {}

    public static volatile boolean ENABLED = false;

    private static final Logger LOGGER = LoggerFactory.getLogger("ferrite");
    private static final long REPORT_INTERVAL_NS  = 5_000_000_000L;
    private static final long MAX_MISMATCH_LOG    = 20;

    // Per-tick stored state. Only accessed on the server tick thread — no sync needed.
    private static long   prevFingerprint = Long.MIN_VALUE; // sentinel: unset
    private static int    prevCount       = 0;
    private static final double[] PREV_DX      = new double[CrammingHandoff.MAX_ENTITIES];
    private static final double[] PREV_DZ      = new double[CrammingHandoff.MAX_ENTITIES];
    private static final int[]    PREV_CROWDED = new int[CrammingHandoff.MAX_ENTITIES];

    // Counters written on tick thread, read from command thread.
    private static final AtomicLong ticksValidated     = new AtomicLong();
    private static final AtomicLong fingerprintHits    = new AtomicLong();
    private static final AtomicLong resultMatches      = new AtomicLong();
    private static final AtomicLong resultMismatches   = new AtomicLong();
    private static final AtomicLong mismatchedEntities = new AtomicLong();
    private static final AtomicLong loggedLines        = new AtomicLong();
    private static volatile long lastReportNs = System.nanoTime();

    /**
     * Computes a 64-bit fingerprint over the first {@code count} entries in
     * REQUEST_BUF. Called after buildRequests() has filled and flipped the
     * buffer — position=0, limit=count*40. The buffer is not consumed; a
     * duplicate view is used.
     *
     * 40 bytes per entity = 5 longs exactly. Each long is folded into a
     * running xor-mul chain. Seed includes {@code count} so batches of
     * different sizes never collide even on identical per-entity content.
     */
    public static long fingerprint(ByteBuffer requestBuf, int count) {
        // duplicate() inherits position=0, limit=count*40 from the flipped buf.
        LongBuffer lb = requestBuf.duplicate().asLongBuffer();
        long fp = count;
        while (lb.hasRemaining()) {
            fp = fp * 0x517cc1b727220a95L + lb.get();
            fp ^= Long.rotateLeft(fp, 31);
        }
        return fp;
    }

    /**
     * Called from CrammingDispatcher.runBatch after the full compute and
     * readResults, before any results are applied. On fingerprint match with
     * the previous tick's fingerprint, diffs current results against stored
     * results and increments counters. Always updates stored state so the
     * next tick has a baseline to compare against.
     */
    public static void validate(long fingerprint, int count,
            double[] dx, double[] dz, int[] crowded) {
        if (!ENABLED) return;
        ticksValidated.incrementAndGet();

        if (prevFingerprint == fingerprint && prevCount == count) {
            fingerprintHits.incrementAndGet();
            long entityMm = 0;
            for (int i = 0; i < count; i++) {
                if (dx[i] != PREV_DX[i] || dz[i] != PREV_DZ[i] || crowded[i] != PREV_CROWDED[i]) {
                    entityMm++;
                    long line = loggedLines.getAndIncrement();
                    if (line < MAX_MISMATCH_LOG) {
                        LOGGER.warn(
                            "[cramming-parity] entity[{}] result diverged on fp-hit: "
                            + "dx {}->{} dz {}->{} crowded {}->{}",
                            i,
                            PREV_DX[i], dx[i],
                            PREV_DZ[i], dz[i],
                            PREV_CROWDED[i], crowded[i]);
                    } else if (line == MAX_MISMATCH_LOG) {
                        LOGGER.warn("[cramming-parity] suppressing further per-entity mismatch lines (>{})", MAX_MISMATCH_LOG);
                    }
                }
            }
            if (entityMm == 0) {
                resultMatches.incrementAndGet();
            } else {
                resultMismatches.incrementAndGet();
                mismatchedEntities.addAndGet(entityMm);
            }
        }

        // Store current results as baseline for the next tick.
        prevFingerprint = fingerprint;
        prevCount       = count;
        System.arraycopy(dx,      0, PREV_DX,      0, count);
        System.arraycopy(dz,      0, PREV_DZ,      0, count);
        System.arraycopy(crowded, 0, PREV_CROWDED,  0, count);

        maybeReport();
    }

    private static void maybeReport() {
        long now = System.nanoTime();
        if (now - lastReportNs < REPORT_INTERVAL_NS) return;
        lastReportNs = now;
        LOGGER.info(statsLine());
    }

    public static String statsLine() {
        long ticks = ticksValidated.get();
        long hits  = fingerprintHits.get();
        long match = resultMatches.get();
        long mm    = resultMismatches.get();
        double hitRate   = ticks == 0 ? 0.0 : 100.0 * hits  / ticks;
        double matchRate = hits  == 0 ? 0.0 : 100.0 * match / hits;
        return String.format(
            "[cramming-parity] ticks=%d fpHits=%d (%.1f%%) resultMatch=%d mismatch=%d (%.1f%% of hits) entityMismatches=%d",
            ticks, hits, hitRate, match, mm, matchRate, mismatchedEntities.get());
    }

    public static void resetCounters() {
        ticksValidated.set(0);
        fingerprintHits.set(0);
        resultMatches.set(0);
        resultMismatches.set(0);
        mismatchedEntities.set(0);
        loggedLines.set(0);
        prevFingerprint = Long.MIN_VALUE;
        prevCount       = 0;
    }
}
