package me.apika.apikaprobe;

import java.io.ByteArrayOutputStream;
import java.lang.reflect.Method;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;

/**
 * Walks a yarn {@code DensityFunction} object tree and emits our
 * Rust-side DF bytecode (see {@code rust/mod/src/density.rs::opcode}).
 *
 * <p>Yarn renames the mojmap classes under the {@code densityfunction}
 * package. Rather than hardcode yarn class names (which drift across
 * versions), we identify nodes by:
 * <ol>
 *   <li>Simple class name fragment matching ("Constant", "ShiftedNoise", etc.)</li>
 *   <li>For multi-kind classes (yarn's TwoArgumentSimpleFunction covers
 *       ADD/MUL/MIN/MAX via an enum field; Mapped covers ABS/SQUARE/etc.)
 *       — read the enum via reflection and dispatch on its name.</li>
 * </ol>
 *
 * <p>Unknown types are encoded as `CONSTANT(0.0)` with a logged
 * warning so chunkgen keeps running with a degraded value rather than
 * crashing. The log lines are the iteration driver: each unknown type
 * we see becomes a new walker case.
 *
 * <p>The walker is best-effort and one-shot (runs at world load).
 * Perf doesn't matter; correctness of emitted bytecode matters.
 */
public final class DensityFunctionWalker {

	private DensityFunctionWalker() {}

	/**
	 * Encode a yarn DensityFunction tree into a {@link ByteBuffer} of
	 * our Rust-side bytecode. Returns null if the tree couldn't be
	 * walked at all (root unknown, etc.); degraded nodes emit
	 * {@code CONSTANT(0)} stubs.
	 */
	public static ByteBuffer encode(Object root) {
		if (root == null) return null;
		ByteArrayOutputStream baos = new ByteArrayOutputStream(256);
		try {
			writeNode(baos, root);
		} catch (RuntimeException e) {
			ExampleMod.LOGGER.warn("[df-walker] encode root failed: {}", e.toString());
			return null;
		}
		byte[] out = baos.toByteArray();
		ByteBuffer buf = ByteBuffer.allocateDirect(out.length).order(ByteOrder.nativeOrder());
		buf.put(out);
		buf.flip();
		return buf;
	}

	// -- Rust-side opcode constants (keep in sync with density.rs::opcode) --
	private static final int OP_CONSTANT = 0x00;
	private static final int OP_ADD = 0x01;
	private static final int OP_MUL = 0x02;
	private static final int OP_MIN = 0x03;
	private static final int OP_MAX = 0x04;
	private static final int OP_ABS = 0x05;
	private static final int OP_SQUARE = 0x06;
	private static final int OP_CUBE = 0x07;
	private static final int OP_HALF_NEGATIVE = 0x08;
	private static final int OP_QUARTER_NEGATIVE = 0x09;
	private static final int OP_INVERT = 0x0A;
	private static final int OP_SQUEEZE = 0x0B;
	private static final int OP_CLAMP = 0x0C;
	private static final int OP_Y_CLAMPED_GRADIENT = 0x0D;
	private static final int OP_RANGE_CHOICE = 0x0E;
	private static final int OP_MARKER = 0x0F;
	private static final int OP_NOISE = 0x10;
	private static final int OP_SHIFT = 0x11;
	private static final int OP_SHIFT_A = 0x12;
	private static final int OP_SHIFT_B = 0x13;
	private static final int OP_SHIFTED_NOISE = 0x14;
	private static final int OP_WEIRD_SCALED_SAMPLER = 0x15;
	private static final int OP_SPLINE = 0x16;
	private static final int OP_SPLINE_CONSTANT = 0x00;
	private static final int OP_SPLINE_MULTIPOINT = 0x01;
	private static final int MARKER_INTERPOLATED = 0;
	private static final int MARKER_FLAT_CACHE = 1;
	private static final int MARKER_CACHE_2D = 2;
	private static final int MARKER_CACHE_ONCE = 3;
	private static final int MARKER_CACHE_ALL_IN_CELL = 4;
	private static final int RARITY_TYPE1 = 0;
	private static final int RARITY_TYPE2 = 1;

	private static final java.util.concurrent.ConcurrentHashMap<String, String> typeLog =
			new java.util.concurrent.ConcurrentHashMap<>();

