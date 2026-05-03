package me.apika.apikaprobe.redstone;

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
 * `world.setBlock(pos, state.setValue(POWER, newPower), UPDATE_CLIENTS)`.
 *
 * A network is everything BFS-reachable from the trigger position via
 * the vanilla connectivity rules. Capped at MAX_NODES so a large
 * interconnected base can't spike the buffer — if a cascade exceeds
 * the cap, Java falls back to vanilla's update path for that tick.
 *
 * <p>The active producer is {@link WireHandler#runRustBatch()}; the
 * shared structs and direct ByteBuffers are exposed here so that file
 * and the Rust kernel agree on layout.
 */
public final class RedstoneHandoff {

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

	// ----- AC kernel buffers (Phase 2 of the AC port) -----------------
	//
	// Richer per-node payload for the offer-based propagation kernel
	// in rust/mod/src/redstone_ac.rs. Layout:
	//
	// RedstoneAcNode (stride = 56 B):
	//   +0    i32    x
	//   +4    i32    y
	//   +8    i32    z
	//   +12   u8     currentPower
	//   +13   u8     externalPower
	//   +14   u8     initialFlowIn   (4-bit NESW mask, mirror of WireNode.flowIn)
	//   +15   u8     flags           (AC_FLAG_*)
	//   +16   i32[8] neighborIndices (-1 sentinel; same as old struct)
	//   +48   u8[8]  neighborIDir    (DIR_* per edge, 0xFF = unused slot)
	//   +56   stride
	//
	// RedstoneAcResult (stride = 24 B):
	//   +0    i32    x
	//   +4    i32    y
	//   +8    i32    z
	//   +12   i32    newPower
	//   +16   u8     newFlowIn
	//   +17   u8     resultFlags     (AC_RESULT_FLAG_*)
	//   +18   i8     iFlowDir        (-1 if ambiguous; Java falls back
	//                                 to wire.connections.iFlowDir)
	//   +19..+24    pad
	//
	// Results arrive in priority order (descending newPower, FIFO within
	// tier). Java applies setPower + queueNeighbors + updateNeighborShapes
	// in returned order without re-sorting through the priority queue.
	public static final int AC_REQUEST_STRIDE = 56;
	public static final int AC_RESULT_STRIDE  = 24;

	public static final int AC_FLAG_REMOVED      = 1 << 0;
	public static final int AC_FLAG_SHOULD_BREAK = 1 << 1;
	public static final int AC_FLAG_ROOT         = 1 << 2;
	public static final int AC_FLAG_ADDED        = 1 << 3;

	public static final int AC_RESULT_FLAG_REMOVED = 1 << 0;

	/** Sentinel for `neighborIDir[k]` when slot k is unused. */
	public static final byte AC_DIR_NONE = (byte) 0xFF;

	public static final ByteBuffer AC_REQUEST_BUF =
			ByteBuffer.allocateDirect(MAX_NODES * AC_REQUEST_STRIDE).order(ByteOrder.nativeOrder());
	public static final ByteBuffer AC_RESULT_BUF =
			ByteBuffer.allocateDirect(MAX_NODES * AC_RESULT_STRIDE).order(ByteOrder.nativeOrder());

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

	// ----- AC accessors -----------------------------------------------

	public static void resetAcRequestBuffer() {
		AC_REQUEST_BUF.clear();
	}

	public static void writeAcNode(
			int index,
			int x, int y, int z,
			int currentPower,
			int externalPower,
			int initialFlowIn,
			int flags,
			int[] neighborIndices,
			byte[] neighborIDir) {
		int base = index * AC_REQUEST_STRIDE;
		AC_REQUEST_BUF.putInt(base,      x);
		AC_REQUEST_BUF.putInt(base + 4,  y);
		AC_REQUEST_BUF.putInt(base + 8,  z);
		AC_REQUEST_BUF.put   (base + 12, (byte) (currentPower  & 0x0F));
		AC_REQUEST_BUF.put   (base + 13, (byte) (externalPower & 0x0F));
		AC_REQUEST_BUF.put   (base + 14, (byte) (initialFlowIn & 0x0F));
		AC_REQUEST_BUF.put   (base + 15, (byte) (flags         & 0xFF));
		for (int i = 0; i < NEIGHBOR_SLOTS; i++) {
			AC_REQUEST_BUF.putInt(base + 16 + (i * 4),
					i < neighborIndices.length ? neighborIndices[i] : NO_NEIGHBOR);
		}
		for (int i = 0; i < NEIGHBOR_SLOTS; i++) {
			AC_REQUEST_BUF.put(base + 48 + i,
					i < neighborIDir.length ? neighborIDir[i] : AC_DIR_NONE);
		}
	}

	public static int  readAcResultX(int index)        { return AC_RESULT_BUF.getInt(index * AC_RESULT_STRIDE); }
	public static int  readAcResultY(int index)        { return AC_RESULT_BUF.getInt(index * AC_RESULT_STRIDE + 4); }
	public static int  readAcResultZ(int index)        { return AC_RESULT_BUF.getInt(index * AC_RESULT_STRIDE + 8); }
	public static int  readAcResultNewPower(int index) { return AC_RESULT_BUF.getInt(index * AC_RESULT_STRIDE + 12); }
	public static int  readAcResultFlowIn(int index)   { return AC_RESULT_BUF.get  (index * AC_RESULT_STRIDE + 16) & 0xFF; }
	public static int  readAcResultFlags(int index)    { return AC_RESULT_BUF.get  (index * AC_RESULT_STRIDE + 17) & 0xFF; }
	public static int  readAcResultIFlowDir(int index) { return AC_RESULT_BUF.get  (index * AC_RESULT_STRIDE + 18); /* signed: -1 sentinel */ }
}
