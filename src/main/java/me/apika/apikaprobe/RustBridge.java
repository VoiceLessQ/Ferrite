package me.apika.apikaprobe;

import me.apika.apikaprobe.bridge.ExampleMod;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.Locale;

public class RustBridge {
  // Per-platform resource paths. The jar bundles all three; loadNativeLibrary
  // picks the one that matches the host OS at runtime.
  private static final String NATIVE_WINDOWS = "/assets/ferrite/natives/windows/rust_mod.dll";
  private static final String NATIVE_LINUX   = "/assets/ferrite/natives/linux/librust_mod.so";
  private static final String NATIVE_MACOS   = "/assets/ferrite/natives/macos/librust_mod.dylib";

  public static final boolean NATIVE_AVAILABLE;

  static {
    NATIVE_AVAILABLE = loadNativeLibrary();
  }

  private RustBridge() {}

  private static boolean loadNativeLibrary() {
    String osName = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
    String resourcePath;
    String tempSuffix;
    if (osName.contains("win")) {
      resourcePath = NATIVE_WINDOWS;
      tempSuffix = ".dll";
    } else if (osName.contains("linux")) {
      resourcePath = NATIVE_LINUX;
      tempSuffix = ".so";
    } else if (osName.contains("mac") || osName.contains("darwin")) {
      resourcePath = NATIVE_MACOS;
      tempSuffix = ".dylib";
    } else {
      ExampleMod.LOGGER.warn(
          "rust_mod native library: unsupported OS \"{}\". Falling back to pure Java.",
          osName);
      return false;
    }

    try (InputStream in = RustBridge.class.getResourceAsStream(resourcePath)) {
      if (in == null) {
        ExampleMod.LOGGER.error(
            "rust_mod native not found in jar at {}. Jar built without native for this platform — running without native support.",
            resourcePath);
        return false;
      }

      File tempFile = File.createTempFile("rust_mod_", tempSuffix);
      tempFile.deleteOnExit();
      Files.copy(in, tempFile.toPath(), StandardCopyOption.REPLACE_EXISTING);

      System.load(tempFile.getAbsolutePath());
      ExampleMod.LOGGER.info("Loaded rust_mod from {}", tempFile.getAbsolutePath());
      return true;
    } catch (UnsatisfiedLinkError | IOException e) {
      ExampleMod.LOGGER.error(
          "rust_mod native library failed to load — falling back to Java. Reason: {}",
          e.getMessage());
      return false;
    }
  }

  public static native int initEngine();

  public static native void computeChunkTerrain(
      java.nio.ByteBuffer cornerDensities,
      java.nio.ByteBuffer outBlockIds,
      int cellWidth,
      int cellHeight,
      int minY,
      int seaLevel,
      int chunkX,
      int chunkZ);

  public static native void computeEntityPhysics(
      java.nio.ByteBuffer snapshot,
      java.nio.ByteBuffer requests,
      java.nio.ByteBuffer results,
      int entityCount);

  public static native void computeCramming(
      java.nio.ByteBuffer requests,
      java.nio.ByteBuffer results,
      int entityCount);

  /**
   * Runs the wire-power BFS on a serialized network. Returns the number
   * of populated RedstoneResult entries in `results` (i.e. how many
   * wire positions changed power and need a Java-side setBlockState).
   * See [RedstoneHandoff] for buffer layout.
   */
  public static native int computeRedstoneBfs(
      java.nio.ByteBuffer requests,
      java.nio.ByteBuffer results,
      int nodeCount);

