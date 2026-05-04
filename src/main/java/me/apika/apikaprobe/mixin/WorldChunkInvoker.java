package me.apika.apikaprobe.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.chunk.LevelChunk;

/**
 * Exposes LevelChunk's private updateBlockEntityTicker(T) so other
 * mixins can re-evaluate a block entity's ticker registration on
 * demand. Vanilla's update path runs unchanged: getTicker is
 * re-queried, the gating mixin returns null when suppression
 * applies, and removeBlockEntityTicker cleans up.
 */
@Mixin(LevelChunk.class)
public interface WorldChunkInvoker {

	@Invoker("updateBlockEntityTicker")
	<T extends BlockEntity> void apikaprobe$updateBlockEntityTicker(T blockEntity);
}
