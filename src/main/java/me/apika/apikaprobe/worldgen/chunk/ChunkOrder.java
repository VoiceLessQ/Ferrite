package me.apika.apikaprobe.worldgen.chunk;

// Request order for pregen; RING is the resumable default, the others are A/B experiments.
public enum ChunkOrder {
	RING, SCAN, DIAG;

	public static ChunkOrder parse(String s) {
		for (ChunkOrder o : values()) if (o.name().equalsIgnoreCase(s)) return o;
		return null;
	}

	// Flat (x, z) pairs for the whole square in this order; RING is handled by the iterator itself.
	int[] sequence(int centerX, int centerZ, int radius) {
		int d = 2 * radius + 1;
		int[] seq = new int[d * d * 2];
		int i = 0;
		switch (this) {
			case SCAN -> {
				for (int row = 0; row < d; row++) {
					for (int col = 0; col < d; col++) {
						seq[i++] = centerX - radius + col;
						seq[i++] = centerZ - radius + row;
					}
				}
			}
			case DIAG -> {
				for (int s = 0; s <= 2 * (d - 1); s++) {
					int rowLo = Math.max(0, s - (d - 1));
					int rowHi = Math.min(d - 1, s);
					for (int row = rowLo; row <= rowHi; row++) {
						seq[i++] = centerX - radius + (s - row);
						seq[i++] = centerZ - radius + row;
					}
				}
			}
			default -> throw new IllegalStateException("RING has no flat sequence");
		}
		return seq;
	}
}