  /**
   * Evaluates a compiled surface rule for one column position. Returns
   * the blockstate ID (≥0, indexes into the per-tree blockstate table)
   * or -1 if no rule matched.
   *
   * <p>{@code biomeMatchBits} is a precomputed bitset (1 byte per
   * biome-set-pool entry, 0=miss, 1=hit) so Rust can do O(1) biome
   * lookups instead of walking string sets across the JNI boundary.
   *
   * <p>{@code noiseValues} is a direct byte buffer of {@code noiseCount}
   * f64s in little-endian order — pre-sampled by Java per column.
   */
  /**
   * Per-call evaluator. {@code factorySeeds} is a direct buffer of
   * {@code factorySeedCount × 2} i64 little-endian values (seedLo,
   * seedHi pairs aligned with the tree's randomNameTable). Used by
   * Rust to construct {@code XoroshiroPositionalRandomFactory} for
   * OP_VERT_GRADIENT per-block PRNG. May be null/0-count for trees
   * without VerticalGradient rules — Rust falls back to midpoint
   * approximation in that case.
   */
  public static native int evaluateSurfaceRule(
      java.nio.ByteBuffer bytecode,
      int bytecodeLen,
      java.nio.ByteBuffer biomeMatchBits,
      int biomeMatchCount,
      int blockX,
      int blockY,
      int blockZ,
      int runDepth,
      int stoneDepthAbove,
      int stoneDepthBelow,
      int fluidHeight,
      boolean isCold,
      boolean isSteep,
      int surfaceHeight,
      double secondaryDepth,
      java.nio.ByteBuffer noiseValues,
      int noiseCount,
      java.nio.ByteBuffer factorySeeds,
      int factorySeedCount);

  /**
   * Batch evaluation: one JNI call per chunk. Replaces ~1k–60k single-
   * column calls, amortizing the JNI boundary cost across all columns.
   * Rust runs the eval loop per-column in parallel via Rayon.
   *
   * <p>All input buffers are direct {@link java.nio.ByteBuffer}s with
   * little-endian byte order. Per-column scalars are parallel arrays
   * of length {@code columnCount}. {@code biomeMatchBits} and
   * {@code noiseValues} are flattened: column {@code c}'s slice lives
   * at {@code [c * stride .. (c+1) * stride]}.
   *
   * <p>Output {@code results} is i32 × columnCount, written in place;
   * each entry is a blockstate ID (≥0, indexes the per-tree table) or
   * -1 if no rule matched for that column.
   */
  /**
   * Phase 1 queue benchmark (docs/REDSTONE_PORT_PLAN.md). Receives an
   * array of (u32 id, u8 priority) pairs, does "offer all, poll all",
   * writes u32 ids in poll order to results_buf. Used only by the
   * /ferrite redstone bench command to decide whether Phase 2 is
   * worth pursuing.
   */
  public static native void benchRedstoneQueue(
      java.nio.ByteBuffer pairs,
      java.nio.ByteBuffer results,
      int n);

  /**
   * Begin a fresh worldgen-state build on the Rust side. Derives the
   * root positional random factory from {@code seed} the same way
   * vanilla's {@code RandomState} (yarn {@code RandomState}) does:
   * {@code Xoroshiro(seed).forkPositional()}.
   *
   * <p>Pair with one {@link #registerNoiseParameter} call per named
   * noise from {@code Registry<NoiseParameters>}, then
   * {@link #finalizeWorldgenState}. After finalization the Rust side
   * can sample any named noise without crossing JNI per-position.
   *
   * <p>Returns true on success, false if Rust rejected the call
   * (e.g. state already finalized this process).
   */
  public static native boolean initWorldgenState(long seed);

  /**
   * Register one named noise into the in-progress build. {@code name}
   * is the UTF-8-encoded full identifier ({@code ResourceLocation.toString()}
   * form, e.g. {@code "minecraft:temperature"}) — that's what vanilla
   * hashes via {@code PositionalRandomFactory.fromHashOf(ResourceLocation)}.
   *
   * <p>{@code amplitudes} is a direct {@link java.nio.ByteBuffer} of
   * {@code ampCount} f64 values in little-endian order (host byte order
   * on x86_64/aarch64 — same convention as the surface evaluator).
   *
   * <p>Returns true on success, false on any decode/registration error.
   */
  public static native boolean registerNoiseParameter(
      java.nio.ByteBuffer name,
      int nameLen,
      int firstOctave,
      java.nio.ByteBuffer amplitudes,
      int ampCount);