	/** Log each (FQN → routed-opcode-name) pair once per process. */
	private static void logRouting(Object node, String routedTo) {
		String fqn = node.getClass().getName();
		if (typeLog.putIfAbsent(fqn, routedTo) == null) {
			ExampleMod.LOGGER.info("[df-walker] {} -> {}", fqn, routedTo);
		}
	}

	/** Dispatch a DF node by yarn class simple name. Unknown → CONSTANT(0) stub. */
	private static void writeNode(ByteArrayOutputStream out, Object node) {
		if (node == null) {
			writeUnknown(out, "null node");
			return;
		}
		String cls = node.getClass().getSimpleName();
		// Record every distinct FQN we encounter, with its simple name.
		// Lets us see at a glance which yarn types our walker has actually
		// seen vs missed.
		logRouting(node, cls);
		// Yarn records may use different names than mojmap; we match on
		// SUBSTRINGS so the walker catches e.g. "Constant", "ConstantDF",
		// etc. Order matters — more specific patterns first.
		if (cls.equals("HolderHolder") || cls.endsWith("$HolderHolder")
				|| cls.equals("RegistryEntryHolder") || cls.endsWith("$RegistryEntryHolder")) {
			Object unwrapped = unwrapHolder(node);
			if (unwrapped != null) {
				writeNode(out, unwrapped);
				return;
			}
			logUnknownOnce(node.getClass().getName() + " (unwrap failed)");
			writeUnknown(out, cls);
			return;
		}
		// Pattern order: MORE SPECIFIC patterns first, since `contains` is
		// substring matching. YClampedGradient contains "Clamp", so it
		// MUST be tested before Clamp. ShiftedNoise contains "Noise", so
		// it MUST be tested before Noise. Etc.

		// Blender variants have well-known unblended-chunk constant
		// behavior. We emit those constants instead of stubs so DFs
		// wrapped in splineWithBlending (= lerp(blendAlpha, target, spline))
		// reduce to just `spline` in unblended chunks (= almost all chunks).
		if (cls.contains("BlendAlpha")) {
			// Vanilla returns 1.0 unconditionally.
			out.write(OP_CONSTANT); writeDouble(out, 1.0); return;
		}
		if (cls.contains("BlendOffset")) {
			// Vanilla returns 0.0 unconditionally.
			out.write(OP_CONSTANT); writeDouble(out, 0.0); return;
		}
		if (cls.contains("BlendDensity")) {
			// In unblended chunks (Blender.empty), this is an identity
			// passthrough. Emit MARKER wrapping the inner input — same
			// effect as identity, plus future cache wiring.
			out.write(OP_MARKER); out.write(MARKER_INTERPOLATED);
			Object inner = invokeAny(node, new String[]{"input", "wrapped"});
			writeNode(out, inner);
			return;
		}

		if (cls.contains("YClampedGradient") || cls.contains("ClampedYGradient") || cls.contains("YGradient")) {
			encodeYClampedGradient(out, node); return;
		}
		if (cls.contains("ShiftedNoise")) { encodeShiftedNoise(out, node); return; }
		if (cls.equals("ShiftA") || cls.endsWith("$ShiftA")) { encodeShiftKind(out, node, OP_SHIFT_A); return; }
		if (cls.equals("ShiftB") || cls.endsWith("$ShiftB")) { encodeShiftKind(out, node, OP_SHIFT_B); return; }
		if (cls.equals("Shift") || cls.endsWith("$Shift")) { encodeShiftKind(out, node, OP_SHIFT); return; }
		if (cls.contains("WeirdScaledSampler") || cls.contains("WeirdScaled")) {
			encodeWeirdScaledSampler(out, node); return;
		}
		if (cls.contains("RangeChoice") || cls.contains("RangeSelector")) {
			encodeRangeChoice(out, node); return;
		}
		if (cls.contains("Constant")) { encodeConstant(out, node); return; }
		if (cls.contains("Noise")) { encodeNoise(out, node); return; }
		if (cls.contains("Clamp")) { encodeClamp(out, node); return; }
		if (cls.contains("Spline")) { encodeSpline(out, node); return; }
		if (cls.contains("Marker") || cls.contains("Wrapping")) { encodeMarker(out, node); return; }
		if (cls.contains("TwoArgument") || cls.contains("BinaryOperation")) {
			encodeBinary(out, node); return;
		}
		if (cls.contains("Mapped") || cls.contains("Unary") || cls.contains("UnaryOperation")) {
			encodeMapped(out, node); return;
		}

		// Unknown — log once per new class and emit stub.
		logUnknownOnce(node.getClass().getName());
		writeUnknown(out, cls);
	}

