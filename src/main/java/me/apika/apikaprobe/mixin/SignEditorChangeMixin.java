package me.apika.apikaprobe.mixin;

import java.util.UUID;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.SignBlockEntity;
import net.minecraft.world.level.chunk.LevelChunk;

/**
 * Re-evaluates the ticker registration whenever a sign's
 * editor field changes. Pairs with the ticker gate mixin: that
 * mixin gates whether the ticker should exist, this one triggers
 * the gate to be re-asked.
 *
 * <p>updateBlockEntityTicker is idempotent, calling it when nothing
 * changed costs one HashMap lookup plus a getTicker dispatch.
 * Cheaper than diffing old vs new editor state in this mixin.
 *
 * <p>Server-side only. Client-side signs don't tick through this path.
 */
@Mixin(SignBlockEntity.class)
public abstract class SignEditorChangeMixin {

	@Inject(
		method = "setAllowedPlayerEditor(Ljava/util/UUID;)V",
		at = @At("RETURN")
	)
	private void apikaprobe$reevalTickerOnEditorChange(UUID editor, CallbackInfo ci) {
		BlockEntity self = (BlockEntity) (Object) this;
		Level level = self.getLevel();
		if (!(level instanceof ServerLevel)) {
			return;
		}
		BlockPos pos = self.getBlockPos();
		LevelChunk chunk = level.getChunkAt(pos);
		((WorldChunkInvoker) chunk).apikaprobe$updateBlockEntityTicker(self);
	}
}