  /**
   * Seal the in-progress build and publish it as the global worldgen
   * state. Subsequent {@link #registerNoiseParameter} calls fail.
   * Returns false if there was nothing to finalize or the state was
   * already published.
   */
  public static native boolean finalizeWorldgenState();

  /**
   * Number of noises currently registered in the finalized worldgen
   * state, or -1 if not finalized. Diagnostic only.
   */
  public static native int worldgenNoiseCount();

  /** Diagnostic: root positional-factory seedLo/seedHi. 0 if not finalized. */
  public static native long worldgenRootSeedLo();
  public static native long worldgenRootSeedHi();

  /** Compute the root positional-factory (lo, hi) for an arbitrary
   *  world seed without requiring init/finalize. Returns long[2] = (lo, hi). */
  public static native long[] rootSeedsForSeed(long seed);

  /**
   * End-to-end Rust biome lookup at a block coord. Samples the 6
   * `ferrite:climate/<axis>` DFs (resolved from the live RandomState
   * router at world load), quantizes, runs the R-tree. Returns biome
   * ID, or -1 if any climate DF or the biome tree isn't registered.
   * Block coords are snapped to quart-aligned values internally.
   */
  public static native int findBiomeAtBlockRust(int blockX, int blockY, int blockZ);

  /**
   * Batched biome lookup over an `(sideX × sideZ)` grid sampled at
   * `stepBlocks` spacing. Fills {@code outBuffer} with i32 biome IDs in
   * row-major order: index {@code (iz * sideX + ix)} for cell at
   * {@code (originX + ix*step, originY, originZ + iz*step)}.
   * One JNI call replaces sideX*sideZ calls to
   * {@link #findBiomeAtBlockRust}; per-cell work is identical.
   * Returns the number of cells written, or -1 on error.
   * {@code outBuffer} must be a direct ByteBuffer with at least
   * {@code sideX * sideZ * 4} bytes capacity.
   */
  public static native int findBiomeRegionRust(
      int originX, int originY, int originZ,
      int sideX, int sideZ, int stepBlocks,
      java.nio.ByteBuffer outBuffer);

  /**
   * 3D batch biome lookup over an {@code (sideX × sideY × sideZ)} grid.
   * Output layout: row-major {@code (iy, iz, ix)} —
   * index {@code (iy * sideZ + iz) * sideX + ix}.
   * One call replaces sideX*sideY*sideZ per-cell calls AND gives Rayon
   * a large enough workload to actually parallelize. For a full chunk
   * (4×96×4 = 1536 cells) this drops per-chunk warm cost from ~26ms
   * to ~3-5ms.
   * Returns total cells written, or -1 on error.
   * {@code outBuffer} must hold at least {@code sideX*sideY*sideZ*4} bytes.
   */
  public static native int findBiomeRegion3DRust(
      int originX, int originY, int originZ,
      int sideX, int sideY, int sideZ, int stepBlocks,
      java.nio.ByteBuffer outBuffer);

  /**
   * Bulk-register biome entries into Rust's worldgen state. Buffer
   * format: per-entry stride 112 bytes, host byte order:
   * {@code [i32 biomeId, i32 _pad, i64×13 (6×(min,max) + offset)]}.
   * Call between {@link #initWorldgenState} and
   * {@link #finalizeWorldgenState}.
   */
  public static native boolean registerBiomeEntries(java.nio.ByteBuffer entries, int count);

  /**
   * Query Rust's biome R-tree for the biome at a quantized climate
   * target point. Returns -1 if Rust's biome tree wasn't registered
   * (worldgen state not finalized yet, or a custom world without
   * MultiNoiseBiomeSource).
   *
   * <p>Coords are vanilla's quantized form: {@code (long)(coord * 10000)}.
   * Use {@link Climate.TargetPoint#toParameterArray} to extract them
   * from a vanilla {@code Climate.Sampler.sample} result.
   */
  public static native int queryBiomeAtTarget(
      long temperature, long humidity, long continentalness,
      long erosion, long depth, long weirdness);

