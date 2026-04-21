package me.apika.apikaprobe.surface;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Per-tree blockstate ID table.
 *
 * Built incrementally during compile: each unique blockstate gets an
 * insertion-order ID. When the compile finishes, {@link Builder#freeze}
 * sorts the table by deterministic key (registry name preferred,
 * toString fallback) and produces a {@code remap[]} the compiler uses
 * to patch the IDs in the emitted bytecode.
 *
 * Why deterministic IDs matter: the spec mandates the bytecode is
 * cached per-world and reused across many chunks. If two compiles of
 * the same rule tree could produce different IDs (because reflection
 * field iteration order changed), the cached bytecode and a freshly
 * compiled validation tree would disagree on what {@code id=42} means
 * — silently miscompiling. Sort-on-freeze removes that class of bug
 * for the cost of one comparator call per unique blockstate at world
 * load.
 */
public final class PerWorldBlockStateTable {

	public static Builder builder() { return new Builder(); }

	public static final class Builder {
		private final Map<Object, Integer> insertionIds = new HashMap<>();
		private final List<Object> states = new ArrayList<>();

		/**
		 * Returns the insertion-order ID for {@code state}. Same object
		 * (by equals) returns the same ID across calls.
		 */
		public int intern(Object state) {
			Integer existing = insertionIds.get(state);
			if (existing != null) return existing;
			int id = states.size();
			states.add(state);
			insertionIds.put(state, id);
			return id;
		}

		public PerWorldBlockStateTable freeze() {
			Object[] sorted = states.toArray();
			java.util.Arrays.sort(sorted, Comparator.comparing(PerWorldBlockStateTable::sortKey));
			int[] insertionToFinal = new int[states.size()];
			for (int i = 0; i < sorted.length; i++) {
				int oldId = insertionIds.get(sorted[i]);
				insertionToFinal[oldId] = i;
			}
			return new PerWorldBlockStateTable(sorted, insertionToFinal);
		}
	}

	private final Object[] idToState;
	private final int[] insertionToFinal;

	private PerWorldBlockStateTable(Object[] idToState, int[] insertionToFinal) {
		this.idToState = idToState;
		this.insertionToFinal = insertionToFinal;
	}

	public Object[] idToState() { return idToState; }
	public int finalIdFor(int insertionId) { return insertionToFinal[insertionId]; }
	public int size() { return idToState.length; }

	// --- sort key resolution ---------------------------------------------

	/**
	 * Stable key for sorting. Tries vanilla registry name (preferred —
	 * survives MC version changes that reorder field iteration). Falls
	 * back to toString for non-vanilla objects (synthetic test nodes,
	 * datapack-added states without a registered Block).
	 */
	private static String sortKey(Object state) {
		Object block = tryGetBlock(state);
		if (block != null) {
			String registryName = tryRegistryName(block);
			if (registryName != null) return "r:" + registryName;
		}
		return "s:" + (state == null ? "" : state.toString());
	}

	private static Object tryGetBlock(Object state) {
		if (state == null) return null;
		try {
			java.lang.reflect.Method m = state.getClass().getMethod("getBlock");
			return m.invoke(state);
		} catch (NoSuchMethodException e) {
			return state; // already a Block, or not a vanilla state
		} catch (ReflectiveOperationException | RuntimeException e) {
			return null;
		}
	}

	private static String tryRegistryName(Object block) {
		try {
			Class<?> registriesCls = Class.forName("net.minecraft.registry.Registries");
			Object blockReg = registriesCls.getField("BLOCK").get(null);
			for (java.lang.reflect.Method m : blockReg.getClass().getMethods()) {
				if (!m.getName().equals("getId") || m.getParameterCount() != 1) continue;
				Object id = m.invoke(blockReg, block);
				return id == null ? null : id.toString();
			}
			return null;
		} catch (ReflectiveOperationException | RuntimeException e) {
			return null;
		}
	}
}
