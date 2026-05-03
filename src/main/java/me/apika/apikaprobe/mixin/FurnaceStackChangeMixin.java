package me.apika.apikaprobe.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import net.minecraft.block.entity.AbstractFurnaceBlockEntity;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import net.minecraft.world.chunk.Chunk;
import net.minecraft.world.chunk.WorldChunk;

/**
 * Re-evaluates ticker registration whenever the furnace's inventory
 * changes. Pairs with {@link WorldChunkFurnaceTickerMixin}: that mixin
 * gates whether a ticker should exist, this one triggers the gate to
 * be re-asked the moment a hopper inserts fuel or input (via
 * {@link AbstractFurnaceBlockEntity#setStack(int, ItemStack)}).
 *
 * <p>Idempotent: calling {@code updateTicker} when nothing changed
 * costs one HashMap lookup plus a {@code getBlockEntityTicker}
 * dispatch. Cheaper than diffing slot state in this mixin.
 *
 * <p>Server-side only. Client furnaces don't tick through this path.
 *
 * <p>{@code setStack} fires for all three vanilla subclasses since the
 * method is inherited from {@code AbstractFurnaceBlockEntity} without
 * override. Mod subclasses that override setStack and call super get
 * the same behavior; mod subclasses that bypass setStack entirely
 * never fire this mixin (acceptable: those mods aren't gated by
 * {@link WorldChunkFurnaceTickerMixin} either, since the strict-class
 * check there only suppresses vanilla types).
 */
@Mixin(AbstractFurnaceBlockEntity.class)
public abstract class FurnaceStackChangeMixin {

	@Inject(
		method = "setStack(ILnet/minecraft/item/ItemStack;)V",
		at = @At("RETURN")
	)
	private void apikaprobe$reevalTickerOnStackChange(int slot, ItemStack stack, CallbackInfo ci) {
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
