package me.apika.apikaprobe;

import java.util.ArrayList;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import me.apika.apikaprobe.mixin.EntityAdjustInvoker;

import net.minecraft.entity.Entity;
import net.minecraft.entity.mob.MobEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.Vec3d;

/**
 * Per-entity JNI with shared snapshot.
 *
 * Pipeline per tick:
 *   1. PhysicsPreTickMixin fires just before entity iteration →
 *      onPreEntityTick(world). Collect all MobEntity, build snapshot
 *      into PhysicsHandoff.SNAPSHOT_BUF, bump snapshotTickId.
 *   2. Each mob's Entity.move() triggers MovementRedirectMixin →
 *      adjust(self, motion). Write a one-entity request, call the
 *      native computeEntityPhysics, read the one result.
 *   3. Fallback flag (complex cell / out-of-bounds / native off) →
 *      vanilla via the @Invoker.
 *
 * ENABLED defaults false. Flipping it to true without the Rust JNI
 * entry point present will trigger UnsatisfiedLinkError on first
 * mob move — the Rust side is the next commit.
 */
public final class PhysicsDispatcher {

	public static volatile boolean ENABLED = false;

	// Per-tick state. Server tick is single-threaded, no synchronisation needed.
	private static int currentSnapshotTickId = 0;
	private static boolean snapshotValid = false;

	// Reused across ticks to avoid per-tick allocation.
	private static final List<Entity> MOB_SCRATCH = new ArrayList<>(1024);

	// --- Diagnostics --------------------------------------------------------
	private static final Logger LOGGER = LoggerFactory.getLogger("ferrite");
	private static long diagBuildsAttempted = 0;
	private static long diagBuildsOk = 0;
	private static long diagDispatched = 0;
	private static long diagFallback = 0;
	private static long diagLastLogNs = System.nanoTime();

	private PhysicsDispatcher() {}

	// =========================================================================
	// Called from PhysicsPreTickMixin
	// =========================================================================

	public static void onPreEntityTick(ServerWorld world) {
		snapshotValid = false;
		if (!ENABLED || !RustBridge.NATIVE_AVAILABLE) return;

		MOB_SCRATCH.clear();
		for (Entity e : world.iterateEntities()) {
			if (e instanceof MobEntity) {
				MOB_SCRATCH.add(e);
			}
		}
		if (MOB_SCRATCH.isEmpty()) {
			// No mobs this tick — dispatcher still idle, but bump tickId so any
			// stale snapshotTickId on a straggling request fails cleanly.
			currentSnapshotTickId++;
			return;
		}

		int newTickId = currentSnapshotTickId + 1;
		diagBuildsAttempted++;
		boolean ok = PhysicsHandoff.buildSnapshot(world, MOB_SCRATCH, newTickId);
		if (ok) {
			currentSnapshotTickId = newTickId;
			snapshotValid = true;
			diagBuildsOk++;
		}
		maybeLogDiag(MOB_SCRATCH.size());
	}

	private static void maybeLogDiag(int mobCount) {
		long now = System.nanoTime();
		if (now - diagLastLogNs < 5_000_000_000L) return;
		diagLastLogNs = now;
		LOGGER.info("[physics-dispatch] builds: {}/{} ok  dispatched={} fallback={} mobsThisTick={} lastReject={}x{}x{}",
				diagBuildsOk, diagBuildsAttempted, diagDispatched, diagFallback, mobCount,
				PhysicsHandoff.LAST_REJECTED_SX, PhysicsHandoff.LAST_REJECTED_SY, PhysicsHandoff.LAST_REJECTED_SZ);
		diagBuildsAttempted = 0;
		diagBuildsOk = 0;
		diagDispatched = 0;
		diagFallback = 0;
	}

	// =========================================================================
	// Called from MovementRedirectMixin (once per mob per move() call)
	// =========================================================================

	public static Vec3d adjust(Entity self, Vec3d motion) {
		if (!ENABLED || !RustBridge.NATIVE_AVAILABLE || !snapshotValid) {
			return ((EntityAdjustInvoker) self).ferrite$invokeAdjust(motion);
		}

		float stepUp = self.getStepHeight();
		byte flags = 0;
		if (self.isOnGround()) flags |= PhysicsHandoff.REQ_FLAG_ON_GROUND;

		PhysicsHandoff.fillSingleRequest(self, motion, stepUp, flags, currentSnapshotTickId);

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
}
