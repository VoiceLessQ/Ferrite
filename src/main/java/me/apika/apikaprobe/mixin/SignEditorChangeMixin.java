package me.apika.apikaprobe.mixin;

import java.util.UUID;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import net.minecraft.block.entity.BlockEntity;
import net.minecraft.block.entity.SignBlockEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import net.minecraft.world.chunk.Chunk;
import net.minecraft.world.chunk.WorldChunk;

/**
 * Re-evaluates the ticker registration whenever a sign's
 * {@code editor} field changes. Pairs with
 * {@link WorldChunkSignTickerMixin}: that mixin gates whether the
 * ticker should exist, this one triggers the gate to be re-asked.
 *
 * <p>{@code updateTicker} is idempotent — calling it when nothing
 * changed costs one HashMap lookup plus a {@code getBlockEntityTicker}
 * dispatch. Cheaper than diffing old vs new editor state in this
 * mixin.
 *
 * <p>Server-side only. Client-side signs don't tick through this path.
 */
@Mixin(SignBlockEntity.class)
public abstract class SignEditorChangeMixin {

	@Inject(
		method = "setEditor(Ljava/util/UUID;)V",
		at = @At("RETURN")
	)
	private void apikaprobe$reevalTickerOnEditorChange(UUID editor, CallbackInfo ci) {
		BlockEntity self = (BlockEntity) (Object) this;
		World world = self.getWorld();
		if (!(world instanceof ServerWorld)) {
			return;
		}
		BlockPos pos = self.getPos();
		Chunk chunk = world.getWorldChunk(pos);
		if (chunk instanceof WorldChunk worldChunk) {
			((WorldChunkInvoker) worldChunk).apikaprobe$updateTicker(self);
		}
	}
}
