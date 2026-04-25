package me.apika.apikaprobe;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Phase 2.5 step 1 — deep router walk.
 *
 * <p>Sister to {@link DensityFunctionWalker}: instead of encoding a tree
 * to Rust bytecode, this enumerates every {@code Marker(type, child)}
 * instance reachable from a root DF, registers {@code child} under a
 * synthetic name (so Rust can sample the wrapped subtree directly), and
 * records {@code marker → syntheticName} in an IdentityHashMap.
 *
 * <p>The map is consumed by cache mixins ({@code RustFlatCache} and the
 * upcoming NoiseInterpolator / CacheAllInCell variants): when vanilla's
 * {@code NoiseChunk.wrapNew} hands us a Marker we recognize, we know
 * which Rust DF to bulk-call to pre-fill the cache's array.
 *
 * <p>Why deep walk? The previous Phase 2.5 attempt only registered the
 * top-level router DFs (climate axes + finalDensity). Interior Markers
 * — e.g. the {@code Marker(FlatCache, X)} inside {@code finalDensity} —
 * were never indexed, so {@code identifiedRouterDfs.get(marker)} always
 * returned null and the FlatCache mixin fired 0 times.
 *
 * <p>Yarn class names rotate; we dispatch on simple-name fragments the
 * same way {@link DensityFunctionWalker} does, so the walker survives
 * mapping drift.
 */
public final class DeepMarkerWalker {

	private DeepMarkerWalker() {}

	/** Per-root-call counter used to mint stable synthetic names. */
	private static final AtomicInteger globalIndex = new AtomicInteger();

	public static final class Result {
		public final Map<Object, String> markerToName;
		public final int markersFound;
		public final int markersRegistered;
		public final int registrationFailures;

		Result(Map<Object, String> map, int found, int registered, int failed) {
			this.markerToName = map;
			this.markersFound = found;
			this.markersRegistered = registered;
			this.registrationFailures = failed;
		}
	}

	/**
	 * Walk {@code root}, register every reachable Marker's inner subtree
	 * to Rust under {@code ferrite:auto/<rootName>/<kind>_<i>} names, and
	 * return the resulting Marker→name identity map (accumulating into
	 * {@code accumulator} if non-null).
	 */
	public static Result walk(Object root, String rootName, Map<Object, String> accumulator) {
		Map<Object, String> map = accumulator != null ? accumulator : new IdentityHashMap<>();
		Counters c = new Counters();
		if (root != null) {
			recurse(root, rootName, map, c, 0);
		}
		return new Result(map, c.found, c.registered, c.failed);
	}

	private static final class Counters {
		int found;
		int registered;
		int failed;
	}