	private static final java.util.concurrent.ConcurrentHashMap<String, Boolean> seenUnknown =
			new java.util.concurrent.ConcurrentHashMap<>();
	private static void logUnknownOnce(String fqn) {
		if (seenUnknown.putIfAbsent(fqn, Boolean.TRUE) == null) {
			ExampleMod.LOGGER.warn("[df-walker] unknown DF type: {} — emitting CONSTANT(0) stub", fqn);
		}
	}

	private static void writeUnknown(ByteArrayOutputStream out, String cls) {
		out.write(OP_CONSTANT);
		writeDouble(out, 0.0);
	}

	private static void encodeConstant(ByteArrayOutputStream out, Object node) {
		out.write(OP_CONSTANT);
		Double v = readDouble(node, "value");
		writeDouble(out, v == null ? 0.0 : v);
	}

	/**
	 * Yarn `TwoArgumentSimpleFunction` / mojmap `TwoArgumentSimpleFunction`
	 * covers ADD / MUL / MIN / MAX via an enum field. Read the enum's
	 * name() and dispatch.
	 */
	private static void encodeBinary(ByteArrayOutputStream out, Object node) {
		String typeName = readEnumName(node, new String[]{"type", "specificType", "operation"});
		int opcode = switch (typeName == null ? "" : typeName) {
			case "ADD", "Add" -> OP_ADD;
			case "MUL", "Mul" -> OP_MUL;
			case "MIN", "Min" -> OP_MIN;
			case "MAX", "Max" -> OP_MAX;
			default -> -1;
		};
		if (opcode < 0) {
			ExampleMod.LOGGER.warn("[df-walker] unknown binary op: {} on {}", typeName, node.getClass().getName());
			writeUnknown(out, "binary"); return;
		}
		out.write(opcode);
		Object a1 = invokeAny(node, new String[]{"argument1", "input1", "first"});
		Object a2 = invokeAny(node, new String[]{"argument2", "input2", "second"});
		writeNode(out, a1);
		writeNode(out, a2);
	}

	private static void encodeMapped(ByteArrayOutputStream out, Object node) {
		String typeName = readEnumName(node, new String[]{"type"});
		int opcode = switch (typeName == null ? "" : typeName) {
			case "ABS", "Abs" -> OP_ABS;
			case "SQUARE", "Square" -> OP_SQUARE;
			case "CUBE", "Cube" -> OP_CUBE;
			case "HALF_NEGATIVE", "HalfNegative" -> OP_HALF_NEGATIVE;
			case "QUARTER_NEGATIVE", "QuarterNegative" -> OP_QUARTER_NEGATIVE;
			case "INVERT", "Invert" -> OP_INVERT;
			case "SQUEEZE", "Squeeze" -> OP_SQUEEZE;
			default -> -1;
		};
		if (opcode < 0) {
			ExampleMod.LOGGER.warn("[df-walker] unknown mapped op: {} on {}", typeName, node.getClass().getName());
			writeUnknown(out, "mapped"); return;
		}
		out.write(opcode);
		Object inner = invokeAny(node, new String[]{"input", "wrapped"});
		writeNode(out, inner);
	}

	private static void encodeMarker(ByteArrayOutputStream out, Object node) {
		String typeName = readEnumName(node, new String[]{"type"});
		int kind = switch (typeName == null ? "" : typeName) {
			case "Interpolated", "INTERPOLATED" -> MARKER_INTERPOLATED;
			case "FlatCache", "FLAT_CACHE" -> MARKER_FLAT_CACHE;
			case "Cache2D", "CACHE_2D" -> MARKER_CACHE_2D;
			case "CacheOnce", "CACHE_ONCE" -> MARKER_CACHE_ONCE;
			case "CacheAllInCell", "CACHE_ALL_IN_CELL" -> MARKER_CACHE_ALL_IN_CELL;
			default -> -1;
		};
		if (kind < 0) {
			// Unknown kind → just passthrough as Interpolated (behavior identical).
			kind = MARKER_INTERPOLATED;
		}
		out.write(OP_MARKER);
		out.write(kind);
		Object inner = invokeAny(node, new String[]{"wrapped", "input"});
		writeNode(out, inner);
	}

