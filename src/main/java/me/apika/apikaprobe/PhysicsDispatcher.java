package me.apika.apikaprobe;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

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

		if (!ENABLED || !RustBridge.NATIVE_AVAILABLE) {
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
		if (!ENABLED || !RustBridge.NATIVE_AVAILABLE || currentWorld == null) {
			return ((EntityAdjustInvoker) self).ferrite$invokeAdjust(motion);
		}

		long key = chunkKey(self);
		List<Entity> bucket = BUCKETS.get(key);
		if (bucket == null) {
			// Mob not in any pre-tick bucket (spawned mid-tick, or cross-world
			// corner case) — fall back. No counter bump; it's not a dispatch.
			return ((EntityAdjustInvoker) self).ferrite$invokeAdjust(motion);
		}

		// Lazy snapshot rebuild on bucket miss.
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
				return ((EntityAdjustInvoker) self).ferrite$invokeAdjust(motion);
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
			diagFallback++;
			return ((EntityAdjustInvoker) self).ferrite$invokeAdjust(motion);
		}
		diagDispatched++;
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
