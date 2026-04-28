package me.apika.apikaprobe.entity;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.List;

import net.minecraft.entity.LivingEntity;
import net.minecraft.util.math.Box;

/**
 * Zero-copy handoff for mob-vs-mob cramming. Much simpler than
 * PhysicsHandoff — no snapshot, no palette, no world state.
 *
 * Buffer layouts (must match rust/mod/src/cramming.rs):
 *
 * CrammingInput (stride = 40 B):
 *   +0   u32   entityId
 *   +4   u8    flags  (pushable | vehicle | passenger | noPhysics)
 *   +5   3B    pad
 *   +8   f64   x
 *   +16  f64   z
 *   +24  f32   aabbHalfWidth
 *   +28  f32   aabbMinY
 *   +32  f32   aabbMaxY
 *   +36  i32   rootVehicleId  (-1 if not riding anything)
 *
 * CrammingResult (stride = 32 B):
 *   +0   u32   entityId (echo)
 *   +4   u32   pad
 *   +8   f64   accumDx
 *   +16  f64   accumDz
 *   +24  u32   neighborCount  (all overlapping pairs, debug-only)
 *   +28  u32   crowdedCount   (overlapping pushable non-passenger
 *                             pairs — vanilla's cramming-damage count)
 */
public final class CrammingHandoff {

	public static final int MAX_ENTITIES   = 2048;
	public static final int REQUEST_STRIDE = 40;
	public static final int RESULT_STRIDE  = 32;

	// Flags (must match Rust cramming.rs)
	public static final int FLAG_PUSHABLE    = 1 << 0;
	public static final int FLAG_VEHICLE     = 1 << 1;
	public static final int FLAG_PASSENGER   = 1 << 2;
	public static final int FLAG_NO_PHYSICS  = 1 << 3;

	public static final ByteBuffer REQUEST_BUF =
			ByteBuffer.allocateDirect(MAX_ENTITIES * REQUEST_STRIDE).order(ByteOrder.nativeOrder());
	public static final ByteBuffer RESULT_BUF =
			ByteBuffer.allocateDirect(MAX_ENTITIES * RESULT_STRIDE).order(ByteOrder.nativeOrder());

	private CrammingHandoff() {}

	/**
	 * Fills REQUEST_BUF with one 40B entry per mob. Mobs with noPhysics or
	 * passenger state are included (Rust skips them on the flags check).
	 */
	public static void buildRequests(List<? extends LivingEntity> mobs) {
		int n = mobs.size();
		if (n > MAX_ENTITIES) {
			throw new IllegalStateException("cramming input exceeds MAX_ENTITIES: " + n);
		}

		REQUEST_BUF.clear();
		for (int i = 0; i < n; i++) {
			LivingEntity e = mobs.get(i);
			Box aabb = e.getBoundingBox();

			byte flags = 0;
			if (e.isPushable())    flags |= FLAG_PUSHABLE;
			if (e.hasPassengers()) flags |= FLAG_VEHICLE;
			if (e.hasVehicle())    flags |= FLAG_PASSENGER;
			if (e.noClip)          flags |= FLAG_NO_PHYSICS;

			float halfWidth = (float) ((aabb.maxX - aabb.minX) * 0.5);

			// Root vehicle id — vanilla's isPassengerOfSameVehicle uses
			// getRootVehicle() == other.getRootVehicle(). -1 sentinel for
			// "not riding anything," so two -1s never compare equal in Rust
			// (sentinel-equal pairs are explicitly skipped on Rust side).
			int rootVehicleId = -1;
			if (e.hasVehicle()) {
				net.minecraft.entity.Entity root = e.getRootVehicle();
				if (root != null) rootVehicleId = root.getId();
			}

			REQUEST_BUF.putInt(e.getId());          // +0
			REQUEST_BUF.put(flags);                 // +4
			REQUEST_BUF.put((byte) 0);              // +5 pad
			REQUEST_BUF.put((byte) 0);              // +6 pad
			REQUEST_BUF.put((byte) 0);              // +7 pad
			REQUEST_BUF.putDouble(e.getX());        // +8
			REQUEST_BUF.putDouble(e.getZ());        // +16
			REQUEST_BUF.putFloat(halfWidth);        // +24
			REQUEST_BUF.putFloat((float) aabb.minY);// +28
			REQUEST_BUF.putFloat((float) aabb.maxY);// +32
			REQUEST_BUF.putInt(rootVehicleId);      // +36
		}
		REQUEST_BUF.flip();
	}

	/**
	 * Reads `count` result entries from RESULT_BUF into parallel caller
	 * arrays. Zero per-call allocation. Array indices align with the
	 * input-order index of each mob in the list passed to buildRequests.
	 */
	public static void readResults(int count, double[] outDx, double[] outDz,
			int[] outNeighborCount, int[] outCrowdedCount) {
		if (count > MAX_ENTITIES) {
			throw new IllegalStateException("cramming count exceeds MAX_ENTITIES");
		}
		RESULT_BUF.position(0);
		for (int i = 0; i < count; i++) {
			int base = i * RESULT_STRIDE;
			// +0 entityId — caller already knows it by index
			outDx[i] = RESULT_BUF.getDouble(base + 8);
			outDz[i] = RESULT_BUF.getDouble(base + 16);
			outNeighborCount[i] = RESULT_BUF.getInt(base + 24);
			outCrowdedCount[i] = RESULT_BUF.getInt(base + 28);
		}
	}
}