	private static void encodeClamp(ByteArrayOutputStream out, Object node) {
		out.write(OP_CLAMP);
		Object inner = invokeAny(node, new String[]{"input"});
		writeNode(out, inner);
		Double min = readDoubleAny(node, new String[]{"minValue", "min"});
		Double max = readDoubleAny(node, new String[]{"maxValue", "max"});
		writeDouble(out, min == null ? 0.0 : min);
		writeDouble(out, max == null ? 0.0 : max);
	}

	private static final java.util.concurrent.ConcurrentHashMap<String, Boolean> seenYcg =
			new java.util.concurrent.ConcurrentHashMap<>();
	private static void encodeYClampedGradient(ByteArrayOutputStream out, Object node) {
		out.write(OP_Y_CLAMPED_GRADIENT);
		Integer fromY = readIntAny(node, new String[]{"fromY", "from_y"});
		Integer toY = readIntAny(node, new String[]{"toY", "to_y"});
		Double fromV = readDoubleAny(node, new String[]{"fromValue", "from_value"});
		Double toV = readDoubleAny(node, new String[]{"toValue", "to_value"});
		// Diagnostic: log every distinct encoded tuple once. If we see
		// (0, 0, 0, 0) it means yarn renamed the accessors and we need
		// to extend the candidate name list.
		String key = fromY + "/" + toY + "/" + fromV + "/" + toV;
		if (seenYcg.putIfAbsent(key, Boolean.TRUE) == null) {
			ExampleMod.LOGGER.info("[df-walker] YClampedGradient: fromY={} toY={} fromValue={} toValue={}",
					fromY, toY, fromV, toV);
		}
		writeInt(out, fromY == null ? 0 : fromY);
		writeInt(out, toY == null ? 0 : toY);
		writeDouble(out, fromV == null ? 0.0 : fromV);
		writeDouble(out, toV == null ? 0.0 : toV);
	}

	private static void encodeRangeChoice(ByteArrayOutputStream out, Object node) {
		out.write(OP_RANGE_CHOICE);
		writeNode(out, invokeAny(node, new String[]{"input"}));
		Double minInc = readDoubleAny(node, new String[]{"minInclusive"});
		Double maxExc = readDoubleAny(node, new String[]{"maxExclusive"});
		writeDouble(out, minInc == null ? 0.0 : minInc);
		writeDouble(out, maxExc == null ? 0.0 : maxExc);
		writeNode(out, invokeAny(node, new String[]{"whenInRange"}));
		writeNode(out, invokeAny(node, new String[]{"whenOutOfRange"}));
	}

	private static void encodeNoise(ByteArrayOutputStream out, Object node) {
		out.write(OP_NOISE);
		String name = resolveNoiseName(invokeAny(node, new String[]{"noise"}));
		writeString(out, name);
		Double xzScale = readDoubleAny(node, new String[]{"xzScale"});
		Double yScale = readDoubleAny(node, new String[]{"yScale"});
		writeDouble(out, xzScale == null ? 1.0 : xzScale);
		writeDouble(out, yScale == null ? 1.0 : yScale);
	}

	private static void encodeShiftKind(ByteArrayOutputStream out, Object node, int opcode) {
		out.write(opcode);
		String name = resolveNoiseName(invokeAny(node, new String[]{"offsetNoise", "noise"}));
		writeString(out, name);
	}

	private static void encodeShiftedNoise(ByteArrayOutputStream out, Object node) {
		out.write(OP_SHIFTED_NOISE);
		writeNode(out, invokeAny(node, new String[]{"shiftX"}));
		writeNode(out, invokeAny(node, new String[]{"shiftY"}));
		writeNode(out, invokeAny(node, new String[]{"shiftZ"}));
		Double xzScale = readDoubleAny(node, new String[]{"xzScale"});
		Double yScale = readDoubleAny(node, new String[]{"yScale"});
		writeDouble(out, xzScale == null ? 1.0 : xzScale);
		writeDouble(out, yScale == null ? 1.0 : yScale);
		String name = resolveNoiseName(invokeAny(node, new String[]{"noise"}));
		writeString(out, name);
	}