	private static void recurse(Object node, String rootName, Map<Object, String> map,
			Counters c, int depth) {
		if (node == null || depth > 64) return;
		String cls = node.getClass().getSimpleName();

		// HolderHolder unwrap — same logic as DensityFunctionWalker.
		if (cls.equals("HolderHolder") || cls.endsWith("$HolderHolder")
				|| cls.equals("RegistryEntryHolder") || cls.endsWith("$RegistryEntryHolder")) {
			Object inner = unwrapHolder(node);
			if (inner != null) recurse(inner, rootName, map, c, depth + 1);
			return;
		}

		// Marker / Wrapping. This is what we're looking for.
		if (cls.contains("Marker") || cls.contains("Wrapping")) {
			// Skip BeardifierMarker — it's a singleton enum, not a real
			// wrapping marker. DensityFunctionWalker collapses it to const(0).
			if (cls.contains("BeardifierMarker") || cls.equals("BeardifierOrMarker")) {
				return;
			}
			handleMarker(node, rootName, map, c, depth);
			return;
		}

		// Recurse into known children. Mirror DensityFunctionWalker's
		// dispatch order — most specific first.
		if (cls.contains("BlendAlpha") || cls.contains("BlendOffset")) return;
		if (cls.contains("BlendDensity")) {
			recurseChild(node, new String[]{"input", "wrapped"}, rootName, map, c, depth);
			return;
		}
		if (cls.contains("YClampedGradient") || cls.contains("ClampedYGradient")
				|| cls.contains("YGradient")) return;
		if (cls.contains("ShiftedNoise")) {
			recurseChild(node, new String[]{"shiftX"}, rootName, map, c, depth);
			recurseChild(node, new String[]{"shiftY"}, rootName, map, c, depth);
			recurseChild(node, new String[]{"shiftZ"}, rootName, map, c, depth);
			return;
		}
		if (cls.equals("ShiftA") || cls.endsWith("$ShiftA")
				|| cls.equals("ShiftB") || cls.endsWith("$ShiftB")
				|| cls.equals("Shift")  || cls.endsWith("$Shift")) return;
		if (cls.contains("WeirdScaledSampler") || cls.contains("WeirdScaled")) {
			recurseChild(node, new String[]{"input"}, rootName, map, c, depth);
			return;
		}
		if (cls.contains("RangeChoice") || cls.contains("RangeSelector")) {
			recurseChild(node, new String[]{"input"}, rootName, map, c, depth);
			recurseChild(node, new String[]{"whenInRange"}, rootName, map, c, depth);
			recurseChild(node, new String[]{"whenOutOfRange"}, rootName, map, c, depth);
			return;
		}
		if (cls.contains("Constant")) return;
		if (cls.equals("InterpolatedNoiseSampler") || cls.endsWith("$InterpolatedNoiseSampler")
				|| cls.contains("BlendedNoise")) return;
		if (cls.contains("Noise")) return;
		if (cls.contains("Clamp")) {
			recurseChild(node, new String[]{"input"}, rootName, map, c, depth);
			return;
		}
		if (cls.contains("Spline")) {
			recurseSpline(node, rootName, map, c, depth);
			return;
		}
		if (cls.contains("TwoArgument") || cls.contains("BinaryOperation")) {
			recurseChild(node, new String[]{"argument1", "input1", "first"}, rootName, map, c, depth);
			recurseChild(node, new String[]{"argument2", "input2", "second"}, rootName, map, c, depth);
			return;
		}
		if (cls.contains("Mapped") || cls.contains("Unary") || cls.contains("UnaryOperation")) {
			recurseChild(node, new String[]{"input", "wrapped"}, rootName, map, c, depth);
			return;
		}
		// Unknown node type — leave for DensityFunctionWalker to log; we
		// just don't descend further (no child accessors known).
	}

	private static void recurseChild(Object parent, String[] accessorNames,
			String rootName, Map<Object, String> map, Counters c, int depth) {
		Object child = invokeAny(parent, accessorNames);
		if (child != null) recurse(child, rootName, map, c, depth + 1);
	}

	/**
	 * Encode the Marker's wrapped child as a Rust DF subtree, register
	 * it under a synthetic name, record marker→name in the map, then
	 * recurse INTO the child to find nested markers.
	 */
	private static void handleMarker(Object marker, String rootName,
			Map<Object, String> map, Counters c, int depth) {
		c.found++;
		// Don't re-register a Marker we've already seen (shared subtrees).
		if (map.containsKey(marker)) return;

		Object inner = invokeAny(marker, new String[]{"wrapped", "input"});
		if (inner == null) return;

		// Synthetic name. Use the marker kind for human readability.
		String kind = readEnumName(marker, new String[]{"type"});
		if (kind == null) kind = "marker";
		String safeRoot = sanitize(rootName);
		String name = "ferrite:auto/" + safeRoot + "/" + kind + "_" + globalIndex.getAndIncrement();

		// Encode the inner subtree (includes Markers nested inside it,
		// which Rust will handle via OP_MARKER passthrough).
		ByteBuffer bytecode = DensityFunctionWalker.encode(inner);
		if (bytecode == null) {
			c.failed++;
		} else {
			byte[] nameBytes = name.getBytes(StandardCharsets.UTF_8);
			ByteBuffer nameBuf = ByteBuffer.allocateDirect(nameBytes.length)
					.order(ByteOrder.nativeOrder());
			nameBuf.put(nameBytes);
			nameBuf.flip();
			boolean ok = RustBridge.registerDensityFunction(
					nameBuf, nameBytes.length, bytecode, bytecode.limit());
			if (ok) {
				map.put(marker, name);
				c.registered++;
			} else {
				c.failed++;
			}
		}

		// Recurse into the child so we also catch nested Markers further
		// down (e.g. Marker(FlatCache, Marker(CacheOnce, X))).
		recurse(inner, rootName, map, c, depth + 1);
	}

	/** Walk a Spline node to find DF coordinate references and recurse. */
	private static void recurseSpline(Object splineNode, String rootName,
			Map<Object, String> map, Counters c, int depth) {
		Object spline = invokeAny(splineNode, new String[]{"spline"});
		if (spline == null) return;
		walkSpline(spline, rootName, map, c, depth + 1);
	}

