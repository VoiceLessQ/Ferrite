package me.apika.apikaprobe;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * Zero-copy handoff for wire-power BFS. Parallels
 * [CrammingHandoff] — direct ByteBuffer, native byte order, one
 * call per gate tick.
 *
 * Buffer layouts (must match rust/mod/src/redstone.rs — see
 * RedstoneNode / RedstoneResult structs):
 *
 * RedstoneNode (stride = 48 B):
 *   +0    i32    x
 *   +4    i32    y
 *   +8    i32    z
 *   +12   u8     currentPower     (0..15; power this wire currently
 *                                  holds in the world — used to decide
 *                                  whether to emit a delta)
 *   +13   u8     sourcePower      (0..15; strong power from non-wire
 *                                  neighbors: torches, repeaters,
 *                                  redstone blocks, levers, etc.
 *                                  Java computes this per-node by
 *                                  calling vanilla's getStrongPower)
 *   +14   u16    flags            (bit 0 = IS_WIRE, bit 1 = IS_SOURCE_ONLY)
 *   +16   i32[8] neighborIndices  (-1 = empty slot; otherwise an index
 *                                  into this same input array pointing
 *                                  at a connected wire neighbor.
 *                                  Java resolves connectivity via
 *                                  calculateWirePowerAt's neighbor-check
 *                                  rules — horizontal + up-step + down-step)
 *   +48   stride
 *
 * RedstoneResult (stride = 16 B):
 *   +0    i32    x
 *   +4    i32    y
 *   +8    i32    z
 *   +12   i32    newPower        (0..15)
 *
 * Only nodes whose power changed produce a result entry; Rust returns
 * the populated count. Java applies each result as
 * `world.setBlockState(pos, state.with(POWER, newPower), NOTIFY_LISTENERS)`.
 *
 * A network is everything BFS-reachable from the trigger position via
 * the vanilla connectivity rules. Capped at MAX_NODES so a large
 * interconnected base can't spike the buffer — if a cascade exceeds
 * the cap, Java falls back to vanilla's update path for that tick.
 */
public final class RedstoneHandoff {

	/**
	 * A/B switch for the Rust wire-BFS path. Default OFF — flip to true
	 * at runtime (breakpoint, mod command, or
	 * `RedstoneHandoff.USE_RUST = true`) to route wire cascades through
	 * [RedstoneRustDispatcher] instead of vanilla. The oracle
	 * instrumentation keeps running on whichever path is active, so
	 * mismatches surface the same way in both modes.
	 */
	public static volatile boolean USE_RUST = false;

	public static final int MAX_NODES       = 1024;
	public static final int REQUEST_STRIDE  = 48;
	public static final int RESULT_STRIDE   = 16;

	public static final int NEIGHBOR_SLOTS  = 8;
	public static final int NO_NEIGHBOR     = -1;

	// Flags (must match rust/mod/src/redstone.rs).
	public static final int FLAG_IS_WIRE         = 1 << 0;
	public static final int FLAG_IS_SOURCE_ONLY  = 1 << 1;

	public static final ByteBuffer REQUEST_BUF =
			ByteBuffer.allocateDirect(MAX_NODES * REQUEST_STRIDE).order(ByteOrder.nativeOrder());
	public static final ByteBuffer RESULT_BUF =
			ByteBuffer.allocateDirect(MAX_NODES * RESULT_STRIDE).order(ByteOrder.nativeOrder());

	private RedstoneHandoff() {}

	/**
	 * Clears the request buffer position markers before a new batch.
	 * Caller populates via absolute indexed writes, then calls the
	 * native function with the node count — native code reads from
	 * offset 0 and ignores position/limit.
	 */
	public static void resetRequestBuffer() {
		REQUEST_BUF.clear();
	}

	public static void writeNode(
			int index,
			int x, int y, int z,
			int currentPower,
			int sourcePower,
			int flags,
			int[] neighborIndices) {
		int base = index * REQUEST_STRIDE;
		REQUEST_BUF.putInt(base,      x);
		REQUEST_BUF.putInt(base + 4,  y);
		REQUEST_BUF.putInt(base + 8,  z);
		REQUEST_BUF.put   (base + 12, (byte) (currentPower & 0x0F));
		REQUEST_BUF.put   (base + 13, (byte) (sourcePower  & 0x0F));
		REQUEST_BUF.putShort(base + 14, (short) flags);
		for (int i = 0; i < NEIGHBOR_SLOTS; i++) {
			REQUEST_BUF.putInt(base + 16 + (i * 4),
					i < neighborIndices.length ? neighborIndices[i] : NO_NEIGHBOR);
		}
	}

	public static int readResultX(int index)        { return RESULT_BUF.getInt(index * RESULT_STRIDE); }
	public static int readResultY(int index)        { return RESULT_BUF.getInt(index * RESULT_STRIDE + 4); }
	public static int readResultZ(int index)        { return RESULT_BUF.getInt(index * RESULT_STRIDE + 8); }
	public static int readResultNewPower(int index) { return RESULT_BUF.getInt(index * RESULT_STRIDE + 12); }
}
