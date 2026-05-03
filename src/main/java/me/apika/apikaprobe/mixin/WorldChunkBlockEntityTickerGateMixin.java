package me.apika.apikaprobe.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.AbstractFurnaceBlockEntity;
import net.minecraft.world.level.block.entity.BlastFurnaceBlockEntity;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.entity.BlockEntityTicker;
import net.minecraft.world.level.block.entity.FurnaceBlockEntity;
import net.minecraft.world.level.block.entity.HangingSignBlockEntity;
import net.minecraft.world.level.block.entity.SignBlockEntity;
import net.minecraft.world.level.block.entity.SmokerBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;

/**
 * Single composite gate that suppresses per-tick ticker registration
 * for vanilla block entities whose tick body is provably no-op given
 * their current state.
 *
 * <p>Mixin's @Redirect only allows one handler per INVOKE site, so all
 * "is this BE doing useful work this tick?" decisions for
 * LevelChunk.updateBlockEntityTicker live here, gated by strict
 * exact-class checks per family.
 *
 * <p>Vanilla's existing if (ticker == null) removeBlockEntityTicker(...)
 * branch handles deregistration when we return null. Re-registration
 * when state changes is driven by per-family hooks:
 * SignEditorChangeMixin (signs) and FurnaceStackChangeMixin (furnaces)
 * call back into the chunk's updateBlockEntityTicker the moment the
 * gate condition flips.
 *
 * <p>Strict-class checks restrict suppression to the exact vanilla
 * types. Mod subclasses with non-trivial tick bodies retain their
 * tickers untouched.
 *
 * <h3>Gates</h3>
 *
 * <ul>
 *   <li><b>SignBlockEntity, HangingSignBlockEntity</b> suppressed when
 *       getPlayerWhoMayEdit() == null (no player editing). The tick
 *       body is a single null check on the editor field; useful only
 *       while a player has the edit screen open.</li>
 *   <li><b>FurnaceBlockEntity, BlastFurnaceBlockEntity,
 *       SmokerBlockEntity</b> suppressed when litTimeRemaining == 0,
 *       cookingTimer == 0, slot 0 (input) empty, slot 1 (fuel) empty.
 *       Output slot is intentionally not part of the check, since
 *       accumulated finished items don't drive ticking, only fuel and
 *       input do.</li>
 * </ul>
 *
 * <p>Add new gates as additional if (beClass == ...) branches inside
 * the redirect handler. Each gate is independent.
 */
@Mixin(LevelChunk.class)
public abstract class WorldChunkBlockEntityTickerGateMixin {

	@Redirect(
		method = "updateBlockEntityTicker(Lnet/minecraft/world/level/block/entity/BlockEntity;)V",
		at = @At(
			value = "INVOKE",
			target = "Lnet/minecraft/world/level/block/state/BlockState;getTicker(Lnet/minecraft/world/level/Level;Lnet/minecraft/world/level/block/entity/BlockEntityType;)Lnet/minecraft/world/level/block/entity/BlockEntityTicker;"
		)
	)
	private <T extends BlockEntity> BlockEntityTicker<T> apikaprobe$gateBlockEntityTicker(
			BlockState state, Level world, BlockEntityType<T> type, T blockEntity) {
		Class<?> beClass = blockEntity.getClass();

		// --- Sign gate ---------------------------------------------------------
		if (beClass == SignBlockEntity.class || beClass == HangingSignBlockEntity.class) {
			if (((SignBlockEntity) blockEntity).getPlayerWhoMayEdit() == null) {
				return null;
			}
			return state.getTicker(world, type);
		}

		// --- Furnace gate ------------------------------------------------------
		if (beClass == FurnaceBlockEntity.class
				|| beClass == BlastFurnaceBlockEntity.class
				|| beClass == SmokerBlockEntity.class) {
			AbstractFurnaceBlockEntity furnace = (AbstractFurnaceBlockEntity) blockEntity;
			AbstractFurnaceBlockEntityAccessor acc = (AbstractFurnaceBlockEntityAccessor) furnace;
			if (acc.apikaprobe$getLitTimeRemaining() == 0
					&& acc.apikaprobe$getCookingTimer() == 0
					&& furnace.getItem(0).isEmpty()
					&& furnace.getItem(1).isEmpty()) {
				return null;
			}
			return state.getTicker(world, type);
		}

		return state.getTicker(world, type);
	}
}