	private static void encodeWeirdScaledSampler(ByteArrayOutputStream out, Object node) {
		out.write(OP_WEIRD_SCALED_SAMPLER);
		writeNode(out, invokeAny(node, new String[]{"input"}));
		String name = resolveNoiseName(invokeAny(node, new String[]{"noise"}));
		writeString(out, name);
		String rType = readEnumName(node, new String[]{"rarityValueMapper"});
		int rCode = switch (rType == null ? "" : rType) {
			case "TYPE1", "Type1", "type_1" -> RARITY_TYPE1;
			case "TYPE2", "Type2", "type_2" -> RARITY_TYPE2;
			default -> {
				ExampleMod.LOGGER.warn("[df-walker] unknown rarity type: {}", rType);
				yield RARITY_TYPE1;
			}
		};
		out.write(rCode);
	}

	private static void encodeSpline(ByteArrayOutputStream out, Object node) {
		out.write(OP_SPLINE);
		Object spline = invokeAny(node, new String[]{"spline"});
		writeSpline(out, spline);
	}

	/** Recursively encode a yarn CubicSpline (either Constant or Multipoint). */
	private static void writeSpline(ByteArrayOutputStream out, Object spline) {
		if (spline == null) {
			out.write(OP_SPLINE_CONSTANT);
			writeFloat(out, 0.0f);
			return;
		}
		String cls = spline.getClass().getSimpleName();
		// Mojmap `CubicSpline.Constant` → yarn `Spline.FixedFloatFunction`.
		if (cls.contains("Constant") || cls.contains("FixedFloatFunction")) {
			out.write(OP_SPLINE_CONSTANT);
			Float v = readFloatAny(spline, new String[]{"value"});
			writeFloat(out, v == null ? 0.0f : v);
			return;
		}
		// Mojmap `CubicSpline.Multipoint` → yarn `Spline.Implementation`.
		if (cls.contains("Multipoint") || cls.contains("MultiPoint") || cls.contains("Implementation")) {
			out.write(OP_SPLINE_MULTIPOINT);
			// Coordinate accessor: mojmap CubicSpline.Multipoint exposes
			// `coordinate()`; yarn 1.21.11 renamed it to `locationFunction()`
			// (returns a ToFloatFunction implementation, in our case a
			// `DensityFunctionTypes$Spline$DensityFunctionWrapper` wrapping
			// a `RegistryEntry<DensityFunction>`). bruteFindCoord is a
			// last-resort field walk in case of further yarn drift.
			Object coord = invokeAny(spline, new String[]{
					"coordinate", "locationFunction",
			});
			if (coord == null) {
				coord = bruteFindCoord(spline);
			}
			Object coordFn = extractSplineCoordFn(coord);
			writeNode(out, coordFn);
			// Arrays.
			float[] locations = readFloatArray(spline, "locations");
			float[] derivatives = readFloatArray(spline, "derivatives");
			Object valuesObj = invokeAny(spline, new String[]{"values"});
			java.util.List<?> values = valuesObj instanceof java.util.List<?> list ? list : java.util.List.of();
			int n = Math.min(locations == null ? 0 : locations.length, values.size());
			writeShort(out, n);
			for (int i = 0; i < n; i++) writeFloat(out, locations[i]);
			for (int i = 0; i < n; i++) writeFloat(out, derivatives == null ? 0.0f : derivatives[i]);
			for (int i = 0; i < n; i++) writeSpline(out, values.get(i));
			return;
		}
		logUnknownOnce(spline.getClass().getName() + " (spline)");
		out.write(OP_SPLINE_CONSTANT);
		writeFloat(out, 0.0f);
	}

