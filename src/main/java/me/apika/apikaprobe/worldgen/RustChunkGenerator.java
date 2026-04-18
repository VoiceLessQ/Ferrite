package me.apika.apikaprobe.worldgen;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.List;
import java.util.concurrent.CompletableFuture;

import com.mojang.serialization.Codec;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

import me.apika.apikaprobe.RustBridge;

import net.minecraft.block.BlockState;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.world.ChunkRegion;
import net.minecraft.world.HeightLimitView;
import net.minecraft.world.Heightmap;
import net.minecraft.world.biome.source.BiomeAccess;
import net.minecraft.world.biome.source.BiomeSource;
import net.minecraft.world.chunk.Chunk;
import net.minecraft.world.gen.StructureAccessor;
import net.minecraft.world.gen.chunk.Blender;
import net.minecraft.world.gen.chunk.ChunkGenerator;
import net.minecraft.world.gen.chunk.VerticalBlockSample;
import net.minecraft.world.gen.noise.NoiseConfig;

public class RustChunkGenerator extends ChunkGenerator {
	public static final MapCodec<RustChunkGenerator> CODEC = RecordCodecBuilder.mapCodec(instance ->
		instance.group(
			BiomeSource.CODEC.fieldOf("biome_source").forGetter(g -> g.biomeSource),
			Codec.LONG.fieldOf("seed").forGetter(g -> g.seed)
		).apply(instance, RustChunkGenerator::new)
	);

	private static final int CHUNK_X = 16;
	private static final int CHUNK_Z = 16;
	private static final int MIN_Y = -64;
	private static final int MAX_Y = 320;
	private static final int CHUNK_HEIGHT = MAX_Y - MIN_Y; // 384
	private static final int CHUNK_CELLS = CHUNK_X * CHUNK_Z * CHUNK_HEIGHT;
	private static final int CHUNK_BYTES = CHUNK_CELLS * 2;

	private final long seed;

	// NOTE: chunkBuffer is an instance field, not thread-local. Minecraft's
	// current chunk generation pipeline serializes populateNoise calls per
	// ChunkGenerator instance, so a single reusable buffer is safe today.
	// If that ever changes (concurrent populateNoise on the same instance),
	// this becomes a data race — make it ThreadLocal<ByteBuffer> then.
	private final ByteBuffer chunkBuffer =
		ByteBuffer.allocateDirect(CHUNK_BYTES).order(ByteOrder.nativeOrder());

	public RustChunkGenerator(BiomeSource biomeSource, long seed) {
		super(biomeSource);
		this.seed = seed;
	}

	@Override
	protected MapCodec<? extends ChunkGenerator> getCodec() {
		return CODEC;
	}

	@Override
	public CompletableFuture<Chunk> populateNoise(
			Blender blender,
			NoiseConfig noiseConfig,
			StructureAccessor structureAccessor,
			Chunk chunk) {
		ChunkPos pos = chunk.getPos();
		RustBridge.generateChunk(chunkBuffer, seed, pos.x, pos.z);

		BlockPos.Mutable blockPos = new BlockPos.Mutable();
		for (int x = 0; x < CHUNK_X; x++) {
			for (int z = 0; z < CHUNK_Z; z++) {
				int columnBase = (x * CHUNK_Z + z) * CHUNK_HEIGHT;
				int worldX = pos.getStartX() + x;
				int worldZ = pos.getStartZ() + z;
				for (int y = MIN_Y; y < MAX_Y; y++) {
					int idx = columnBase + (y - MIN_Y);
					int id = chunkBuffer.getShort(idx * 2) & 0xFFFF;
					BlockState state = BlockRegistry.get(id);
					if (state.isAir()) {
						continue;
					}
					blockPos.set(worldX, y, worldZ);
					chunk.setBlockState(blockPos, state, 0);
				}
			}
		}
		return CompletableFuture.completedFuture(chunk);
	}

	@Override
	public void carve(
			ChunkRegion chunkRegion,
			long seed,
			NoiseConfig noiseConfig,
			BiomeAccess biomeAccess,
			StructureAccessor structureAccessor,
			Chunk chunk) {
		// no-op
	}

	@Override
	public void buildSurface(
			ChunkRegion region,
			StructureAccessor structures,
			NoiseConfig noiseConfig,
			Chunk chunk) {
		// no-op — Rust already wrote all blocks including surface
	}

	@Override
	public void populateEntities(ChunkRegion region) {
		// no-op
	}

	@Override
	public int getWorldHeight() {
		return CHUNK_HEIGHT;
	}

	@Override
	public int getSeaLevel() {
		return 62;
	}

	@Override
	public int getMinimumY() {
		return MIN_Y;
	}

	@Override
	public int getHeight(int x, int z, Heightmap.Type heightmap, HeightLimitView world, NoiseConfig noiseConfig) {
		return 64;
	}

	@Override
	public VerticalBlockSample getColumnSample(int x, int z, HeightLimitView world, NoiseConfig noiseConfig) {
		BlockState[] states = new BlockState[CHUNK_HEIGHT];
		for (int i = 0; i < CHUNK_HEIGHT; i++) {
			states[i] = BlockRegistry.get(0); // air fallback
		}
		return new VerticalBlockSample(MIN_Y, states);
	}

	@Override
	public void appendDebugHudText(List<String> text, NoiseConfig noiseConfig, BlockPos pos) {
		// no-op
	}
}