	private static void walkSpline(Object spline, String rootName,
			Map<Object, String> map, Counters c, int depth) {
		if (spline == null || depth > 64) return;
		String cls = spline.getClass().getSimpleName();
		if (cls.contains("Constant") || cls.contains("FixedFloatFunction")) return;
		if (cls.contains("Multipoint") || cls.contains("MultiPoint")
				|| cls.contains("Implementation")) {
			Object coord = invokeAny(spline, new String[]{"coordinate", "locationFunction"});
			if (coord != null) {
				Object coordFn = extractSplineCoordFn(coord);
				if (coordFn != null) recurse(coordFn, rootName, map, c, depth + 1);
			}
			Object valuesObj = invokeAny(spline, new String[]{"values"});
			if (valuesObj instanceof List<?> values) {
				for (Object v : values) walkSpline(v, rootName, map, c, depth + 1);
			}
		}
	}

	private static Object extractSplineCoordFn(Object coord) {
		Object holder = invokeAny(coord, new String[]{
				"function", "densityFunction", "densityFunctionWrapper",
				"holder", "delegate", "df",
		});
		if (holder == null) {
			for (Method m : coord.getClass().getDeclaredMethods()) {
				if (m.getParameterCount() != 0) continue;
				try {
					m.setAccessible(true);
					Object v = m.invoke(coord);
					if (v == null) continue;
					String tn = v.getClass().getSimpleName();
					if (tn.contains("Holder") || tn.contains("RegistryEntry")) {
						holder = v; break;
					}
					if (tn.contains("DensityFunction") || tn.contains("Wrapping")
							|| tn.contains("BinaryOperation") || tn.contains("Noise")) {
						return v;
					}
				} catch (ReflectiveOperationException ignored) {
					// next
				}
			}
		}
		if (holder == null) return null;
		Object value = invokeAny(holder, new String[]{"value", "comp_349", "get"});
		if (value != null) return value;
		String tn = holder.getClass().getSimpleName();
		if (tn.contains("DensityFunction") || tn.contains("Wrapping")
				|| tn.contains("BinaryOperation") || tn.contains("Noise")) {
			return holder;
		}
		return null;
	}

	private static Object unwrapHolder(Object node) {
		String[] holderAccessors = {"function", "entry", "holder", "delegate"};
		for (String acc : holderAccessors) {
			Object inner = invokeNoArg(node, acc);
			if (inner == null) continue;
			Object value = invokeAny(inner, new String[]{"value", "comp_349"});
			if (value != null) return value;
			Object get = invokeNoArg(inner, "get");
			if (get != null) return get;
		}
		Class<?> c = node.getClass();
		while (c != null && c != Object.class) {
			for (Field f : c.getDeclaredFields()) {
				try {
					f.setAccessible(true);
					Object v = f.get(node);
					if (v == null) continue;
					String typeName = v.getClass().getSimpleName();
					if (typeName.equals("String") || typeName.equals("HashMap")
							|| typeName.equals("ArrayList")) continue;
					Object value = invokeNoArg(v, "value");
					if (value != null) return value;
					if (typeName.contains("DensityFunction") || typeName.contains("Noise")
							|| typeName.contains("Spline")) {
						return v;
					}
				} catch (ReflectiveOperationException ignored) {
					// next
				}
			}
			c = c.getSuperclass();
		}
		return null;
	}

	private static Object invokeNoArg(Object o, String name) {
		try {
			Method m = o.getClass().getMethod(name);
			return m.invoke(o);
		} catch (ReflectiveOperationException | RuntimeException e) {
			return null;
		}
	}

	private static Object invokeAny(Object o, String[] names) {
		for (String n : names) {
			Object v = invokeNoArg(o, n);
			if (v != null) return v;
		}
		return null;
	}

	private static String readEnumName(Object o, String[] names) {
		for (String n : names) {
			Object v = invokeNoArg(o, n);
			if (v instanceof Enum<?> e) return e.name();
		}
		return null;
	}

	/** Replace path-unsafe characters in the root name so the synthetic
	 *  name parses as a single registry path segment cleanly. */
	private static String sanitize(String s) {
		if (s == null) return "unknown";
		StringBuilder sb = new StringBuilder(s.length());
		for (int i = 0; i < s.length(); i++) {
			char ch = s.charAt(i);
			if (ch == ':' || ch == '/' || ch == ' ') sb.append('_');
			else sb.append(ch);
		}
		return sb.toString();
	}
}
