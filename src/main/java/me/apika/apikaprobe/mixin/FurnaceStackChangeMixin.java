package me.apika.apikaprobe.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.AbstractFurnaceBlockEntity;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.chunk.LevelChunk;

/**
 * Re-evaluates ticker registration whenever the furnace's inventory
 * changes. Pairs with the unified ticker gate mixin: that mixin
 * gates whether a ticker should exist, this one triggers the gate
 * to be re-asked the moment a hopper inserts fuel or input (via
 * AbstractFurnaceBlockEntity.setItem(int, ItemStack)).
 *
 * <p>Idempotent: calling updateBlockEntityTicker when nothing
 * changed costs one HashMap lookup plus a getTicker dispatch.
 * Cheaper than diffing slot state in this mixin.
 *
 * <p>Server-side only. Client furnaces don't tick through this path.
 *
 * <p>setItem fires for all three vanilla subclasses since the method
 * is inherited from AbstractFurnaceBlockEntity without override. Mod
 * subclasses that override setItem and call super get the same
 * behavior; mod subclasses that bypass setItem entirely never fire
 * this mixin (acceptable: those mods aren't gated either, since the
 * strict-class check there only suppresses vanilla types).
 */
@Mixin(AbstractFurnaceBlockEntity.class)
public abstract class FurnaceStackChangeMixin {

	@Inject(
		method = "setItem(ILnet/minecraft/world/item/ItemStack;)V",
		at = @At("RETURN")
	)
	private void apikaprobe$reevalTickerOnStackChange(int slot, ItemStack stack, CallbackInfo ci) {
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
