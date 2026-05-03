package me.apika.apikaprobe.worldgen.chunk;

import java.util.ArrayDeque;

/**
 * 30-second sliding window for chunks-per-second from a monotonically
 * increasing completion counter. Each {@link #record(long)} call
 * appends {timestamp_ms, count} and trims entries older than the window.
 * {@link #chunksPerSecond()} reports the slope across the surviving
 * window: {@code (newest.count - oldest.count) / elapsedSeconds}.
 *
 * <p>Synchronized because record() runs on whatever executor completes
 * the chunk future, while readers may be on the server or render thread.
 */
public final class SlidingWindowRate {
	private static final long WINDOW_MS = 30_000L;

	private final ArrayDeque<long[]> samples = new ArrayDeque<>();

	public synchronized void record(long completedCount) {
		final long now = System.currentTimeMillis();
		samples.addLast(new long[]{now, completedCount});
		while (!samples.isEmpty() && now - samples.peekFirst()[0] > WINDOW_MS) {
			samples.pollFirst();
		}
	}

	public synchronized double chunksPerSecond() {
		if (samples.size() < 2) return 0.0;
		final long[] oldest = samples.peekFirst();
		final long[] newest = samples.peekLast();
		final double elapsedSec = (newest[0] - oldest[0]) / 1000.0;
		if (elapsedSec <= 0.0) return 0.0;
		return (newest[1] - oldest[1]) / elapsedSec;
	}
}
