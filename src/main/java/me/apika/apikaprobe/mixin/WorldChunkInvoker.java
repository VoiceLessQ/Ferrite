package me.apika.apikaprobe.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

import net.minecraft.block.entity.BlockEntity;
import net.minecraft.world.chunk.WorldChunk;

/**
 * Exposes {@link WorldChunk}'s private {@code updateTicker(BlockEntity)}
 * so {@link SignEditorChangeMixin} can re-evaluate a sign's ticker
 * registration the moment its editor field flips. Vanilla's update
 * path runs unchanged: getBlockEntityTicker is re-queried, the
 * gating mixin returns null when the sign has no editor, and
 * removeBlockEntityTicker cleans up.
 */
@Mixin(WorldChunk.class)
public interface WorldChunkInvoker {

	@Invoker("updateTicker")
	<T extends BlockEntity> void apikaprobe$updateTicker(T blockEntity);
}