  /**
   * Register one named density function into Rust's worldgen state.
   * {@code bytecode} is the DF tree encoded per Rust-side opcode
   * format (see rust/mod/src/density.rs). Call between
   * {@link #initWorldgenState} and {@link #finalizeWorldgenState}.
   * Returns false if bytecode fails to parse.
   */
  public static native boolean registerDensityFunction(
      java.nio.ByteBuffer name, int nameLen,
      java.nio.ByteBuffer bytecode, int bytecodeLen);

  /**
   * Sample a registered density function at an integer block coord.
   * Returns {@code NaN} if not finalized or not registered.
   */
  public static native double sampleDensityFunction(
      java.nio.ByteBuffer name, int nameLen,
      int x, int y, int z);

  /**
   * 3D batch density-function sampler. Computes the named registered
   * DF across {@code (sideX × sideY × sideZ)} cells at
   * {@code stepBlocks} spacing, writing f64 values into
   * {@code outBuffer}.
   *
   * <p>Output layout: row-major {@code (iy, iz, ix)} —
   * index {@code (iy * sideZ + iz) * sideX + ix} for cell at
   * {@code (originX + ix*step, originY + iy*step, originZ + iz*step)}.
   * Internal Rayon parallelism over Y-slabs.
   *
   * <p>{@code outBuffer} must be a direct ByteBuffer with at least
   * {@code sideX * sideY * sideZ * 8} bytes capacity.
   *
   * <p>Returns total cells written, or -1 on error.
   */
  public static native int sampleDensityRegion3DRust(
      java.nio.ByteBuffer name, int nameLen,
      int originX, int originY, int originZ,
      int sideX, int sideY, int sideZ, int stepBlocks,
      java.nio.ByteBuffer outBuffer);

  /**
   * Phase 2.5 step 2b: 3D batch density sampler with separate per-axis
   * steps (vanilla's interpolator slice fill uses cellWidth=4 horizontal,
   * cellHeight=8 vertical — the unified {@code stepBlocks} above doesn't fit).
   *
   * <p>Output layout matches {@link #sampleDensityRegion3DRust}: row-major
   * {@code (iy, iz, ix)} — index {@code (iy * sideZ + iz) * sideX + ix}.
   *
   * <p>For per-interpolator slice fill: {@code sideX=1, sideY=verticalCellCount+1,
   * sideZ=horizontalCellCount+1, stepX=cellWidth, stepY=cellHeight, stepZ=cellWidth}.
   *
   * <p>Returns total cells written or -1 on error.
   */
  public static native int sampleDensitySlicesRust(
      java.nio.ByteBuffer name, int nameLen,
      int originX, int originY, int originZ,
      int sideX, int sideY, int sideZ,
      int stepX, int stepY, int stepZ,
      java.nio.ByteBuffer outBuffer);

  /**
   * Phase 2: Bulk-fill an entire chunk's per-block density buffer in
   * one JNI call. Internally:
   * <ol>
   *   <li>Sample {@code name} at the cell-corner grid
   *       (5 × 49 × 5 = 1,225 corners; cellWidth=4 X/Z, cellHeight=8 Y).</li>
   *   <li>Per-block trilinear lerp from those corners into the output
   *       buffer, matching vanilla's NoiseInterpolator math.</li>
   * </ol>
   *
   * <p>Output layout: row-major {@code (by, bz, bx)} —
   * index {@code (by * 16 + bz) * 16 + bx} for the f64 density value at
   * block {@code (chunkMinBlockX + bx, -64 + by, chunkMinBlockZ + bz)}.
   * Buffer must hold at least {@code 16 * 384 * 16 * 8 = 786,432} bytes.
   * Y is fixed at -64..319 (overworld).
   *
   * <p>Returns total cells written (98,304 for an overworld chunk) or
   * -1 on error.
   */
  public static native int populateNoiseBufferRust(
      java.nio.ByteBuffer name, int nameLen,
      int chunkMinBlockX, int chunkMinBlockZ,
      java.nio.ByteBuffer outBuffer);

  /** Count of registered density functions, or -1 if state not finalized. */
  public static native int densityFunctionCount();

