package me.apika.apikaprobe.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
import org.spongepowered.asm.mixin.gen.Invoker;

import net.minecraft.server.level.WorldGenRegion;
import net.minecraft.world.level.chunk.ChunkAccess;

@Mixin(WorldGenRegion.class)
public interface WorldGenRegionAccessor {

	@Accessor("center")
	ChunkAccess ferrite$center();

	@Invoker("isWithinWriteZone")
	boolean ferrite$isWithinWriteZone(int chunkX, int chunkZ);
}
