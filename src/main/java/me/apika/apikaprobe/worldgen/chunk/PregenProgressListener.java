package me.apika.apikaprobe.worldgen.chunk;

/**
 * Progress sink for {@link PregenDriver}. Implementations may be called
 * from chunk-system worker threads (onProgress) and from the pre-gen
 * driver thread (onComplete / onCancelled). Implementations must be
 * thread-safe and short-running; the driver fires {@link #onProgress}
 * on every chunk completion.
 */
public interface PregenProgressListener {
	void onProgress(int done, int total, double chunksPerSecond);
	void onComplete(int total);
	void onCancelled(int done, int total);
}
