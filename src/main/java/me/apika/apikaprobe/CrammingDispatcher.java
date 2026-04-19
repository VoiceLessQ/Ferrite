package me.apika.apikaprobe;

import java.util.ArrayList;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import net.minecraft.entity.Entity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.mob.MobEntity;
import net.minecraft.server.world.ServerWorld;

/**
 * Batched mob-vs-mob cramming dispatcher.
 *
 * First LivingEntity.tickCramming() call within a given server tick
 * triggers the batch:
 *   1. Collect every MobEntity in the world.
 *   2. Fill CrammingHandoff.REQUEST_BUF with positions + AABBs + flags.
 *   3. Native call → Rust runs the spatial hash push accumulator.
 *   4. Read accumulated (dx, dz) + neighborCount per mob.
 *   5. Apply each mob's velocity delta via entity.addVelocity(dx,0,dz).
 *   6. Apply cramming damage where neighborCount > maxEntityCramming − 1
 *      (vanilla's 1-in-4 random still honoured).
 *
 * Subsequent tickCramming calls in the same server tick are cancelled
 * by the Mixin without re-triggering — the tick guard is `world.getTime()`.
 *
 * ENABLED=false by default. Mixin falls back to vanilla in that case.
 */
public final class CrammingDispatcher {

	public static volatile boolean ENABLED = false;

	// --- Per-server-tick state ---------------------------------------------
	private static long lastProcessedTick = Long.MIN_VALUE;
	private static final List<LivingEntity> MOB_SCRATCH = new ArrayList<>(1024);

	// Parallel output arrays sized to MAX_ENTITIES; reused every tick.
	private static final double[] ACCUM_DX = new double[CrammingHandoff.MAX_ENTITIES];
	private static final double[] ACCUM_DZ = new double[CrammingHandoff.MAX_ENTITIES];
	private static final int[] NEIGHBOR_COUNT = new int[CrammingHandoff.MAX_ENTITIES];

	// --- Diagnostics -------------------------------------------------------
	private static final Logger LOGGER = LoggerFactory.getLogger("ferrite");
	private static long diagBatches  = 0;
	private static long diagMobs     = 0;
	private static long diagPushed   = 0;
	private static long diagLastLogNs = System.nanoTime();

	private CrammingDispatcher() {}

	/**
	 * Called from CrammingCancelMixin on the first tickCramming call of a
	 * server tick. Returns true if Rust handled the batch (caller should
	 * cancel the vanilla body); false if the caller should let vanilla run.
	 */
	public static boolean onTickCramming(LivingEntity caller) {
		if (!ENABLED || !RustBridge.NATIVE_AVAILABLE) return false;
		if (!(caller.getEntityWorld() instanceof ServerWorld world)) return false;

		long tick = world.getTime();
		if (tick == lastProcessedTick) {
			// Batch already processed this tick — still cancel vanilla body.
			return true;
		}
		lastProcessedTick = tick;
		runBatch(world);
		maybeLogDiag();
		return true;
	}

	// =========================================================================
	// Batch
	// =========================================================================

	private static void runBatch(ServerWorld world) {
		// 1. Collect all eligible mobs in this world.
		MOB_SCRATCH.clear();
		for (Entity e : world.iterateEntities()) {
			if (e instanceof MobEntity && e.isAlive() && !e.isRemoved()) {
				MOB_SCRATCH.add((LivingEntity) e);
			}
		}
		int count = MOB_SCRATCH.size();
		if (count == 0) return;
		if (count > CrammingHandoff.MAX_ENTITIES) {
			// Too many mobs — fall back. The Mixin already cancelled, so
			// vanilla won't run. Rare; safer than partial batching.
			return;
		}

		// 2–4. Build, dispatch, read.
		CrammingHandoff.buildRequests(MOB_SCRATCH);
		RustBridge.computeCramming(
			CrammingHandoff.REQUEST_BUF,
			CrammingHandoff.RESULT_BUF,
			count
		);
		CrammingHandoff.readResults(count, ACCUM_DX, ACCUM_DZ, NEIGHBOR_COUNT);

		// 5. Apply pushes. (Cramming damage deferred to v2 — 1.21.11's
		//    GameRules API moved to getValue(rule)→Object and we're not
		//    wiring up reflection just for the damage-threshold lookup.
		//    The 14ms target from instrumentation is entirely push cost,
		//    not damage cost, so v1 ships push-only.)
		int pushedThisBatch = 0;
		for (int i = 0; i < count; i++) {
			LivingEntity e = MOB_SCRATCH.get(i);
			double dx = ACCUM_DX[i];
			double dz = ACCUM_DZ[i];
			if (dx != 0.0 || dz != 0.0) {
				e.addVelocity(dx, 0.0, dz);
				pushedThisBatch++;
			}
		}

		diagBatches++;
		diagMobs += count;
		diagPushed += pushedThisBatch;
	}

	private static void maybeLogDiag() {
		long now = System.nanoTime();
		if (now - diagLastLogNs < 5_000_000_000L) return;
		diagLastLogNs = now;
		LOGGER.info(
			"[cramming-dispatch] batches={} mobsTotal={}  pushed={}",
			diagBatches, diagMobs, diagPushed
		);
		diagBatches = 0;
		diagMobs = 0;
		diagPushed = 0;
	}
}
