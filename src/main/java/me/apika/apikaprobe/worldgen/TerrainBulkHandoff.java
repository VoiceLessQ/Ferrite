package me.apika.apikaprobe.worldgen;

import me.apika.apikaprobe.bridge.ExampleMod;
import me.apika.apikaprobe.RustBridge;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.concurrent.atomic.AtomicLong;

import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import net.minecraft.world.chunk.Chunk;
import net.minecraft.world.gen.densityfunction.DensityFunction;
import net.minecraft.world.gen.noise.NoiseConfig;

/**
 * v1 bulk-terrain handoff — "run alongside" mode.
 *
 * Exports cell-corner finalDensity values to Rust, has Rust compute
 * block IDs for the entire chunk (simplified aquifer: density→block),
 * times the operation, and THROWS AWAY the result. This is the A/B
 * baseline: we want a clean speed number vs vanilla's sampleBlockState
 * loop without affecting terrain.
 *
 * Vanilla overworld cell dimensions assumed (cellWidth=4, cellHeight=8,
 * minY=-64, seaLevel=62). For v2, read from NoiseSettings.
 *
 * Buffer layout:
 *   cornerDensities: float[5 × 49 × 5] = 1225 floats = 4900 bytes
 *     index(cx, cy, cz) = cy * 25 + cz * 5 + cx
 *   blockIds output:  short[16 × 384 × 16] = 98304 shorts = 196608 bytes
 *     index(x, y, z)   = (x * 16 + z) * 384 + (y - MIN_Y)
 *
 * Thread-safety: ByteBuffers allocated fresh per call (chunk gen is on
 * worker pool, no shared state). Monitor counters are AtomicLong.
 */
public final class TerrainBulkHandoff {
	private static final Logger LOGGER = LoggerFactory.getLogger("ferrite");

	// Overworld defaults — v2 reads these from NoiseSettings.
	private static final int CHUNK_X = 16;
	private static final int CHUNK_Z = 16;
	private static final int MIN_Y = -64;
	private static final int MAX_Y = 320;
	private static final int SEA_LEVEL = 62;
	private static final int CELL_WIDTH = 4;
	private static final int CELL_HEIGHT = 8;

	private static final int CORNERS_X = CHUNK_X / CELL_WIDTH + 1;                 // 5
	private static final int CORNERS_Y = (MAX_Y - MIN_Y) / CELL_HEIGHT + 1;        // 49
	private static final int CORNERS_Z = CHUNK_Z / CELL_WIDTH + 1;                 // 5
	private static final int CORNER_COUNT = CORNERS_X * CORNERS_Y * CORNERS_Z;     // 1225
	private static final int CORNER_BYTES = CORNER_COUNT * 4;                      // 4900

	private static final int BLOCKS_PER_CHUNK = CHUNK_X * CHUNK_Z * (MAX_Y - MIN_Y); // 98304
	private static final int BLOCK_ID_BYTES = BLOCKS_PER_CHUNK * 2;                   // 196608

	// Aggregate counters — flushed every 5 seconds by the registered reporter.
	private static final AtomicLong CALL_COUNT = new AtomicLong();
	private static final AtomicLong SAMPLE_TOTAL_NS = new AtomicLong();
	private static final AtomicLong RUST_TOTAL_NS = new AtomicLong();
	private static final AtomicLong TOTAL_NS = new AtomicLong();
	private static final AtomicLong MAX_TOTAL_NS = new AtomicLong();

	private static final long REPORT_INTERVAL_NS = 5_000_000_000L;
	private static volatile long lastReportNs = System.nanoTime();

	private TerrainBulkHandoff() {}

	public static void register() {
		ServerTickEvents.END_SERVER_TICK.register(server -> maybeReport());
	}

