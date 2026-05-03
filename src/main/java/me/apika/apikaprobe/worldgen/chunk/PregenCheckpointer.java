package me.apika.apikaprobe.worldgen.chunk;

/**
 * Persistence sink for {@link PregenDriver}. Called periodically from
 * the driver thread (every {@link PregenDriver#CHECKPOINT_INTERVAL}
 * submissions) and once at the end. Implementations must be safe to
 * call from a non-server-thread.
 */
public interface PregenCheckpointer {
	/** Persist the current iterator state so a subsequent restart can
	 *  resume from this position. May be called many times during a run. */
	void onCheckpoint(ConcentricChunkIterator.State state);

	/** Final call. {@code completedNormally=true} means full radius
	 *  reached (delete snapshot, write done-marker); false means cancel
	 *  or interruption (keep snapshot for resume next launch). */
	void onFinalize(boolean completedNormally);
}
