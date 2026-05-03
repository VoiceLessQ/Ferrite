package me.apika.apikaprobe.worldgen.chunk;

import net.minecraft.world.level.ChunkPos;

import java.util.Iterator;
import java.util.NoSuchElementException;

/**
 * Concentric annulus walker. Emits chunk coordinates in expanding rings
 * from {@code (centerX, centerZ)} outward to {@code radius} (inclusive).
 *
 * <p>Ring 0 is the center chunk. Ring r contains 8r chunks (the square
 * frame at Chebyshev distance r). Each ring is walked clockwise starting
 * at the top-left corner: top edge L→R, right edge T→B, bottom edge R→L,
 * left edge B→T. Total chunks emitted: {@code (2*radius + 1)^2}.
 */
public final class ConcentricChunkIterator implements Iterator<ChunkPos> {
	private final int centerX;
	private final int centerZ;
	private final int radius;
	private final int total;

	private int currentRing = 0;
	private int side = 0;     // 0=top, 1=right, 2=bottom, 3=left
	private int sidePos = 0;  // index within the current side
	private int emitted = 0;

	public ConcentricChunkIterator(int centerX, int centerZ, int radius) {
		if (radius < 0) throw new IllegalArgumentException("radius must be >= 0");
		this.centerX = centerX;
		this.centerZ = centerZ;
		this.radius = radius;
		final int diameter = 2 * radius + 1;
		this.total = diameter * diameter;
	}

	public ConcentricChunkIterator(int centerX, int centerZ, int radius, State state) {
		this(centerX, centerZ, radius);
		this.currentRing = state.currentRing();
		this.side = state.side();
		this.sidePos = state.sidePos();
		this.emitted = state.emitted();
	}

	public int totalChunks() {
		return total;
	}

	public State snapshot() {
		return new State(currentRing, side, sidePos, emitted);
	}

	/** Iterator position snapshot suitable for resume. The four fields
	 *  fully describe where {@link #next()} will pick up. */
	public record State(int currentRing, int side, int sidePos, int emitted) {}

	@Override
	public boolean hasNext() {
		return emitted < total;
	}

	@Override
	public ChunkPos next() {
		if (!hasNext()) throw new NoSuchElementException();
		final ChunkPos pos;
		if (currentRing == 0) {
			pos = new ChunkPos(centerX, centerZ);
			currentRing = 1;
			side = 0;
			sidePos = 0;
		} else {
			final int r = currentRing;
			switch (side) {
				case 0 -> {
					// top edge, z = cz - r, x = cx - r + sidePos, length 2r+1
					pos = new ChunkPos(centerX - r + sidePos, centerZ - r);
					if (++sidePos > 2 * r) { side = 1; sidePos = 0; }
				}
				case 1 -> {
					// right edge, x = cx + r, z = cz - r + 1 + sidePos, length 2r
					pos = new ChunkPos(centerX + r, centerZ - r + 1 + sidePos);
					if (++sidePos >= 2 * r) { side = 2; sidePos = 0; }
				}
				case 2 -> {
					// bottom edge, z = cz + r, x = cx + r - 1 - sidePos, length 2r
					pos = new ChunkPos(centerX + r - 1 - sidePos, centerZ + r);
					if (++sidePos >= 2 * r) { side = 3; sidePos = 0; }
				}
				case 3 -> {
					// left edge, x = cx - r, z = cz + r - 1 - sidePos, length 2r-1
					pos = new ChunkPos(centerX - r, centerZ + r - 1 - sidePos);
					if (++sidePos >= 2 * r - 1) { side = 0; sidePos = 0; currentRing++; }
				}
				default -> throw new IllegalStateException("invalid side: " + side);
			}
		}
		emitted++;
		return pos;
	}
}
