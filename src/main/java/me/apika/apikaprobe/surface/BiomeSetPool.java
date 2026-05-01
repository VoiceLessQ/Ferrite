package me.apika.apikaprobe.surface;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Per-tree pool of distinct biome sets referenced by
 * {@code BiomeMaterialCondition} nodes.
 *
 * Equality is by **content**, not object identity — two
 * {@code RegistryEntryList&lt;Biome&gt;} instances holding the same
 * registry IDs (regardless of construction order) collapse to the same
 * pool entry. This is the win behind pooling: 39 vanilla biome
 * conditions in the default overworld tree may reference only ~10–15
 * distinct biome sets (e.g. "any cold biome", "any badlands variant").
 *
 * Canonical key: sorted {@code List&lt;String&gt;} of biome registry
 * names. Sorting before interning makes the key deterministic
 * regardless of source iteration order.
 *
 * On {@link Builder#freeze} the pool is sorted by canonical key so pool
 * IDs are stable across recompiles, matching the determinism guarantee
 * that {@link PerWorldBlockStateTable} provides for blockstate IDs.
 */
public final class BiomeSetPool {

	public static Builder builder() { return new Builder(); }

	public static final class Builder {
		private final Map<List<String>, Integer> insertionIds = new HashMap<>();
		private final List<List<String>> sets = new ArrayList<>();

		/**
		 * Interns a biome set. Caller must pass an already-canonical key
		 * (sorted list of registry-name strings). Returns insertion-order
		 * pool ID, deduped on content equality.
		 */
		public int intern(List<String> canonicalKey) {
			Integer existing = insertionIds.get(canonicalKey);
			if (existing != null) return existing;
			int id = sets.size();
			sets.add(canonicalKey);
			insertionIds.put(canonicalKey, id);
			return id;
		}

		public BiomeSetPool freeze() {
			List<List<String>> sorted = new ArrayList<>(sets);
			sorted.sort(BiomeSetPool::compareKeys);
			int[] insertionToFinal = new int[sets.size()];
			for (int i = 0; i < sorted.size(); i++) {
				int oldId = insertionIds.get(sorted.get(i));
				insertionToFinal[oldId] = i;
			}
			@SuppressWarnings("unchecked")
			List<String>[] arr = sorted.toArray(new List[0]);
			return new BiomeSetPool(arr, insertionToFinal);
		}
	}

	private final List<String>[] idToSet;
	private final int[] insertionToFinal;

	private BiomeSetPool(List<String>[] idToSet, int[] insertionToFinal) {
		this.idToSet = idToSet;
		this.insertionToFinal = insertionToFinal;
	}

	public List<String>[] idToSet() { return idToSet; }
	public int finalIdFor(int insertionId) { return insertionToFinal[insertionId]; }
	public int size() { return idToSet.length; }

	/**
	 * Convert an arbitrary biome-collection (Set, List, RegistryEntryList,
	 * Stream-able, or single ResourceKey/Holder) to the canonical
	 * sorted-list-of-registry-names key. Best-effort with reflection;
	 * elements whose registry name can't be resolved fall back to
	 * toString.
	 */
	public static List<String> canonicalKey(Object biomeCollection) {
		List<String> out = new ArrayList<>();
		collectNames(biomeCollection, out);
		Collections.sort(out);
		return Collections.unmodifiableList(out);
	}

	private static void collectNames(Object obj, List<String> out) {
		if (obj == null) return;
		if (obj instanceof Iterable<?> iter) {
			for (Object e : iter) collectNames(e, out);
			return;
		}
		// RegistryEntryList / similar — try a no-arg stream() then fall through
		try {
			java.lang.reflect.Method stream = obj.getClass().getMethod("stream");
			Object s = stream.invoke(obj);
			if (s instanceof java.util.stream.Stream<?> jstream) {
				jstream.forEach(e -> collectNames(e, out));
				return;
			}
		} catch (NoSuchMethodException ignored) {
			// not stream-able
		} catch (ReflectiveOperationException | RuntimeException ignored) {
			// fall through to toString
		}
		out.add(registryNameOf(obj));
	}

	/**
	 * Resolve a single biome reference to its registry name string.
	 * Tries getValue() (ResourceKey) → getKey() (Holder,
	 * returns Optional&lt;ResourceKey&gt;) → toString fallback.
	 */
	private static String registryNameOf(Object o) {
		if (o == null) return "";
		// ResourceKey has getValue() returning Identifier
		String s = invokeAndStringify(o, "getValue");
		if (s != null) return s;
		// Holder has getKey() returning Optional<ResourceKey<T>>
		try {
			java.lang.reflect.Method m = o.getClass().getMethod("getKey");
			Object k = m.invoke(o);
			if (k instanceof java.util.Optional<?> opt && opt.isPresent()) {
				String s2 = invokeAndStringify(opt.get(), "getValue");
				if (s2 != null) return s2;
				return opt.get().toString();
			}
			if (k != null) {
				String s2 = invokeAndStringify(k, "getValue");
				if (s2 != null) return s2;
				return k.toString();
			}
		} catch (NoSuchMethodException ignored) {
			// not a Holder
		} catch (ReflectiveOperationException | RuntimeException ignored) {
			// fall through
		}
		return o.toString();
	}

	private static String invokeAndStringify(Object o, String methodName) {
		try {
			java.lang.reflect.Method m = o.getClass().getMethod(methodName);
			Object v = m.invoke(o);
			return v == null ? null : v.toString();
		} catch (NoSuchMethodException e) {
			return null;
		} catch (ReflectiveOperationException | RuntimeException e) {
			return null;
		}
	}

	private static int compareKeys(List<String> a, List<String> b) {
		int n = Math.min(a.size(), b.size());
		for (int i = 0; i < n; i++) {
			int c = a.get(i).compareTo(b.get(i));
			if (c != 0) return c;
		}
		return Integer.compare(a.size(), b.size());
	}
}
