package me.apika.apikaprobe.entity;

import java.util.ArrayList;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import me.apika.apikaprobe.RustBridge;

import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.server.level.ServerLevel;

/**
 * Batched mob-vs-mob cramming dispatcher.
 *
 * First LivingEntity.tickCramming() call within a given server tick
 * triggers the batch:
 *   1. Collect every Mob in the world.
 *   2. Fill CrammingHandoff.REQUEST_BUF with positions, AABBs, flags,
 *      and root vehicle ids.
 *   3. Native call → Rust runs the spatial hash push accumulator,
 *      skipping pairs of same-vehicle passengers, and counts pushable
 *      non-passenger overlaps per entity (`crowdedCount`).
 *   4. Read accumulated (dx, dz) + neighborCount + crowdedCount.
 *   5. Apply each mob's velocity delta via entity.addVelocity(dx,0,dz).
 *   6. Apply cramming damage when crowdedCount &gt; maxEntityCramming − 1
 *      AND the per-entity RandomSource fires the vanilla 1-in-4 check
 *      (entity.getRandom().nextInt(4) == 0). Mirrors vanilla
 *      LivingEntity.pushEntities (Yarn: tickCramming) bit-for-bit.
 *
 * Subsequent tickCramming calls in the same server tick are cancelled
 * by the Mixin without re-triggering — the tick guard is `world.getTime()`.
 *
 * ENABLED=true by default. /ferrite cramming off lets users fall back
 * to vanilla without restart for A/B verification.
 */
public final class CrammingDispatcher {

	public static volatile boolean ENABLED = true;

	// --- Per-server-tick state ---------------------------------------------
	private static long lastProcessedTick = Long.MIN_VALUE;
	private static final List<LivingEntity> MOB_SCRATCH = new ArrayList<>(1024);

	// Rate limiter for the "too many mobs to batch" warning. Without this,
	// a single overloaded tick would print MAX_ENTITIES-overflow lines on
	// every subsequent tick until the mob count drops, which is pure noise.
	private static final long OVERFLOW_LOG_MIN_GAP_NS = 1_000_000_000L;
	private static volatile long lastOverflowLogNs;

	// Parallel output arrays sized to MAX_ENTITIES; reused every tick.
	private static final double[] ACCUM_DX = new double[CrammingHandoff.MAX_ENTITIES];
	private static final double[] ACCUM_DZ = new double[CrammingHandoff.MAX_ENTITIES];
	private static final int[] NEIGHBOR_COUNT = new int[CrammingHandoff.MAX_ENTITIES];
	private static final int[] CROWDED_COUNT = new int[CrammingHandoff.MAX_ENTITIES];

	// --- Diagnostics -------------------------------------------------------
	private static final Logger LOGGER = LoggerFactory.getLogger("ferrite");
	private static long diagBatches  = 0;
	private static long diagMobs     = 0;
	private static long diagPushed   = 0;
	private static long diagDamaged  = 0;
	private static long diagLastLogNs = System.nanoTime();

	private CrammingDispatcher() {}

	/**
	 * Called from CrammingCancelMixin on the first tickCramming call of a
	 * server tick. Returns true if Rust handled the batch (caller should
	 * cancel the vanilla body); false if the caller should let vanilla run.
	 */
	public static boolean onTickCramming(LivingEntity caller) {
		if (!ENABLED || !RustBridge.NATIVE_AVAILABLE) return false;
		if (!(caller.getEntityWorld() instanceof ServerLevel world)) return false;

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

	private static void runBatch(ServerLevel world) {
		// 1. Collect all eligible mobs in this world.
		MOB_SCRATCH.clear();
		for (Entity e : world.iterateEntities()) {
			if (e instanceof Mob && e.isAlive() && !e.isRemoved()) {
				MOB_SCRATCH.add((LivingEntity) e);
			}
		}
		int count = MOB_SCRATCH.size();
		if (count == 0) return;
		if (count > CrammingHandoff.MAX_ENTITIES) {
			// Too many mobs — fall back. The Mixin already cancelled, so
			// vanilla won't run. Rare; safer than partial batching.
			maybeLogOverflow(count);
			return;
		}

		// 2–4. Build, dispatch, read.
		CrammingHandoff.buildRequests(MOB_SCRATCH);
		RustBridge.computeCramming(
			CrammingHandoff.REQUEST_BUF,
			CrammingHandoff.RESULT_BUF,
			count
		);
		CrammingHandoff.readResults(count, ACCUM_DX, ACCUM_DZ, NEIGHBOR_COUNT, CROWDED_COUNT);

		// 5. Apply pushes + cramming damage. Damage gate mirrors vanilla
		//    LivingEntity.pushEntities (Yarn: tickCramming):
		//      if (maxCramming > 0
		//          && pushableEntities.size() > maxCramming - 1
		//          && entity.random.nextInt(4) == 0) {
		//          int count = non-passenger pushable entities;
		//          if (count > maxCramming - 1) hurt(cramming, 6.0F);
		//      }
		//    Rust returns crowdedCount = the inner `count`. Per-entity
		//    RandomSource and the threshold check stay in Java so semantics are
		//    bit-for-bit identical to vanilla (per-entity RNG state).
		// Yarn 1.21.11: GameRules moved to net.minecraft.world.rule.GameRules
		// and the typed getInt accessor is gone — only getValue(rule)→Object
		// remains. Cast Integer for the int rule.
		int maxCramming = (Integer) world.getGameRules().getValue(
				net.minecraft.world.rule.GameRules.MAX_ENTITY_CRAMMING);
		int pushedThisBatch = 0;
		int damagedThisBatch = 0;
		for (int i = 0; i < count; i++) {
			LivingEntity e = MOB_SCRATCH.get(i);
			double dx = ACCUM_DX[i];
			double dz = ACCUM_DZ[i];
			if (dx != 0.0 || dz != 0.0) {
				e.addVelocity(dx, 0.0, dz);
				pushedThisBatch++;
			}

			if (maxCramming > 0
					&& CROWDED_COUNT[i] > maxCramming - 1
					&& e.getRandom().nextInt(4) == 0) {
				e.damage(world, world.getDamageSources().cramming(), 6.0F);
				damagedThisBatch++;
			}
		}

		diagBatches++;
		diagMobs += count;
		diagPushed += pushedThisBatch;
		diagDamaged += damagedThisBatch;
	}

	private static void maybeLogOverflow(int count) {
		long now = System.nanoTime();
		if (now - lastOverflowLogNs < OVERFLOW_LOG_MIN_GAP_NS) return;
		lastOverflowLogNs = now;
		LOGGER.warn("[cramming-dispatch] {} mobs exceeds MAX_ENTITIES={}, falling back to vanilla",
				count, CrammingHandoff.MAX_ENTITIES);
	}

	private static void maybeLogDiag() {
		long now = System.nanoTime();
		if (now - diagLastLogNs < 5_000_000_000L) return;
		diagLastLogNs = now;
		LOGGER.info(
			"[cramming-dispatch] batches={} mobsTotal={}  pushed={}  damaged={}",
			diagBatches, diagMobs, diagPushed, diagDamaged
		);
		diagBatches = 0;
		diagMobs = 0;
		diagPushed = 0;
		diagDamaged = 0;
	}
}
