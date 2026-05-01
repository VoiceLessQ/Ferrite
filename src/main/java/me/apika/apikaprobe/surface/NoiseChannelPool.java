package me.apika.apikaprobe.surface;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Per-tree pool of distinct noise channel references used by
 * {@code NoiseThresholdMaterialCondition} nodes.
 *
 * Same intern-and-sort design as {@link PerWorldBlockStateTable} and
 * {@link BiomeSetPool}: keys are single registry-name strings (the
 * noise parameter ID), pool IDs are stable across recompiles after
 * {@link Builder#freeze}.
 *
 * Why a separate class instead of reusing
 * {@link PerWorldBlockStateTable}: keys here are simple strings with
 * no registry-lookup branch needed; sortKey is just identity. Keeping
 * the type distinct also means the bytecode evaluator (eventual Rust
 * code) can index three independent tables without confusion.
 */
public final class NoiseChannelPool {

	public static Builder builder() { return new Builder(); }

	public static final class Builder {
		private final Map<String, Integer> insertionIds = new HashMap<>();
		private final List<String> channels = new ArrayList<>();

		public int intern(String registryName) {
			Integer existing = insertionIds.get(registryName);
			if (existing != null) return existing;
			int id = channels.size();
			channels.add(registryName);
			insertionIds.put(registryName, id);
			return id;
		}

		public NoiseChannelPool freeze() {
			String[] sorted = channels.toArray(new String[0]);
			java.util.Arrays.sort(sorted);
			int[] insertionToFinal = new int[channels.size()];
			for (int i = 0; i < sorted.length; i++) {
				int oldId = insertionIds.get(sorted[i]);
				insertionToFinal[oldId] = i;
			}
			return new NoiseChannelPool(sorted, insertionToFinal);
		}
	}

	private final String[] idToChannel;
	private final int[] insertionToFinal;

	private NoiseChannelPool(String[] idToChannel, int[] insertionToFinal) {
		this.idToChannel = idToChannel;
		this.insertionToFinal = insertionToFinal;
	}

	public String[] idToChannel() { return idToChannel; }
	public int finalIdFor(int insertionId) { return insertionToFinal[insertionId]; }
	public int size() { return idToChannel.length; }

	/**
	 * Resolve a noise reference (ResourceKey or bare Identifier or
	 * String) to its registry-name string. Tries getValue() first
	 * (ResourceKey path), falls back to toString.
	 */
	public static String resolveName(Object noiseRef) {
		if (noiseRef == null) return "";
		try {
			java.lang.reflect.Method m = noiseRef.getClass().getMethod("getValue");
			Object v = m.invoke(noiseRef);
			if (v != null) return v.toString();
		} catch (NoSuchMethodException ignored) {
			// not a ResourceKey
		} catch (ReflectiveOperationException | RuntimeException ignored) {
			// fall through
		}
		return noiseRef.toString();
	}
}
