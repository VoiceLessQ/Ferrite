package me.apika.apikaprobe.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.entity.BlockEntityTicker;
import net.minecraft.world.level.block.entity.HangingSignBlockEntity;
import net.minecraft.world.level.block.entity.SignBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;

/**
 * Suppresses the per-tick ticker registration for sign block entities
 * that have no active editor. Vanilla registers a ticker for every
 * sign at chunk load, then ticks each one to do nothing 99.99% of
 * the time (the body is one null check on the playerWhoMayEdit field).
 *
 * <p>This redirect intercepts the BlockState.getTicker call inside
 * LevelChunk.updateBlockEntityTicker and returns null when the BE is
 * a vanilla SignBlockEntity or HangingSignBlockEntity with
 * getPlayerWhoMayEdit() == null. Vanilla's existing
 * if (ticker == null) removeBlockEntityTicker(pos) branch then
 * handles deregistration.
 *
 * <p>The strict-class guard (getClass() == ... .class) limits
 * suppression to vanilla sign types. Mod subclasses with non-trivial
 * tick bodies keep their tickers.
 *
 * <p>Re-registration when a player starts editing is driven by
 * SignEditorChangeMixin, which calls back into the chunk's
 * updateBlockEntityTicker the moment the editor field changes.
 */
@Mixin(LevelChunk.class)
public abstract class WorldChunkSignTickerMixin {

	@Redirect(
		method = "updateBlockEntityTicker(Lnet/minecraft/world/level/block/entity/BlockEntity;)V",
		at = @At(
			value = "INVOKE",
			target = "Lnet/minecraft/world/level/block/state/BlockState;getTicker(Lnet/minecraft/world/level/Level;Lnet/minecraft/world/level/block/entity/BlockEntityType;)Lnet/minecraft/world/level/block/entity/BlockEntityTicker;"
		)
	)
	private <T extends BlockEntity> BlockEntityTicker<T> apikaprobe$gateSignTicker(
			BlockState state, Level world, BlockEntityType<T> type, T blockEntity) {
		Class<?> beClass = blockEntity.getClass();
		if ((beClass == SignBlockEntity.class || beClass == HangingSignBlockEntity.class)
				&& ((SignBlockEntity) blockEntity).getPlayerWhoMayEdit() == null) {
			return null;
		}
		return state.getTicker(world, type);
	}
}
