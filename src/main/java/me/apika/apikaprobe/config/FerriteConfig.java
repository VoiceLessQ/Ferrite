package me.apika.apikaprobe.config;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Properties;

import net.fabricmc.loader.api.FabricLoader;

import me.apika.apikaprobe.bridge.ExampleMod;
import me.apika.apikaprobe.entity.CrammingDispatcher;
import me.apika.apikaprobe.monitor.HopperHintMonitor;
import me.apika.apikaprobe.monitor.MonitorLog;
import me.apika.apikaprobe.redstone.FerriteWireConfig;

/**
 * Persists user-facing module toggles across restarts (#13) in
 * config/ferrite.properties.  Only deltas from defaults are written, so
 * a fresh install has no file and version-to-version default changes
 * take effect unless the user pinned the module.  Diagnostic and
 * experiment flags (surface, validators, probes) stay volatile on
 * purpose; prewarm too, since enabling it at boot breaks spawn loading.
 * A malformed file is logged and ignored; it never blocks server boot.
 */
public final class FerriteConfig {
	private FerriteConfig() {}

	private static final String FILE_NAME = "ferrite.properties";

	public static final String KEY_CRAMMING = "cramming";
	public static final String KEY_HOPPER = "hopper";
	public static final String KEY_REDSTONE_AC = "redstone-ac";
	public static final String KEY_LOG_MONITORS = "log-monitors";
	public static final String KEY_LOG_MUTED = "log-muted";

	private static final Properties STATE = new Properties();

	private static Path file() {
		return FabricLoader.getInstance().getConfigDir().resolve(FILE_NAME);
	}

	/** Reads the file and applies saved toggles. Call once from mod init. */
	public static synchronized void load() {
		Path path = file();
		if (!Files.isRegularFile(path)) return;
		try (Reader in = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
			STATE.load(in);
		} catch (IOException | IllegalArgumentException e) {
			ExampleMod.LOGGER.warn("[config] could not read {}; using defaults: {}",
					path, e.getMessage());
			STATE.clear();
			return;
		}
		apply();
		ExampleMod.LOGGER.info("[config] applied {} saved toggle(s) from {}",
				STATE.size(), path);
	}

	private static void apply() {
		String v;
		if ((v = STATE.getProperty(KEY_CRAMMING)) != null) {
			CrammingDispatcher.ENABLED = Boolean.parseBoolean(v);
		}
		if ((v = STATE.getProperty(KEY_HOPPER)) != null) {
			boolean on = Boolean.parseBoolean(v);
			HopperHintMonitor.USE_HINT = on;
			me.apika.apikaprobe.hopper.PerSlotFireConfig.ENABLE = on;
			me.apika.apikaprobe.hopper.HopperLaneRouteConfig.ENABLE = on;
		}
		if ((v = STATE.getProperty(KEY_REDSTONE_AC)) != null) {
			FerriteWireConfig.ENABLED = Boolean.parseBoolean(v);
		}
		if ((v = STATE.getProperty(KEY_LOG_MONITORS)) != null) {
			MonitorLog.ENABLED = Boolean.parseBoolean(v);
		}
		if ((v = STATE.getProperty(KEY_LOG_MUTED)) != null && !v.isEmpty()) {
			for (String cat : v.split(",")) {
				if (!cat.isEmpty()) MonitorLog.mute(cat);
			}
		}
		for (String key : STATE.stringPropertyNames()) {
			switch (key) {
				case KEY_CRAMMING, KEY_HOPPER, KEY_REDSTONE_AC,
						KEY_LOG_MONITORS, KEY_LOG_MUTED -> {}
				default -> ExampleMod.LOGGER.warn(
						"[config] unknown key \"{}\" in {} (ignored)", key, FILE_NAME);
			}
		}
	}

	/**
	 * Records a toggle and saves. Passing the module's compiled-in default
	 * removes the key instead, keeping the file delta-only.
	 */
	public static synchronized void set(String key, boolean value, boolean defaultValue) {
		if (value == defaultValue) STATE.remove(key);
		else STATE.setProperty(key, Boolean.toString(value));
		save();
	}

	/** Records an always-explicit string value; empty removes the key. */
	public static synchronized void setString(String key, String value) {
		if (value == null || value.isEmpty()) STATE.remove(key);
		else STATE.setProperty(key, value);
		save();
	}

	private static void save() {
		Path path = file();
		try {
			if (STATE.isEmpty()) {
				Files.deleteIfExists(path);
				return;
			}
			Path tmp = path.resolveSibling(FILE_NAME + ".tmp");
			try (Writer out = Files.newBufferedWriter(tmp, StandardCharsets.UTF_8)) {
				STATE.store(out, "Ferrite module toggles; managed by /ferrite commands, deltas from defaults only");
			}
			Files.move(tmp, path, StandardCopyOption.REPLACE_EXISTING,
					StandardCopyOption.ATOMIC_MOVE);
		} catch (IOException e) {
			ExampleMod.LOGGER.warn("[config] could not save {}: {}", path, e.getMessage());
		}
	}
}