	/**
	 * Brute-force locator for the Coordinate object on a yarn Spline.Implementation
	 * when none of the known accessor names work. Scans no-arg methods + fields
	 * for something whose class looks like a Coordinate (has a Holder-typed field).
	 */
	private static Object bruteFindCoord(Object spline) {
		Class<?> c = spline.getClass();
		for (Method m : c.getDeclaredMethods()) {
			if (m.getParameterCount() != 0) continue;
			try {
				m.setAccessible(true);
				Object v = m.invoke(spline);
				if (v == null) continue;
				String tn = v.getClass().getSimpleName();
				if (tn.contains("Coordinate") || tn.contains("coord")) return v;
			} catch (ReflectiveOperationException ignored) {
				// next
			}
		}
		for (java.lang.reflect.Field f : c.getDeclaredFields()) {
			try {
				f.setAccessible(true);
				Object v = f.get(spline);
				if (v == null) continue;
				String tn = v.getClass().getSimpleName();
				if (tn.contains("Coordinate") || tn.contains("coord")) return v;
			} catch (ReflectiveOperationException ignored) {
				// next
			}
		}
		return null;
	}

	/**
	 * Extract the underlying DensityFunction from a `Spline.Coordinate`
	 * record. Mojmap accessor is `function() → Holder<DensityFunction>`.
	 * Yarn renames it; we try common names then brute-force walk fields.
	 */
	private static final java.util.concurrent.ConcurrentHashMap<String, Boolean> dumpedCoordClass =
			new java.util.concurrent.ConcurrentHashMap<>();