	/**
	 * Compute chunk terrain via Rust bulk handoff. Result is discarded —
	 * this is the A/B timing run. Safe to call from chunk-gen workers.
	 */
	public static void apply(Chunk chunk, NoiseConfig noiseConfig) {
		if (!RustBridge.NATIVE_AVAILABLE) {
			return;
		}

		long tStart = System.nanoTime();

		DensityFunction finalDensity = noiseConfig.getNoiseRouter().finalDensity();

		ByteBuffer cornerBuf = ByteBuffer.allocateDirect(CORNER_BYTES).order(ByteOrder.nativeOrder());
		ByteBuffer outBuf = ByteBuffer.allocateDirect(BLOCK_ID_BYTES).order(ByteOrder.nativeOrder());

		final int startX = chunk.getPos().getStartX();
		final int startZ = chunk.getPos().getStartZ();

		// Batched fill via DensityFunction.fill(double[], EachApplier).
		// Concrete density function implementations can override fill to
		// batch-process more efficiently than 1225 individual sample() calls.
		// Applier.at(i) maps flat-array index to cell-corner world position.
		double[] cornerValues = new double[CORNER_COUNT];
		DensityFunction.EachApplier applier = new DensityFunction.EachApplier() {
			@Override
			public DensityFunction.NoisePos at(int index) {
				int cy = index / (CORNERS_X * CORNERS_Z);
				int cz = (index / CORNERS_X) % CORNERS_Z;
				int cx = index % CORNERS_X;
				return new DensityFunction.UnblendedNoisePos(
						startX + cx * CELL_WIDTH,
						MIN_Y + cy * CELL_HEIGHT,
						startZ + cz * CELL_WIDTH);
			}

			@Override
			public void fill(double[] densities, DensityFunction function) {
				for (int i = 0; i < densities.length; i++) {
					densities[i] = function.sample(at(i));
				}
			}
		};

		long tSampleStart = System.nanoTime();
		finalDensity.fill(cornerValues, applier);
		long sampleNs = System.nanoTime() - tSampleStart;

		for (int i = 0; i < CORNER_COUNT; i++) {
			cornerBuf.putFloat(i * 4, (float) cornerValues[i]);
		}

		// Hand off to Rust: interpolates + decides + fills output buffer.
		long tRustStart = System.nanoTime();
		RustBridge.computeChunkTerrain(
				cornerBuf,
				outBuf,
				CELL_WIDTH,
				CELL_HEIGHT,
				MIN_Y,
				SEA_LEVEL,
				chunk.getPos().x,
				chunk.getPos().z);
		long rustNs = System.nanoTime() - tRustStart;

		// Intentionally: do NOT walk outBuf and apply to chunk. This is A/B mode.
		// outBuf gets GC'd. cornerBuf too.

		long totalNs = System.nanoTime() - tStart;

		CALL_COUNT.incrementAndGet();
		SAMPLE_TOTAL_NS.addAndGet(sampleNs);
		RUST_TOTAL_NS.addAndGet(rustNs);
		TOTAL_NS.addAndGet(totalNs);
		MAX_TOTAL_NS.updateAndGet(prev -> Math.max(prev, totalNs));
	}

	private static void maybeReport() {
		long now = System.nanoTime();
		if (now - lastReportNs < REPORT_INTERVAL_NS) {
			return;
		}

		long count = CALL_COUNT.getAndSet(0L);
		long sampleTotal = SAMPLE_TOTAL_NS.getAndSet(0L);
		long rustTotal = RUST_TOTAL_NS.getAndSet(0L);
		long total = TOTAL_NS.getAndSet(0L);
		long maxTotal = MAX_TOTAL_NS.getAndSet(0L);

		lastReportNs = now;

		if (count == 0L) {
			return;
		}

		double sampleAvg = sampleTotal / (double) count / 1_000_000.0;
		double rustAvg = rustTotal / (double) count / 1_000_000.0;
		double totalAvg = total / (double) count / 1_000_000.0;
		double maxMs = maxTotal / 1_000_000.0;

		LOGGER.info("[bulk-terrain] n={}  sample={} ms  rust={} ms  total={} ms/chunk  max={} ms",
				count,
				String.format("%.2f", sampleAvg),
				String.format("%.2f", rustAvg),
				String.format("%.2f", totalAvg),
				String.format("%.2f", maxMs));
	}
}
