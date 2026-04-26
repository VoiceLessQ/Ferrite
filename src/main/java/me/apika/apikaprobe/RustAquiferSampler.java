package me.apika.apikaprobe;

import java.lang.ref.Cleaner;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.IntBuffer;

import org.jspecify.annotations.Nullable;

import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.world.gen.chunk.AquiferSampler;
import net.minecraft.world.gen.densityfunction.DensityFunction;

/**
 * Java wrapper around the Rust aquifer port. Implements vanilla's
 * {@link AquiferSampler} interface so it drops directly into
 * {@code ChunkNoiseSampler}'s pipeline.
 *
 * <p>Lifecycle:
 * <ul>
 *   <li>Construct: caller pre-computes a surface-height grid (~32
 *       i32s) by calling vanilla's {@code estimateSurfaceHeight} at
 *       grid points; passes it to {@code RustBridge.initAquifer}
 *       which returns an opaque handle.</li>
 *   <li>Apply: per-block {@code applyAquifer} JNI call returns a
 *       packed result + needs-fluid-tick bit.</li>
 *   <li>Free: a {@link Cleaner} registers
 *       {@code RustBridge.freeAquifer} to run when this wrapper
 *       becomes phantom-reachable (i.e. its owning ChunkNoiseSampler
 *       is GC'd).</li>
 * </ul>
 *
 * <p>If {@code initAquifer} returns 0 (worldgen state not finalized,
 * grid invalid, etc.), the wrapper holds a {@code vanillaFallback}
 * and forwards every call to it. The Java caller never has to
 * differentiate.
 */
public final class RustAquiferSampler implements AquiferSampler {

    private static final Cleaner CLEANER = Cleaner.create();

    /** Surface-height grid stride. Vanilla's
     *  {@code chunkNoiseSampler.estimateSurfaceHeight(x, z)} is called
     *  by aquifer at non-grid-aligned (x, z); we pre-compute a sparse
     *  grid and Rust does nearest-cell lookup.
     *
     *  <p>Empirical: stride 16 → 0.105% block mismatch; stride 8 →
     *  0.275% block mismatch (both with correct coverage). Finer grid
     *  hurts here, not helps — the aquifer algorithm is sensitive to
     *  specific surface values rather than their precision. Stride 16
     *  is the empirical sweet spot. See {@code docs/AQUIFER_PORT.md}.  */
    private static final int GRID_STRIDE_BLOCKS = 16;

    /** Grid size — must cover the full queryable extent vanilla calls
     *  {@code estimateSurfaceHeight} on inside aquifer.
     *
     *  <p>Queryable X range: cells extend 1 chunk past the chunk's
     *  block extents (start_x = (chunkMin - 5) >> 4 ≈ chunkMin/16 - 1),
     *  giving cell block_x in [chunkMin - 16, chunkMin + 25]. With
     *  CHUNK_POS_OFFSETS x ∈ [-3, 1] adding offset[0] * 16:
     *  - min: (chunkMin - 16) + (-3)*16 = chunkMin - 64
     *  - max: (chunkMin + 25) + 1*16 = chunkMin + 41
     *
     *  <p>Queryable Z range similarly: [chunkMin_z - 32, chunkMin_z + 41].
     *
     *  <p>EMPIRICAL: parity is best at side=8/4, padding=48/16 (the
     *  original undersized-but-wrong-fallback configuration), at
     *  0.105% block mismatch. "Correct coverage" (side=8/6,
     *  padding=64/32) raises mismatch to 0.541%, and stride=8 with
     *  correct coverage to 0.275%. The grid approach is fundamentally
     *  lossy (nearest-neighbor) and the aquifer algorithm is sensitive
     *  to exact per-column surface estimates. The undersized 8×4
     *  config "happens to work better" because edge queries fall back
     *  to the exact rect-max scalar, which matches vanilla's behavior
     *  for specific decision points more often than the lossy grid
     *  lookup does.
     *
     *  <p>Real fix is per-column surface estimates, not a denser grid.
     *  See {@code docs/AQUIFER_PORT.md} for the next-pass plan. */
    private static final int GRID_SIDE_X = 8;
    private static final int GRID_SIDE_Z = 4;

    /** Padding (in BLOCKS, not stride units) on the negative side of
     *  the chunk. Independent of {@link #GRID_STRIDE_BLOCKS}. */
    private static final int GRID_PADDING_X_BLOCKS = 48;
    private static final int GRID_PADDING_Z_BLOCKS = 16;

