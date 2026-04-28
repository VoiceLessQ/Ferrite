package me.apika.apikaprobe.worldgen;

import me.apika.apikaprobe.bridge.ExampleMod;
import me.apika.apikaprobe.RustBridge;

import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Phase 2.5 step 2b — per-instance rustName side map for vanilla's
 * {@code ChunkNoiseSampler$DensityInterpolator} (and its sibling
 * cache wrappers). Populated by
 * {@link me.apika.apikaprobe.mixin.CacheRouteCaptureMixin} every time
 * a fingerprint match succeeds; consumed by the upcoming
 * {@code fillSlice} mixin to know which Rust DF to bulk-call for each
 * interpolator in the batch.
 *
 * <p>IdentityHashMap keyed on the wrapper instance. NoiseChunkSampler
 * holds the instances for the lifetime of the chunk, so entries are
 * GC'd naturally when the chunk's NoiseChunkSampler is collected.
 *
 * <p>Synchronized via {@code Collections.synchronizedMap} because
 * chunkgen workers are concurrent (Worker-Main-N threads). The fast
 * path (lookup at fillSlice time) is read-only and uncontended in
 * the common case — workers operate on distinct chunks.
 */
public final class InterpolatorNameRegistry {
	private InterpolatorNameRegistry() {}

	private static final Map<Object, String> map =
			Collections.synchronizedMap(new IdentityHashMap<>());

	public static final AtomicLong recordCount = new AtomicLong();
	public static final AtomicLong duplicateCount = new AtomicLong();

	public static void record(Object wrapper, String rustName) {
		if (wrapper == null || rustName == null) return;
		String prev = map.putIfAbsent(wrapper, rustName);
		if (prev == null) {
			recordCount.incrementAndGet();
		} else {
			duplicateCount.incrementAndGet();
		}
	}

	/** Returns the registered name or null. */
	public static String nameFor(Object wrapper) {
		return wrapper == null ? null : map.get(wrapper);
	}

	public static int size() {
		return map.size();
	}

	public static void reset() {
		map.clear();
		recordCount.set(0);
		duplicateCount.set(0);
	}
}
