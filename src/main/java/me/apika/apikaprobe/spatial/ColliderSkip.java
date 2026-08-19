package me.apika.apikaprobe.spatial;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import net.fabricmc.fabric.api.event.lifecycle.v1.ServerEntityEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLevelEvents;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.animal.happyghast.HappyGhast;
import net.minecraft.world.entity.monster.Shulker;
import net.minecraft.world.entity.vehicle.boat.AbstractBoat;
import net.minecraft.world.entity.vehicle.minecart.AbstractMinecart;

/**
 * Caller-side skip for getEntityCollisions: the walk only ever accepts
 * hard-collidable entities (boat/shulker/happy-ghast family), so when a
 * level holds none of those, the correct result is the empty list and the
 * per-entity walk is skipped outright (LOCAL_DESIGN "caller-side query
 * avoidance": 87% of farm queries are this shape).
 *
 * Count is per-level and type-based (conservative overcount: a dead
 * shulker still counts). Skip requires BOTH the count at zero AND a
 * source whose canCollideWith is not widened (boats and minecarts also
 * accept pushables, so they always walk). Modded sources that widen
 * canCollideWith are the residual risk; the oracle samples eligible
 * queries, runs the vanilla walk, and logs any non-empty result.
 */
public final class ColliderSkip {
	private ColliderSkip() {}

	/** Master switch: default on since 0.7.2; kill with -Dferrite.entityquery.colliderskip=false. */
	public static final boolean ENABLED = !"false".equals(System.getProperty("ferrite.entityquery.colliderskip"));

	/** Oracle: sample 1 in N eligible queries with the vanilla walk; 0 disables. */
	public static final int ORACLE_RATE = Integer.getInteger("ferrite.entityquery.colliderskip.oracle", 16);

	private static final Map<ServerLevel, AtomicInteger> COUNTS = new ConcurrentHashMap<>();

	// Server-thread counters, drained by EntityQueryMonitor's 5 s report.
	public static long eligible;
	public static long skipped;
	public static long oracleWalks;
	public static long oracleNonEmpty;
	public static int sampleCounter;

	public static void register() {
		ServerEntityEvents.ENTITY_LOAD.register((entity, level) -> {
			if (isHardCollider(entity)) COUNTS.computeIfAbsent(level, l -> new AtomicInteger()).incrementAndGet();
		});
		ServerEntityEvents.ENTITY_UNLOAD.register((entity, level) -> {
			if (isHardCollider(entity)) {
				AtomicInteger c = COUNTS.get(level);
				if (c != null) c.decrementAndGet();
			}
		});
		ServerLevelEvents.UNLOAD.register((server, level) -> COUNTS.remove(level));
	}

	/** Types whose canBeCollidedWith can ever return true (26.2 override set). */
	public static boolean isHardCollider(Entity entity) {
		return entity instanceof AbstractBoat || entity instanceof Shulker || entity instanceof HappyGhast;
	}

	/** Sources whose canCollideWith accepts more than hard colliders. */
	public static boolean isWideningSource(Entity source) {
		return source instanceof AbstractBoat || source instanceof AbstractMinecart;
	}

	public static boolean levelHasHardColliders(ServerLevel level) {
		AtomicInteger c = COUNTS.get(level);
		return c != null && c.get() > 0;
	}

	public static int count(ServerLevel level) {
		AtomicInteger c = COUNTS.get(level);
		return c == null ? 0 : c.get();
	}
}