    private final long handle;
    /** Used when {@link #handle} is 0 — the Rust path failed to
     *  initialize and we must defer to vanilla. */
    private final @Nullable AquiferSampler vanillaFallback;
    /** Cached side-effect bit, vanilla's {@code needsFluidTick}.
     *  Updated by every {@link #apply} call. */
    private boolean lastNeedsFluidTick;

    /**
     * Construct from a pre-computed surface-height grid and a vanilla
     * fallback. If the Rust handle is 0, every call forwards to the
     * fallback transparently.
     *
     * @param chunkMinBlockX chunk's min block X (inclusive)
     * @param chunkMinBlockZ chunk's min block Z (inclusive)
     * @param chunkMaxBlockX chunk's max block X (inclusive — vanilla
     *                       calls this {@code chunkPos.getEndX()})
     * @param chunkMaxBlockZ chunk's max block Z
     * @param surfaceGrid    {@link IntBuffer} of i32 surface heights
     *                       in row-major order, size {@code GRID_SIDE_X
     *                       × GRID_SIDE_Z}; backed by a direct
     *                       ByteBuffer.
     * @param gridOriginX    world block-X of the grid's (0, 0) cell
     * @param gridOriginZ    world block-Z of the grid's (0, 0) cell
     * @param vanillaFallback the AquiferSampler.Impl we delegate to if
     *                       {@code initAquifer} fails. Must not be
     *                       null — caller is expected to pass the
     *                       sampler vanilla would have used.
     */
    public RustAquiferSampler(
            int seaLevel,
            int chunkMinBlockX,
            int chunkMinBlockZ,
            int chunkMaxBlockX,
            int chunkMaxBlockZ,
            int minY,
            int height,
            int surfaceHeightEstimate,
            ByteBuffer surfaceGridBuf,
            int gridOriginX,
            int gridOriginZ,
            AquiferSampler vanillaFallback) {
        if (vanillaFallback == null) {
            throw new IllegalArgumentException("vanillaFallback must not be null");
        }
        this.vanillaFallback = vanillaFallback;

        long h = 0L;
        if (RustBridge.NATIVE_AVAILABLE && RustAquiferDispatch.ENABLED) {
            try {
                h = RustBridge.initAquifer(
                        seaLevel,
                        chunkMinBlockX,
                        chunkMinBlockZ,
                        chunkMaxBlockX,
                        chunkMaxBlockZ,
                        minY,
                        height,
                        surfaceHeightEstimate,
                        surfaceGridBuf,
                        gridOriginX,
                        gridOriginZ,
                        GRID_SIDE_X,
                        GRID_SIDE_Z,
                        GRID_STRIDE_BLOCKS);
            } catch (Throwable t) {
                ExampleMod.LOGGER.warn("[aquifer-rust] initAquifer threw: {}", t.toString());
                h = 0L;
            }
        }
        this.handle = h;
        if (h != 0L) {
            RustAquiferDispatch.wrappersCreated.incrementAndGet();
            // Register cleanup. The lambda holds only the primitive
            // long handle and a static AtomicLong reference, so it
            // doesn't pin `this` and prevent GC.
            final long capturedHandle = h;
            CLEANER.register(this, () -> {
                RustBridge.freeAquifer(capturedHandle);
                RustAquiferDispatch.wrappersFreed.incrementAndGet();
            });
        } else {
            RustAquiferDispatch.constructionFallbacks.incrementAndGet();
        }
    }

