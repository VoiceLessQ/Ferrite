package me.apika.apikaprobe.worldgen.chunk;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.Properties;

/**
 * Persistent snapshot for resume-on-restart. Serialized as a plain
 * {@link Properties} file so it stays human-inspectable for triage —
 * NBT would buy us nothing here (no codec dependencies, no schema
 * evolution worth the ceremony).
 *
 * <p>On graceful completion the snapshot file is deleted and a sibling
 * {@code ferrite_pregen.done} marker is written; that marker is the
 * "first-launch already happened" signal for the dedicated-server
 * property path.
 */
public record PregenSnapshot(
		String worldName,
		int centerX,
		int centerZ,
		int radius,
		ConcentricChunkIterator.State iteratorState
) {
	public static final String SNAPSHOT_FILE = "ferrite_pregen.dat";
	public static final String DONE_MARKER = "ferrite_pregen.done";

	public void writeTo(Path file) throws IOException {
		Properties p = new Properties();
		p.setProperty("worldName", worldName);
		p.setProperty("centerX", Integer.toString(centerX));
		p.setProperty("centerZ", Integer.toString(centerZ));
		p.setProperty("radius", Integer.toString(radius));
		p.setProperty("currentRing", Integer.toString(iteratorState.currentRing()));
		p.setProperty("side", Integer.toString(iteratorState.side()));
		p.setProperty("sidePos", Integer.toString(iteratorState.sidePos()));
		p.setProperty("emitted", Integer.toString(iteratorState.emitted()));
		Files.createDirectories(file.getParent());
		try (OutputStream out = Files.newOutputStream(file)) {
			p.store(out, "Ferrite pre-gen checkpoint -- safe to delete to start fresh");
		}
	}

	public static Optional<PregenSnapshot> readFrom(Path file) {
		if (!Files.exists(file)) return Optional.empty();
		Properties p = new Properties();
		try (InputStream in = Files.newInputStream(file)) {
			p.load(in);
		} catch (IOException e) {
			return Optional.empty();
		}
		try {
			String name = p.getProperty("worldName");
			if (name == null || name.isEmpty()) return Optional.empty();
			ConcentricChunkIterator.State st = new ConcentricChunkIterator.State(
					Integer.parseInt(p.getProperty("currentRing")),
					Integer.parseInt(p.getProperty("side")),
					Integer.parseInt(p.getProperty("sidePos")),
					Integer.parseInt(p.getProperty("emitted")));
			return Optional.of(new PregenSnapshot(
					name,
					Integer.parseInt(p.getProperty("centerX")),
					Integer.parseInt(p.getProperty("centerZ")),
					Integer.parseInt(p.getProperty("radius")),
					st));
		} catch (NullPointerException | NumberFormatException e) {
			return Optional.empty();
		}
	}
}
