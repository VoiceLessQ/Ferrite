package me.apika.apikaprobe.entity;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import me.apika.apikaprobe.RustBridge;
import me.apika.apikaprobe.mixin.EntityAdjustInvoker;

import net.minecraft.entity.Entity;
import net.minecraft.entity.mob.MobEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.Vec3d;

/**
 * Option A — chunk-column bucketing with lazy snapshot rebuild.
 *
 * Per tick:
 *   1. PhysicsPreTickMixin fires at ServerWorld.tick HEAD →
 *      onPreEntityTick(world). Walk entities once, partition MobEntity
 *      into HashMap<chunkKey, List<Entity>>. No snapshots built yet.
 *   2. Each mob's move() triggers MovementRedirectMixin → adjust().
 *      Compute the mob's chunk key; if SNAPSHOT_BUF currently holds a
 *      different bucket, rebuild snapshot for this mob's bucket and
 *      bump snapshotTickId. Then dispatch one-entity computeEntityPhysics.
 *   3. Rebuild counter exposes miss rate — if rebuilds/tick exceeds
 *      bucket count significantly, vanilla's tick order isn't
 *      bucket-contiguous and we'd need a pre-build pass.
 *
 * ENABLED stays false until a benchmark confirms correctness under
 * this model.
 */
public final class PhysicsDispatcher {

	public static volatile boolean ENABLED = false;

	/** When true, every {@link #adjust} call ALWAYS returns vanilla's
	 *  result (so the live world is unaffected) but ALSO shadow-runs
	 *  the Rust path and feeds the (vanilla, rust) pair to
	 *  {@link PhysicsOracle#record}. Independent of {@link #ENABLED}.
	 *
	 *  <p>Used to validate the Rust port against vanilla under live
	 *  load before flipping {@link #ENABLED}. Phase plan:
	 *  <ul>
	 *    <li>Phase 1: {@code PARITY_MODE=true, ENABLED=false} — collect
	 *        parity dataset.</li>
	 *    <li>Phase 2: {@code PARITY_MODE=false, ENABLED=true} — measure
	 *        adjustColl band perf delta vs vanilla baseline.</li>
	 *  </ul>
	 *
	 *  <p>Default {@code false}: parity has been validated (2026-04-28,
	 *  100.0000% match across 700K+ dispatches at 1000-mob scale, 0
	 *  mismatches). Phase 2 measurement showed the Rust path regresses
	 *  perf vs JIT-inlined vanilla under load, so {@link #ENABLED} also
	 *  ships off — see docs/PIANO_STATUS.md. Flip both to {@code true}
	 *  + {@code false} respectively only for re-validation runs. */
	public static volatile boolean PARITY_MODE = false;

	// --- Per-tick state (server tick is single-threaded) --------------------
	private static final Map<Long, List<Entity>> BUCKETS = new HashMap<>(64);
	private static final Deque<List<Entity>> LIST_POOL = new ArrayDeque<>();
	private static final long BUCKET_KEY_NONE = Long.MIN_VALUE;

	private static long currentlyLoadedBucketKey = BUCKET_KEY_NONE;
	private static int  currentlyLoadedTickId   = 0;
	private static ServerWorld currentWorld     = null;

	// --- Diagnostics --------------------------------------------------------
	private static final Logger LOGGER = LoggerFactory.getLogger("ferrite");
	private static long diagBuildsOk        = 0;
	private static long diagBuildsFailed    = 0;
	private static long diagDispatched      = 0;
	private static long diagFallback        = 0;
	private static long diagRebuilds        = 0;
	private static long diagBucketMisses    = 0;
	private static long diagBucketsThisTick = 0;
	private static int  diagMobsThisTick    = 0;
	private static long diagLastLogNs       = System.nanoTime();

	private PhysicsDispatcher() {}

	// =========================================================================
	// Called from PhysicsPreTickMixin
	// =========================================================================

	public static void onPreEntityTick(ServerWorld world) {
		// Return any lists we kept around to the pool, then clear the map.
		releaseBuckets();
		currentlyLoadedBucketKey = BUCKET_KEY_NONE;
		currentWorld = world;

		// Bucket build is needed when ENABLED OR PARITY_MODE is on — the
		// shadow path in adjust() reads BUCKETS regardless of ENABLED.
		if (!RustBridge.NATIVE_AVAILABLE || (!ENABLED && !PARITY_MODE)) {
			maybeLogDiag();
			return;
		}

		// Partition all MobEntity in this world into 16x16 chunk-column buckets.
		int mobCount = 0;
		for (Entity e : world.iterateEntities()) {
			if (!(e instanceof MobEntity)) continue;
			long key = chunkKey(e);
			List<Entity> bucket = BUCKETS.get(key);
			if (bucket == null) {
				bucket = borrowList();
				BUCKETS.put(key, bucket);
			}
			bucket.add(e);
			mobCount++;
		}
		diagBucketsThisTick = BUCKETS.size();
		diagMobsThisTick = mobCount;

		maybeLogDiag();
	}

	// =========================================================================
	// Called from MovementRedirectMixin (once per mob per move() call)
	// =========================================================================

