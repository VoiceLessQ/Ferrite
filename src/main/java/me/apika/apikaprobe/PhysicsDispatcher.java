package me.apika.apikaprobe;

import me.apika.apikaprobe.mixin.EntityAdjustInvoker;

import net.minecraft.entity.Entity;
import net.minecraft.util.math.Vec3d;

/**
 * Routes an entity's adjustMovementForCollisions call to either the
 * Rust-backed bulk sweep or vanilla. Default ENABLED=false — every
 * call falls through to vanilla until step 5 activates the Rust path.
 *
 * Step 5 responsibilities:
 *   - per-tick snapshot build (only when mob AABB union changes meaningfully)
 *   - batched request fill across all mobs in the current tick window
 *   - one JNI call per batch (RustBridge.computeEntityPhysics)
 *   - scatter adjusted motions back by entityId; fallback flagged entries
 *     invoke the @Invoker path below.
 */
public final class PhysicsDispatcher {

	public static volatile boolean ENABLED = false;

	private PhysicsDispatcher() {}

	public static Vec3d adjust(Entity self, Vec3d motion) {
		if (!ENABLED || !RustBridge.NATIVE_AVAILABLE) {
			return ((EntityAdjustInvoker) self).ferrite$invokeAdjust(motion);
		}
		// TODO step 5: Rust-backed batched dispatch. Until then, vanilla.
		return ((EntityAdjustInvoker) self).ferrite$invokeAdjust(motion);
	}
}
