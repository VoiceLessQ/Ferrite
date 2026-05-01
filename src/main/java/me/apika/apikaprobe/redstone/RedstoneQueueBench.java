package me.apika.apikaprobe.redstone;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.RandomSource;

import me.apika.apikaprobe.RustBridge;

/**
 * Phase 1 of the AC Rust core port
 * (see {@code docs/REDSTONE_PORT_PLAN.md}).
 *
 * <p>Micro-benchmark comparing Ferrite's Java {@link PriorityQueue}
 * against a Rust port with the same semantics, on workloads of
 * N = 100, 1000, 10000 items. JNI round-trip cost is counted —
 * the Rust side must beat Java by ≥2× at N ≥ 1000 for Phase 2
 * to be green-lit.
 *
 * <p>Workload: generate N (id, priority) pairs with priorities
 * uniformly in [0, 15] (matching AC's signal range), offer all,
 * then poll all. No interleaving — pure throughput. Runs each
 * N 10 times after a warmup pass; reports median wall time.
 */
public final class RedstoneQueueBench {

	private RedstoneQueueBench() {}

	public record Result(int n, double javaMedianMs, double rustMedianMs, double ratio) {
		public boolean rustWins2x() { return rustMedianMs > 0 && javaMedianMs / rustMedianMs >= 2.0; }
	}

	public static Result run(int n) {
		return run(n, 10, 2);
	}

	public static Result run(int n, int iterations, int warmup) {
		// Deterministic test data so both paths see identical input.
		RandomSource rng = new RandomSource(0xFE11171EL);
		int[] ids = new int[n];
		byte[] priorities = new byte[n];
		for (int i = 0; i < n; i++) {
			ids[i] = i; // unique ids — deterministic poll order is easy to verify
			priorities[i] = (byte) rng.nextInt(16);
		}

		// Pre-allocate Java scratch and Rust ByteBuffers once; reused each iteration.
		PriorityQueue javaQueue = new PriorityQueue();
		Node[] javaNodes = new Node[n];
		for (int i = 0; i < n; i++) javaNodes[i] = new BenchNode(ids[i], priorities[i] & 0xFF);

		ByteBuffer pairsBuf = ByteBuffer.allocateDirect(n * 5).order(ByteOrder.LITTLE_ENDIAN);
		ByteBuffer resultsBuf = ByteBuffer.allocateDirect(n * 4).order(ByteOrder.LITTLE_ENDIAN);
		pairsBuf.clear();
		for (int i = 0; i < n; i++) {
			pairsBuf.putInt(ids[i]);
			pairsBuf.put(priorities[i]);
		}

		// Warmup — JIT warm + native lib paged in.
		for (int w = 0; w < warmup; w++) {
			javaRun(javaQueue, javaNodes);
			if (RustBridge.NATIVE_AVAILABLE) {
				RustBridge.benchRedstoneQueue(pairsBuf, resultsBuf, n);
			}
		}

		// Measured passes. Record each; return median.
		long[] javaNanos = new long[iterations];
		long[] rustNanos = new long[iterations];
		for (int i = 0; i < iterations; i++) {
			long t0 = System.nanoTime();
			javaRun(javaQueue, javaNodes);
			javaNanos[i] = System.nanoTime() - t0;

			if (RustBridge.NATIVE_AVAILABLE) {
				long t1 = System.nanoTime();
				RustBridge.benchRedstoneQueue(pairsBuf, resultsBuf, n);
				rustNanos[i] = System.nanoTime() - t1;
			} else {
				rustNanos[i] = Long.MAX_VALUE;
			}
		}

		double javaMs = median(javaNanos) / 1_000_000.0;
		double rustMs = median(rustNanos) / 1_000_000.0;
		double ratio = rustMs > 0 ? javaMs / rustMs : 0;
		return new Result(n, javaMs, rustMs, ratio);
	}

	/** Run offer-all + poll-all on the shared Java queue. */
	private static void javaRun(PriorityQueue q, Node[] nodes) {
		q.clear();
		for (Node n : nodes) {
			q.offer(n);
		}
		while (q.poll() != null) {
			// drain
		}
	}

	private static long median(long[] arr) {
		long[] sorted = arr.clone();
		java.util.Arrays.sort(sorted);
		return sorted[sorted.length / 2];
	}

	/**
	 * Minimal {@link Node} subclass for the bench. Real AC uses
	 * {@link WireNode} which carries world state; the queue doesn't
	 * touch any of it, so a lightweight stand-in suffices.
	 */
	static final class BenchNode extends Node {
		private final int id;
		private final int priority;
		BenchNode(int id, int priority) {
			super(null); // LevelHelper not touched by queue ops
			this.id = id;
			this.priority = priority;
		}
		@Override public int priority() { return priority; }
		@Override public boolean isWire() { return false; }
		@Override public WireNode asWire() { throw new UnsupportedOperationException(); }
		@SuppressWarnings("unused") public int id() { return id; }
	}
}