    @Nullable
    @Override
    public BlockState apply(DensityFunction.NoisePos pos, double density) {
        if (this.handle == 0L) {
            BlockState fallbackResult = this.vanillaFallback.apply(pos, density);
            this.lastNeedsFluidTick = this.vanillaFallback.needsFluidTick();
            return fallbackResult;
        }
        long packed = RustBridge.applyAquifer(
                this.handle, pos.blockX(), pos.blockY(), pos.blockZ(), density);
        // Low 8 bits = result discriminant.
        // Bit 8 = needs_fluid_tick.
        int kind = (int) (packed & 0xFF);
        boolean rustTick = (packed & 0x100) != 0;
        BlockState rustResult = switch (kind) {
            case 0 -> null; // RESULT_NONE — no aquifer override.
            case 1 -> Blocks.AIR.getDefaultState();
            case 2 -> Blocks.WATER.getDefaultState();
            case 3 -> Blocks.LAVA.getDefaultState();
            default -> null;
        };

        if (RustAquiferDispatch.PARITY_MODE) {
            // Run vanilla in parallel and compare. ~2× cost — diag only.
            BlockState vanillaResult = this.vanillaFallback.apply(pos, density);
            boolean vanillaTick = this.vanillaFallback.needsFluidTick();
            RustAquiferDispatch.parityCompared.incrementAndGet();
            if (!blockStatesEqual(rustResult, vanillaResult)) {
                long n = RustAquiferDispatch.parityBlockMismatch.incrementAndGet();
                // Rate-limit logging — first ~20 mismatches verbose,
                // then a count summary.
                if (n <= 20 || (n & 0xFFFL) == 0) {
                    ExampleMod.LOGGER.warn(
                            "[aquifer-parity] block mismatch #{} at ({},{},{}) density={} : rust={} vanilla={}",
                            n, pos.blockX(), pos.blockY(), pos.blockZ(),
                            String.format("%.4f", density),
                            describeState(rustResult),
                            describeState(vanillaResult));
                }
            }
            if (rustTick != vanillaTick) {
                long n = RustAquiferDispatch.parityTickMismatch.incrementAndGet();
                if (n <= 20 || (n & 0xFFFL) == 0) {
                    ExampleMod.LOGGER.warn(
                            "[aquifer-parity] tick mismatch #{} at ({},{},{}) : rust={} vanilla={}",
                            n, pos.blockX(), pos.blockY(), pos.blockZ(),
                            rustTick, vanillaTick);
                }
            }
        }

        this.lastNeedsFluidTick = rustTick;
        return rustResult;
    }

    private static boolean blockStatesEqual(@Nullable BlockState a, @Nullable BlockState b) {
        if (a == b) return true;
        if (a == null || b == null) return false;
        return a.getBlock() == b.getBlock();
    }

    private static String describeState(@Nullable BlockState s) {
        if (s == null) return "null";
        return s.getBlock().toString();
    }

    @Override
    public boolean needsFluidTick() {
        return this.lastNeedsFluidTick;
    }

    /**
     * Build the surface-height grid for a chunk by sampling vanilla's
     * {@code estimateSurfaceHeight} at {@link #GRID_SIDE_X} ×
     * {@link #GRID_SIDE_Z} stride-spaced points. Returns the
     * (origin_x, origin_z) of the grid's (0, 0) cell in world block
     * coordinates, plus the populated direct ByteBuffer.
     *
     * <p>Caller invokes this BEFORE constructing the wrapper, with a
     * reference to the live {@code estimateSurfaceHeight} method (via
     * mixin invoker — vanilla's method is package-private).
     */
    public static GridResult buildSurfaceGrid(
            int chunkMinBlockX,
            int chunkMinBlockZ,
            SurfaceHeightEstimator estimator) {
        long t0 = System.nanoTime();
        // Origin: cover offset[0] = -3 chunks (-48 blocks) horizontally,
        // offset[1] = -1 chunk (-16 blocks) vertically. Padding is
        // expressed in blocks because the queryable extent doesn't
        // depend on grid stride (a denser grid covers the same range
        // with more samples).
        int gridOriginX = chunkMinBlockX - GRID_PADDING_X_BLOCKS;
        int gridOriginZ = chunkMinBlockZ - GRID_PADDING_Z_BLOCKS;

        int total = GRID_SIDE_X * GRID_SIDE_Z;
        ByteBuffer buf = ByteBuffer.allocateDirect(total * Integer.BYTES)
                .order(ByteOrder.nativeOrder());
        for (int gz = 0; gz < GRID_SIDE_Z; gz++) {
            for (int gx = 0; gx < GRID_SIDE_X; gx++) {
                int worldX = gridOriginX + gx * GRID_STRIDE_BLOCKS;
                int worldZ = gridOriginZ + gz * GRID_STRIDE_BLOCKS;
                int h = estimator.estimate(worldX, worldZ);
                buf.putInt((gz * GRID_SIDE_X + gx) * Integer.BYTES, h);
            }
        }
        RustAquiferDispatch.gridBuildTotalNs.addAndGet(System.nanoTime() - t0);
        return new GridResult(buf, gridOriginX, gridOriginZ);
    }

    /** Functional bridge to vanilla's package-private
     *  {@code ChunkNoiseSampler.estimateSurfaceHeight}. Implemented by
     *  the route mixin, which calls the shadow method on `this`. */
    @FunctionalInterface
    public interface SurfaceHeightEstimator {
        int estimate(int blockX, int blockZ);
    }

    public record GridResult(ByteBuffer buf, int originX, int originZ) {}
}