  /**
   * Debug dump of a registered DF as a multi-line String. Useful for
   * verifying the walker built what we expect. Returns null on miss.
   */
  public static native String dumpDensityFunction(java.nio.ByteBuffer name, int nameLen);

  /**
   * Sample a named noise from Rust's finalized worldgen state.
   * {@code name} is the UTF-8 identifier buffer (same format as
   * {@link #registerNoiseParameter}). Returns {@code NaN} if the state
   * isn't finalized or the name isn't registered.
   */
  public static native double sampleWorldgenNoise(
      java.nio.ByteBuffer name,
      int nameLen,
      double x,
      double y,
      double z);

  public static native void evaluateSurfaceRuleBatch(
      java.nio.ByteBuffer bytecode,
      int bytecodeLen,
      int biomeSetCount,
      int noiseChannelCount,
      java.nio.ByteBuffer biomeMatchBits,
      java.nio.ByteBuffer blockYs,
      java.nio.ByteBuffer runDepths,
      java.nio.ByteBuffer stoneAbove,
      java.nio.ByteBuffer stoneBelow,
      java.nio.ByteBuffer fluidHeights,
      java.nio.ByteBuffer surfaceHeights,
      java.nio.ByteBuffer flags,
      java.nio.ByteBuffer secondaryDepths,
      java.nio.ByteBuffer noiseValues,
      java.nio.ByteBuffer xs,
      java.nio.ByteBuffer zs,
      java.nio.ByteBuffer factorySeeds,
      int factorySeedCount,
      java.nio.ByteBuffer results,
      int columnCount);

  // ==========================================================================
  // Aquifer port — per-chunk handle-based JNI.
  //
  // Lifecycle: initAquifer → many applyAquifer → freeAquifer.
  // The handle is an opaque jlong (boxed Rust pointer); 0 indicates
  // failure (e.g. worldgen state not finalized).
  //
  // Surface-height grid: see rust/mod/src/aquifer_jni.rs. Java
  // pre-computes a sparse 2D grid by calling vanilla's
  // chunkNoiseSampler.estimateSurfaceHeight at grid points; Rust
  // does nearest-neighbor lookup with a fallback to the construction-
  // time scalar estimate when out of range.
  // ==========================================================================

  /**
   * Allocate per-chunk aquifer state and return a handle. Returns 0 on
   * failure. Pair every successful call with a {@link #freeAquifer}.
   *
   * @param surfaceGridBuf direct ByteBuffer of i32 LE values, row-major
   *                       {@code [gz * gridSideX + gx]}.
   * @param gridStrideBlocks block-spacing between grid points.
   */
  public static native long initAquifer(
      int seaLevel,
      int chunkMinBlockX,
      int chunkMinBlockZ,
      int chunkMaxBlockX,
      int chunkMaxBlockZ,
      int minY,
      int height,
      int surfaceHeightEstimate,
      java.nio.ByteBuffer surfaceGridBuf,
      int gridOriginBlockX,
      int gridOriginBlockZ,
      int gridSideX,
      int gridSideZ,
      int gridStrideBlocks);

  /**
   * Per-block aquifer query. Returns a packed jlong:
   * <ul>
   *   <li>Low 8 bits: result enum
   *     <ul>
   *       <li>0 = NONE (no aquifer override; vanilla density wins)</li>
   *       <li>1 = AIR (very-deep no-fluid)</li>
   *       <li>2 = WATER</li>
   *       <li>3 = LAVA</li>
   *     </ul>
   *   </li>
   *   <li>Bit 8: needsFluidTick (1 = should be ticked, 0 = static)</li>
   * </ul>
   *
   * <p>Mirrors vanilla's
   * {@code Aquifer.Impl.apply(NoisePos, double)} return.
   */
  public static native long applyAquifer(
      long handle, int blockX, int blockY, int blockZ, double density);

  /**
   * Free the aquifer state. MUST be called when the chunk's aquifer
   * goes out of scope, otherwise we leak ~16 KB per chunk forever.
   */
  public static native void freeAquifer(long handle);
}
