package me.apika.apikaprobe;

import java.util.concurrent.atomic.AtomicLong;

import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;

import net.minecraft.entity.Entity;
import net.minecraft.util.math.Vec3d;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Correctness oracle for the Rust physics dispatcher.
 *
 * <p>When {@link PhysicsDispatcher#PARITY_MODE} is true,
 * {@link PhysicsDispatcher#adjust(Entity, Vec3d)} ALWAYS returns vanilla's
 * result (so the live world is unaffected) but ALSO shadow-runs the Rust
 * path and feeds the (vanilla, rust) pair through {@link #record}. This
 * gives a real-load parity dataset without risking visible mob clipping
 * during validation.
 *
 * <p>Phase 1 (validate): {@code PARITY_MODE=true, ENABLED=false}. Vanilla
 * applied, Rust shadow-runs, mismatches surface here. Pass criterion is
 * {@code matches / (matches + mismatches) == 1.000} across ≥10K dispatches.
 *
 * <p>Phase 2 (perf measure): {@code PARITY_MODE=false, ENABLED=true}. Rust
 * applied, no shadow, vanilla baseline measured separately for A/B.
 *
 * <p>Mirrors the aquifer / redstone parity pattern: AtomicLong counters,
 * 5s window report via {@code END_SERVER_TICK}, rate-limited mismatch logs.
 */
public final class PhysicsOracle {
	private static final Logger LOGGER = LoggerFactory.getLogger("ferrite");
	private static final long REPORT_INTERVAL_NS = 5_000_000_000L;
	private static final long MISMATCH_LOG_MIN_GAP_NS = 100_000_000L;

	/** Component-wise tolerance for vanilla vs Rust displacement diff.
	 *  Vanilla's iterative collision sweep accumulates ulp-level rounding;
	 *  1e-9 is well below visual significance (sub-nanometer) but catches
	 *  any logic divergence (off-by-one cell, wrong axis, etc.). */
	public static final double EPSILON = 1.0e-9;

	private static final AtomicLong COMPARED = new AtomicLong();
	private static final AtomicLong MATCHES = new AtomicLong();
	private static final AtomicLong MISMATCHES = new AtomicLong();
	private static final AtomicLong RUST_FALLBACKS = new AtomicLong();
	private static final AtomicLong BUCKET_MISSES = new AtomicLong();
	private static final AtomicLong BUILD_FAILS = new AtomicLong();

	private static volatile long lastReportNs = System.nanoTime();
	private static volatile long lastMismatchLogNs;

	private PhysicsOracle() {}

	public static void register() {
		ServerTickEvents.END_SERVER_TICK.register(server -> maybeReport());
	}

	/** Rust returned a result; compare against vanilla. */
	public static void record(Entity self, Vec3d motion, Vec3d vanilla, Vec3d rust) {
		COMPARED.incrementAndGet();
		double dx = rust.x - vanilla.x;
		double dy = rust.y - vanilla.y;
		double dz = rust.z - vanilla.z;
		if (Math.abs(dx) <= EPSILON && Math.abs(dy) <= EPSILON && Math.abs(dz) <= EPSILON) {
			MATCHES.incrementAndGet();
			return;
		}
		long n = MISMATCHES.incrementAndGet();
		long now = System.nanoTime();
		if (now - lastMismatchLogNs >= MISMATCH_LOG_MIN_GAP_NS) {
			lastMismatchLogNs = now;
			LOGGER.warn(
					"[physics-parity] MISMATCH #{} type={} pos=({},{},{}) motion=({},{},{}) vanilla=({},{},{}) rust=({},{},{}) delta=({},{},{})",
					n,
					self.getType().toString(),
					f(self.getX()), f(self.getY()), f(self.getZ()),
					f(motion.x), f(motion.y), f(motion.z),
					f(vanilla.x), f(vanilla.y), f(vanilla.z),
					f(rust.x), f(rust.y), f(rust.z),
					e(dx), e(dy), e(dz));
		}
	}

	/** Rust path returned null (snapshot rejected, palette overflow, etc.). */
	public static void recordRustFallback() {
		RUST_FALLBACKS.incrementAndGet();
	}

	/** Mob wasn't in any pre-tick bucket (spawned mid-tick, world swap). */
	public static void recordBucketMiss() {
		BUCKET_MISSES.incrementAndGet();
	}

	/** Snapshot build failed (oversize region, palette overflow). */
	public static void recordBuildFail() {
		BUILD_FAILS.incrementAndGet();
	}

	private static void maybeReport() {
		long now = System.nanoTime();
		if (now - lastReportNs < REPORT_INTERVAL_NS) return;
		lastReportNs = now;

		long compared = COMPARED.getAndSet(0L);
		long matches = MATCHES.getAndSet(0L);
		long mismatches = MISMATCHES.getAndSet(0L);
		long rustFb = RUST_FALLBACKS.getAndSet(0L);
		long bucketMiss = BUCKET_MISSES.getAndSet(0L);
		long buildFail = BUILD_FAILS.getAndSet(0L);

		if (compared == 0L && rustFb == 0L && bucketMiss == 0L && buildFail == 0L) return;

		double matchPct = compared == 0L ? 0.0 : 100.0 * matches / compared;
		LOGGER.info(
				"[physics-parity] dispatched={} matched={} mismatches={} match={}%  ineligible: rustFb={} bucketMiss={} buildFail={}",
				compared, matches, mismatches,
				String.format("%.4f", matchPct),
				rustFb, bucketMiss, buildFail);
	}

	private static String f(double v) {
		return String.format("%.6f", v);
	}

	private static String e(double v) {
		return String.format("%.3e", v);
	}
}
