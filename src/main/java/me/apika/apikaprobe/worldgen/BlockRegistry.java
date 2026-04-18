package me.apika.apikaprobe.worldgen;

import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;

public final class BlockRegistry {
	private static final BlockState[] TABLE = new BlockState[7];

	private BlockRegistry() {}

	public static void init() {
		TABLE[0] = Blocks.AIR.getDefaultState();
		TABLE[1] = Blocks.STONE.getDefaultState();
		TABLE[2] = Blocks.DIRT.getDefaultState();
		TABLE[3] = Blocks.GRASS_BLOCK.getDefaultState();
		TABLE[4] = Blocks.WATER.getDefaultState();
		TABLE[5] = Blocks.BEDROCK.getDefaultState();
		TABLE[6] = Blocks.DEEPSLATE.getDefaultState();
	}

	public static BlockState get(int id) {
		if (id < 0 || id >= TABLE.length) {
			return TABLE[1];
		}
		BlockState state = TABLE[id];
		return state != null ? state : TABLE[1];
	}
}
