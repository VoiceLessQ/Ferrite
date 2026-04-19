package me.apika.apikaprobe;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.Locale;

public class RustBridge {
  private static final String NATIVE_RESOURCE_PATH = "/assets/rusty/natives/rust_mod.dll";

  public static final boolean NATIVE_AVAILABLE;

  static {
    NATIVE_AVAILABLE = loadNativeLibrary();
  }

  private RustBridge() {}

  private static boolean loadNativeLibrary() {
    String osName = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
    if (!osName.contains("win")) {
      ExampleMod.LOGGER.warn(
          "rust_mod native library is Windows-only (OS detected: {}). Falling back to pure Java — no Rust-side features available.",
          osName);
      return false;
    }

    try (InputStream in = RustBridge.class.getResourceAsStream(NATIVE_RESOURCE_PATH)) {
      if (in == null) {
        ExampleMod.LOGGER.error(
            "rust_mod native library not found in jar at {}. The jar is built without the DLL — performance diagnostics will run without native support.",
            NATIVE_RESOURCE_PATH);
        return false;
      }

      File tempFile = File.createTempFile("rust_mod_", ".dll");
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
}
