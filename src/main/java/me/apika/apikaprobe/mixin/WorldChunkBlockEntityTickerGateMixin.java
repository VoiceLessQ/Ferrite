package me.apika.apikaprobe.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

import net.minecraft.block.BlockState;
import net.minecraft.block.entity.AbstractFurnaceBlockEntity;
import net.minecraft.block.entity.BlastFurnaceBlockEntity;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.block.entity.BlockEntityType;
import net.minecraft.block.entity.BlockEntityTicker;
import net.minecraft.block.entity.FurnaceBlockEntity;
import net.minecraft.block.entity.HangingSignBlockEntity;
import net.minecraft.block.entity.SignBlockEntity;
import net.minecraft.block.entity.SmokerBlockEntity;
import net.minecraft.world.World;
import net.minecraft.world.chunk.WorldChunk;

/**
 * Single composite gate that suppresses per-tick ticker registration
 * for vanilla block entities whose tick body is provably no-op given
 * their current state.
 *
 * <p>Mixin's {@code @Redirect} only allows one handler per INVOKE
 * site, so all "is this BE doing useful work this tick?" decisions
 * for {@code WorldChunk.updateTicker} live here, gated by strict
 * exact-class checks per family.
 *
 * <p>Vanilla's existing {@code if (ticker == null) removeBlockEntity-
 * Ticker(...)} branch handles deregistration when we return null.
 * Re-registration when state changes is driven by per-family hooks:
 * {@link SignEditorChangeMixin} (signs) and
 * {@link FurnaceStackChangeMixin} (furnaces) call back into the
 * chunk's {@code updateTicker} the moment the gate condition flips.
 *
 * <p>Strict-class checks restrict suppression to the exact vanilla
 * types. Mod subclasses with non-trivial tick bodies retain their
 * tickers untouched.
 *
 * <h3>Gates</h3>
 *
 * <ul>
 *   <li><b>{@link SignBlockEntity}, {@link HangingSignBlockEntity}</b>
 *       suppressed when {@code editor == null} (no player editing).
 *       The tick body is a single null check on the editor field;
 *       useful only while a player has the edit screen open.</li>
 *   <li><b>{@link FurnaceBlockEntity}, {@link BlastFurnaceBlockEntity},
 *       {@link SmokerBlockEntity}</b> suppressed when
 *       {@code litTimeRemaining == 0}, {@code cookingTimeSpent == 0},
 *       slot 0 (input) empty, slot 1 (fuel) empty. Output slot is
 *       intentionally not part of the check, since accumulated
 *       finished items don't drive ticking, only fuel/input do.</li>
 * </ul>
 *
 * <p>Add new gates as additional {@code if (beClass == ...)} branches
 * inside the redirect handler. Each gate is independent.
 */
@Mixin(WorldChunk.class)
public abstract class WorldChunkBlockEntityTickerGateMixin {

	@Redirect(
		method = "updateTicker(Lnet/minecraft/block/entity/BlockEntity;)V",
		at = @At(
			value = "INVOKE",
			target = "Lnet/minecraft/block/BlockState;getBlockEntityTicker(Lnet/minecraft/world/World;Lnet/minecraft/block/entity/BlockEntityType;)Lnet/minecraft/block/entity/BlockEntityTicker;"
		)
	)
	private <T extends BlockEntity> BlockEntityTicker<T> apikaprobe$gateBlockEntityTicker(
			BlockState state, World world, BlockEntityType<T> type, T blockEntity) {
		Class<?> beClass = blockEntity.getClass();

		// --- Sign gate ---------------------------------------------------------
		if (beClass == SignBlockEntity.class || beClass == HangingSignBlockEntity.class) {
			if (((SignBlockEntity) blockEntity).getEditor() == null) {
				return null;
			}
			return state.getBlockEntityTicker(world, type);
		}

		// --- Furnace gate ------------------------------------------------------
		if (beClass == FurnaceBlockEntity.class
				|| beClass == BlastFurnaceBlockEntity.class
				|| beClass == SmokerBlockEntity.class) {
			AbstractFurnaceBlockEntity furnace = (AbstractFurnaceBlockEntity) blockEntity;
			AbstractFurnaceBlockEntityAccessor acc = (AbstractFurnaceBlockEntityAccessor) furnace;
			if (acc.apikaprobe$getLitTimeRemaining() == 0
					&& acc.apikaprobe$getCookingTimeSpent() == 0
					&& furnace.getStack(0).isEmpty()
					&& furnace.getStack(1).isEmpty()) {
				return null;
			}
			return state.getBlockEntityTicker(world, type);
		}

		return state.getBlockEntityTicker(world, type);
	}
}
