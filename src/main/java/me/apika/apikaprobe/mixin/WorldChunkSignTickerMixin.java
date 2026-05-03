package me.apika.apikaprobe.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

import net.minecraft.block.BlockState;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.block.entity.BlockEntityType;
import net.minecraft.block.entity.BlockEntityTicker;
import net.minecraft.block.entity.HangingSignBlockEntity;
import net.minecraft.block.entity.SignBlockEntity;
import net.minecraft.world.World;
import net.minecraft.world.chunk.WorldChunk;

/**
 * Suppresses the per-tick ticker registration for sign block entities
 * that have no active editor. Vanilla registers a ticker for every
 * sign at chunk load, then ticks each one to do nothing 99.99% of
 * the time (the body is one null check on the {@code editor} field).
 *
 * <p>This redirect intercepts the {@code BlockState.getBlockEntityTicker}
 * call inside {@link WorldChunk#updateTicker(BlockEntity)} and returns
 * {@code null} when the BE is a vanilla {@link SignBlockEntity} or
 * {@link HangingSignBlockEntity} with {@code editor == null}. Vanilla's
 * existing {@code if (ticker == null) removeBlockEntityTicker(pos)}
 * branch then handles deregistration.
 *
 * <p>The strict-class guard ({@code getClass() == ... .class}) limits
 * suppression to vanilla sign types. Mod subclasses with non-trivial
 * tick bodies keep their tickers.
 *
 * <p>Re-registration when a player starts editing is driven by
 * {@link SignEditorChangeMixin}, which calls back into the chunk's
 * {@code updateTicker} the moment the editor field changes.
 */
@Mixin(WorldChunk.class)
public abstract class WorldChunkSignTickerMixin {

	@Redirect(
		method = "updateTicker(Lnet/minecraft/block/entity/BlockEntity;)V",
		at = @At(
			value = "INVOKE",
			target = "Lnet/minecraft/block/BlockState;getBlockEntityTicker(Lnet/minecraft/world/World;Lnet/minecraft/block/entity/BlockEntityType;)Lnet/minecraft/block/entity/BlockEntityTicker;"
		)
	)
	private <T extends BlockEntity> BlockEntityTicker<T> apikaprobe$gateSignTicker(
			BlockState state, World world, BlockEntityType<T> type, T blockEntity) {
		Class<?> beClass = blockEntity.getClass();
		if ((beClass == SignBlockEntity.class || beClass == HangingSignBlockEntity.class)
				&& ((SignBlockEntity) blockEntity).getEditor() == null) {
			return null;
		}
		return state.getBlockEntityTicker(world, type);
	}
}
