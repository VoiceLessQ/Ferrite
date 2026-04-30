package me.apika.apikaprobe.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import me.apika.apikaprobe.hopper.PerSlotFireConfig;
import me.apika.apikaprobe.hopper.SlotCooldownAccess;

import net.minecraft.block.entity.HopperBlockEntity;
import net.minecraft.inventory.Inventory;
import net.minecraft.item.ItemStack;
import net.minecraft.util.math.Direction;

@Mixin(HopperBlockEntity.class)
public abstract class HopperPerSlotChainFeedMixin {

	@Inject(
		method = "transfer(Lnet/minecraft/inventory/Inventory;Lnet/minecraft/inventory/Inventory;Lnet/minecraft/item/ItemStack;ILnet/minecraft/util/math/Direction;)Lnet/minecraft/item/ItemStack;",
		at = @At(
			value = "INVOKE",
			target = "Lnet/minecraft/block/entity/HopperBlockEntity;setTransferCooldown(I)V",
			shift = At.Shift.AFTER
		)
	)
	private static void ferrite$perSlotChainFeed(
		Inventory from, Inventory to, ItemStack stack, int slot, Direction side,
		CallbackInfoReturnable<ItemStack> cir
	) {
		if (!PerSlotFireConfig.ENABLE) return;
		if (!(to instanceof HopperBlockEntity recv)) return;
		if (!(to instanceof SlotCooldownAccess access)) return;

		int j = 0;
		if (from instanceof HopperBlockEntity from2) {
			long recvT = ((HopperBlockEntityInvoker) (Object) recv).ferrite$getLastTickTime();
			long fromT = ((HopperBlockEntityInvoker) (Object) from2).ferrite$getLastTickTime();
			if (recvT >= fromT) j = 1;
		}
		access.ferrite$setSlotCooldown(slot, 8 - j);
	}
}
