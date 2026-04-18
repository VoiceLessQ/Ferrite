package me.apika.apikaprobe;

import java.util.function.Function;

import net.fabricmc.fabric.api.event.Event;
import net.fabricmc.fabric.api.itemgroup.v1.ItemGroupEvents.ModifyEntries;
import net.minecraft.item.Item;

public class RustBridge {
  public static <T extends Item> Function<Item.Settings, Item> itemFactory(Class<T> item) {
    return (settings) -> {
      try {
        return item.getDeclaredConstructor(Item.Settings.class).newInstance(settings);
      } catch (Exception e) {
        ExampleMod.LOGGER.error("======================================= Cannot create new instance ");
        return null;
      }
    };
  }

  public static void registerGroupEvent(Event<ModifyEntries> event, Item item) {
    event.register((entries) -> entries.add(item));
  }

  public static Item SERJIO = null;

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

  public static native void main();

  public static native int initEngine();

  public static native void generateHeightmap(
      java.nio.ByteBuffer buffer, long seed, int originX, int originZ, int size);

  public static native void generateChunk(
      java.nio.ByteBuffer buffer, long seed, int chunkX, int chunkZ);

  public static native void erodeHeightmap(
      java.nio.ByteBuffer buffer, int width, int height, int iterations, long seed);
}
