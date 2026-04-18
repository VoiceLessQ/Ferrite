package me.apika.apikaprobe;

public class RustBridge {
  public static final boolean NATIVE_AVAILABLE;

  static {
    boolean loaded;
    try {
      System.loadLibrary("rust_mod");
      loaded = true;
    } catch (UnsatisfiedLinkError e) {
      ExampleMod.LOGGER.error(
          "rust_mod native library failed to load — falling back to Java. Reason: {}",
          e.getMessage());
      loaded = false;
    }
    NATIVE_AVAILABLE = loaded;
  }

  private RustBridge() {}

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
}