	public static Vec3d adjust(Entity self, Vec3d motion) {
		if (PARITY_MODE) {
			// Always run vanilla — that's what we return.
			Vec3d vanillaResult = ((EntityAdjustInvoker) self).ferrite$invokeAdjust(motion);
			// Shadow-run Rust if eligible. Unaffected by ENABLED — parity
			// validation happens with ENABLED=false so the live world stays
			// on vanilla while we collect the dataset.
			if (RustBridge.NATIVE_AVAILABLE && currentWorld != null) {
				Vec3d rustResult = runRust(self, motion);
				if (rustResult != null) {
					PhysicsOracle.record(self, motion, vanillaResult, rustResult);
				}
				// Ineligible / fallback paths bump their own counters inside runRust.
			}
			return vanillaResult;
		}

		if (!ENABLED || !RustBridge.NATIVE_AVAILABLE || currentWorld == null) {
			return ((EntityAdjustInvoker) self).ferrite$invokeAdjust(motion);
		}

		Vec3d result = runRust(self, motion);
		if (result == null) {
			diagFallback++;
			return ((EntityAdjustInvoker) self).ferrite$invokeAdjust(motion);
		}
		diagDispatched++;
		return result;
	}

	/**
	 * Runs the Rust adjust path for one mob. Returns the adjusted motion,
	 * or {@code null} on any ineligibility (bucket miss, snapshot build
	 * fail, Rust returned FALLBACK flag). Counters get bumped on both the
	 * existing diag pipe ({@link #diagBucketMisses} etc.) AND the
	 * {@link PhysicsOracle} side counters so PARITY_MODE captures the
	 * eligibility breakdown without mutating the existing diag log shape.
	 */
	private static Vec3d runRust(Entity self, Vec3d motion) {
		long key = chunkKey(self);
		List<Entity> bucket = BUCKETS.get(key);
		if (bucket == null) {
			PhysicsOracle.recordBucketMiss();
			return null;
		}

		if (key != currentlyLoadedBucketKey) {
			diagBucketMisses++;
			int newTickId = currentlyLoadedTickId + 1;
			boolean ok = PhysicsHandoff.buildSnapshot(currentWorld, bucket, newTickId);
			if (ok) {
				currentlyLoadedBucketKey = key;
				currentlyLoadedTickId = newTickId;
				diagBuildsOk++;
				diagRebuilds++;
			} else {
				currentlyLoadedBucketKey = BUCKET_KEY_NONE;
				diagBuildsFailed++;
				PhysicsOracle.recordBuildFail();
				return null;
			}
		}

		float stepUp = self.getStepHeight();
		byte flags = 0;
		if (self.isOnGround()) flags |= PhysicsHandoff.REQ_FLAG_ON_GROUND;

		PhysicsHandoff.fillSingleRequest(self, motion, stepUp, flags, currentlyLoadedTickId);
		RustBridge.computeEntityPhysics(
			PhysicsHandoff.SNAPSHOT_BUF,
			PhysicsHandoff.REQUEST_BUF,
			PhysicsHandoff.RESULT_BUF,
			1
		);

		Vec3d result = PhysicsHandoff.readSingleResult();
		if (result == null) {
			PhysicsOracle.recordRustFallback();
		}
		return result;
	}

	// =========================================================================
	// Internals
	// =========================================================================

	private static long chunkKey(Entity e) {
		int cx = ((int) Math.floor(e.getX())) >> 4;
		int cz = ((int) Math.floor(e.getZ())) >> 4;
		return (((long) cx) << 32) | (((long) cz) & 0xFFFFFFFFL);
	}

	private static List<Entity> borrowList() {
		List<Entity> l = LIST_POOL.pollFirst();
		return l != null ? l : new ArrayList<>(32);
	}

	private static void releaseBuckets() {
		if (BUCKETS.isEmpty()) return;
		for (List<Entity> bucket : BUCKETS.values()) {
			bucket.clear();
			LIST_POOL.addFirst(bucket);
		}
		BUCKETS.clear();
	}

	private static void maybeLogDiag() {
		long now = System.nanoTime();
		if (now - diagLastLogNs < 5_000_000_000L) return;
		diagLastLogNs = now;
		LOGGER.info(
			"[physics-dispatch] buckets={} mobs={}  builds: {} ok / {} failed  "
			+ "rebuilds={} bucketMisses={}  dispatched={} fallback={}  "
			+ "lastReject={}x{}x{}",
			diagBucketsThisTick, diagMobsThisTick,
			diagBuildsOk, diagBuildsFailed,
			diagRebuilds, diagBucketMisses,
			diagDispatched, diagFallback,
			PhysicsHandoff.LAST_REJECTED_SX, PhysicsHandoff.LAST_REJECTED_SY, PhysicsHandoff.LAST_REJECTED_SZ
		);
		diagBuildsOk = 0;
		diagBuildsFailed = 0;
		diagDispatched = 0;
		diagFallback = 0;
		diagRebuilds = 0;
		diagBucketMisses = 0;
	}
}