	private static Object extractSplineCoordFn(Object coord) {
		if (coord == null) return null;
		// One-shot dump per class: list its no-arg methods so we can see
		// what yarn renamed the accessor to.
		String coordClsName = coord.getClass().getName();
		if (dumpedCoordClass.putIfAbsent(coordClsName, Boolean.TRUE) == null) {
			StringBuilder sb = new StringBuilder("[df-walker] Spline.Coordinate methods on ")
					.append(coordClsName).append(":");
			for (Method m : coord.getClass().getDeclaredMethods()) {
				if (m.getParameterCount() == 0) {
					sb.append(' ').append(m.getName())
							.append("():").append(m.getReturnType().getSimpleName());
				}
			}
			ExampleMod.LOGGER.info(sb.toString());
			// Also list fields.
			StringBuilder sf = new StringBuilder("[df-walker] Spline.Coordinate fields:");
			for (java.lang.reflect.Field f : coord.getClass().getDeclaredFields()) {
				sf.append(' ').append(f.getName()).append(':')
						.append(f.getType().getSimpleName());
			}
			ExampleMod.LOGGER.info(sf.toString());
		}
		// Try known accessor names (mojmap + likely yarn variants).
		Object holder = invokeAny(coord, new String[]{
			"function", "densityFunction", "densityFunctionWrapper",
			"holder", "delegate", "df",
		});
		if (holder == null) {
			// Brute force: walk no-arg accessors looking for a Holder.
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
					// Some yarn versions inline the DF directly without a Holder.
					if (tn.contains("DensityFunction") || tn.contains("Wrapping")
							|| tn.contains("BinaryOperation") || tn.contains("Noise")) {
						return v; // already a DF, no holder unwrap needed
					}
				} catch (ReflectiveOperationException ignored) {
					// next
				}
			}
		}
		if (holder == null) return null;
		// Now unwrap the Holder via .value() (or yarn-renamed variants).
		Object value = invokeAny(holder, new String[]{"value", "comp_349", "get"});
		if (value != null) return value;
		// Holder might directly be a DF (rare path).
		String tn = holder.getClass().getSimpleName();
		if (tn.contains("DensityFunction") || tn.contains("Wrapping")
				|| tn.contains("BinaryOperation") || tn.contains("Noise")) {
			return holder;
		}
		return null;
	}

	/**
	 * Unwrap a yarn RegistryEntryHolder / mojmap HolderHolder to its
	 * underlying DF. Tries several accessor paths because yarn shifts
	 * names per version:
	 *   node.function().value()
	 *   node.entry().value()
	 *   node.holder().value()
	 *   node.function().comp_349()     (intermediate-mappings style)
	 * Plus a brute-force field walk as a last resort.
	 */
	private static Object unwrapHolder(Object node) {
		// First: try named accessors.
		String[] holderAccessors = {"function", "entry", "holder", "delegate"};
		for (String acc : holderAccessors) {
			Object inner = invokeNoArg(node, acc);
			if (inner == null) continue;
			Object value = invokeAny(inner, new String[]{"value", "comp_349"});
			if (value != null) return value;
			// Some holders resolve via getValue() (Holder's get method).
			Object get = invokeNoArg(inner, "get");
			if (get != null) return get;
		}
		// Last resort: walk fields, find any DensityFunction-like thing.
		Class<?> c = node.getClass();
		while (c != null && c != Object.class) {
			for (java.lang.reflect.Field f : c.getDeclaredFields()) {
				try {
					f.setAccessible(true);
					Object v = f.get(node);
					if (v == null) continue;
					// If it looks like a DF (not a primitive, not a String, not a collection), try walking.
					String typeName = v.getClass().getSimpleName();
					if (typeName.equals("String") || typeName.equals("HashMap") || typeName.equals("ArrayList")) continue;
					// If it's a Holder, unwrap further.
					Object value = invokeNoArg(v, "value");
					if (value != null) return value;
					// Otherwise, maybe it IS the DF.
					if (typeName.contains("DensityFunction") || typeName.contains("Noise") || typeName.contains("Spline")) {
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

	// -------- Yarn NoiseHolder → full identifier resolution --------

	/**
	 * A yarn NoiseHolder wraps `Holder<NoiseParameters>` + resolved
	 * `NormalNoise`. We need the identifier string. Try
	 * `noiseData().getKey().get().getValue().toString()`.
	 */
	private static String resolveNoiseName(Object holder) {
		if (holder == null) return "";
		Object data = invokeNoArg(holder, "noiseData");
		if (data == null) return "";
		try {
			Method getKey = data.getClass().getMethod("getKey");
			Object opt = getKey.invoke(data);
			if (opt instanceof java.util.Optional<?> o && o.isPresent()) {
				Object regKey = o.get();
				Method getValue = regKey.getClass().getMethod("getValue");
				Object id = getValue.invoke(regKey);
				if (id != null) return id.toString();
			}
		} catch (ReflectiveOperationException ignored) {
			// fall through
		}
		return "";
	}

	// -------- Reflection helpers --------

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

	private static Double readDouble(Object o, String name) {
		Object v = invokeNoArg(o, name);
		return v instanceof Double d ? d : null;
	}

	private static Double readDoubleAny(Object o, String[] names) {
		for (String n : names) {
			Object v = invokeNoArg(o, n);
			if (v instanceof Double d) return d;
		}
		return null;
	}

	private static Integer readIntAny(Object o, String[] names) {
		for (String n : names) {
			Object v = invokeNoArg(o, n);
			if (v instanceof Integer i) return i;
		}
		return null;
	}

	private static Float readFloatAny(Object o, String[] names) {
		for (String n : names) {
			Object v = invokeNoArg(o, n);
			if (v instanceof Float f) return f;
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

	private static float[] readFloatArray(Object o, String name) {
		Object v = invokeNoArg(o, name);
		return v instanceof float[] arr ? arr : null;
	}

	// -------- Writers --------

	private static void writeInt(ByteArrayOutputStream out, int v) {
		ByteBuffer b = ByteBuffer.allocate(4).order(ByteOrder.nativeOrder()).putInt(v);
		out.write(b.array(), 0, 4);
	}

	private static void writeFloat(ByteArrayOutputStream out, float v) {
		ByteBuffer b = ByteBuffer.allocate(4).order(ByteOrder.nativeOrder()).putFloat(v);
		out.write(b.array(), 0, 4);
	}

	private static void writeDouble(ByteArrayOutputStream out, double v) {
		ByteBuffer b = ByteBuffer.allocate(8).order(ByteOrder.nativeOrder()).putDouble(v);
		out.write(b.array(), 0, 8);
	}

	private static void writeShort(ByteArrayOutputStream out, int v) {
		ByteBuffer b = ByteBuffer.allocate(2).order(ByteOrder.nativeOrder()).putShort((short) v);
		out.write(b.array(), 0, 2);
	}

	private static void writeString(ByteArrayOutputStream out, String s) {
		byte[] bytes = s == null ? new byte[0] : s.getBytes(StandardCharsets.UTF_8);
		writeShort(out, bytes.length);
		out.write(bytes, 0, bytes.length);
	}
}
