package me.apika.apikaprobe;

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
  public static native int evaluateSurfaceRule(
      java.nio.ByteBuffer bytecode,
      int bytecodeLen,
      java.nio.ByteBuffer biomeMatchBits,
      int biomeMatchCount,
      int blockY,
      int runDepth,
      int stoneDepthAbove,
      int stoneDepthBelow,
      int fluidHeight,
      boolean isCold,
      boolean isSteep,
      int surfaceHeight,
      double secondaryDepth,
      java.nio.ByteBuffer noiseValues,
      int noiseCount);
}
