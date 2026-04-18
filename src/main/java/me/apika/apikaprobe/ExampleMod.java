package me.apika.apikaprobe;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

import net.fabricmc.api.ModInitializer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class ExampleMod implements ModInitializer {
	public static final String MOD_ID = "apikaprobe";

	// This logger is used to write text to the console and the log file.
	// It is considered best practice to use your mod id as the logger's name.
	// That way, it's clear which mod wrote info, warnings, and errors.
	public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

	@Override
	public void onInitialize() {
		LOGGER.info("Hello Fabric world!");

		if (!RustBridge.NATIVE_AVAILABLE) {
			LOGGER.warn("Native engine unavailable — skipping Rust init and heightmap bench.");
			return;
		}

		int threads = RustBridge.initEngine();
		LOGGER.info("[rust-engine] Rayon pool size = {}", threads);

		RustBridge.main();
		benchHeightmap();
	}

	private static void benchHeightmap() {
		int size = 256;
		ByteBuffer buf = ByteBuffer.allocateDirect(size * size * 4).order(ByteOrder.nativeOrder());

		// warm-up
		RustBridge.generateHeightmap(buf, 42L, 0, 0, size);

		long t0 = System.nanoTime();
		RustBridge.generateHeightmap(buf, 42L, 0, 0, size);
		long elapsedNs = System.nanoTime() - t0;

		buf.rewind();
		float min = Float.POSITIVE_INFINITY;
		float max = Float.NEGATIVE_INFINITY;
		double sum = 0.0;
		int count = size * size;
		for (int i = 0; i < count; i++) {
			float v = buf.getFloat();
			if (v < min) min = v;
			if (v > max) max = v;
			sum += v;
		}

		LOGGER.info("[rust-heightmap] {}x{} filled in {} us  min={} max={} avg={}",
				size, size, elapsedNs / 1_000, min, max, sum / count);
	}
}
