package me.apika.apikaprobe.bench;

import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandle;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import me.apika.apikaprobe.RustBridge;

/**
 * Boundary-tax benchmark: the same three ops (no-arg call, 4 KB int sum,
 * 512 KB double fill) crossing into rust_mod once over JNI and once over
 * java.lang.foreign, measured inside the live game JVM.
 *
 * Scratchpad spike numbers (bare JVM, this machine): noop JNI 4.0 ns vs
 * FFM 5.3 ns; sum JNI 142 ns vs FFM 121 ns; fill JNI 64.7 us vs FFM
 * shared-segment 30.3 us. This class exists to check those under MC's
 * JIT and GC state via /ferrite ffm bench.
 */
public final class FfmBoundaryBench {

	private static final int SUM_LEN = 1024;
	private static final int FILL_LEN = 65536;

	private FfmBoundaryBench() {}

	public record Line(String label, double nsPerCall) {
		public String format() {
			return String.format("[ffm-bench] %-22s %10.1f ns/call", label, nsPerCall);
		}
	}

	public static List<Line> run() throws Throwable {
		String dll = RustBridge.nativePath();
		if (dll == null) {
			throw new IllegalStateException("rust_mod native not loaded");
		}

		Linker linker = Linker.nativeLinker();
		SymbolLookup lookup = SymbolLookup.libraryLookup(Path.of(dll), Arena.global());
		MethodHandle cNoop = linker.downcallHandle(
				lookup.find("ffm_noop").orElseThrow(),
				FunctionDescriptor.ofVoid());
		MethodHandle cSum = linker.downcallHandle(
				lookup.find("ffm_sum").orElseThrow(),
				FunctionDescriptor.of(ValueLayout.JAVA_LONG, ValueLayout.ADDRESS, ValueLayout.JAVA_LONG));
		MethodHandle cSumCrit = linker.downcallHandle(
				lookup.find("ffm_sum").orElseThrow(),
				FunctionDescriptor.of(ValueLayout.JAVA_LONG, ValueLayout.ADDRESS, ValueLayout.JAVA_LONG),
				Linker.Option.critical(true));
		MethodHandle cFill = linker.downcallHandle(
				lookup.find("ffm_fill").orElseThrow(),
				FunctionDescriptor.ofVoid(ValueLayout.ADDRESS, ValueLayout.JAVA_LONG, ValueLayout.JAVA_DOUBLE));

		int[] heapInts = new int[SUM_LEN];
		for (int i = 0; i < SUM_LEN; i++) {
			heapInts[i] = i;
		}
		double[] heapDoubles = new double[FILL_LEN];
		MemorySegment heapIntsSeg = MemorySegment.ofArray(heapInts);

		List<Line> out = new ArrayList<>();
		try (Arena arena = Arena.ofConfined()) {
			MemorySegment nativeInts = arena.allocate(ValueLayout.JAVA_INT, SUM_LEN);
			MemorySegment.copy(heapInts, 0, nativeInts, ValueLayout.JAVA_INT, 0, SUM_LEN);
			MemorySegment nativeDoubles = arena.allocate(ValueLayout.JAVA_DOUBLE, FILL_LEN);

			// warmup
			long sink = 0;
			for (int i = 0; i < 200_000; i++) {
				RustBridge.ffmBenchNoop();
				cNoop.invokeExact();
			}
			for (int i = 0; i < 20_000; i++) {
				sink += RustBridge.ffmBenchSum(heapInts);
				sink += (long) cSum.invokeExact(nativeInts, (long) SUM_LEN);
				sink += (long) cSumCrit.invokeExact(heapIntsSeg, (long) SUM_LEN);
			}
			for (int i = 0; i < 2_000; i++) {
				RustBridge.ffmBenchFill(heapDoubles, i);
				cFill.invokeExact(nativeDoubles, (long) FILL_LEN, (double) i);
			}

			out.add(bench("noop JNI", 5_000_000, () -> RustBridge.ffmBenchNoop()));
			out.add(bench("noop FFM", 5_000_000, () -> {
				try {
					cNoop.invokeExact();
				} catch (Throwable t) {
					throw new RuntimeException(t);
				}
			}));
			long[] sinkBox = {sink};
			out.add(bench("sum4K JNI critical", 200_000, () -> sinkBox[0] += RustBridge.ffmBenchSum(heapInts)));
			out.add(bench("sum4K FFM native-seg", 200_000, () -> {
				try {
					sinkBox[0] += (long) cSum.invokeExact(nativeInts, (long) SUM_LEN);
				} catch (Throwable t) {
					throw new RuntimeException(t);
				}
			}));
			out.add(bench("sum4K FFM heap-crit", 200_000, () -> {
				try {
					sinkBox[0] += (long) cSumCrit.invokeExact(heapIntsSeg, (long) SUM_LEN);
				} catch (Throwable t) {
					throw new RuntimeException(t);
				}
			}));
			out.add(bench("fill512K JNI region", 10_000, () -> RustBridge.ffmBenchFill(heapDoubles, 1.0)));
			out.add(bench("fill512K FFM nat-seg", 10_000, () -> {
				try {
					cFill.invokeExact(nativeDoubles, (long) FILL_LEN, 1.0);
				} catch (Throwable t) {
					throw new RuntimeException(t);
				}
			}));
			if (sinkBox[0] == 42) {
				System.out.println("(unreachable sink)");
			}
		}
		return out;
	}

	private static Line bench(String label, int iters, Runnable body) {
		long best = Long.MAX_VALUE;
		for (int rep = 0; rep < 5; rep++) {
			long t0 = System.nanoTime();
			for (int i = 0; i < iters; i++) {
				body.run();
			}
			best = Math.min(best, System.nanoTime() - t0);
		}
		return new Line(label, (double) best / iters);
	}
}
