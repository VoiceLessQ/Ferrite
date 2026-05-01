package me.apika.apikaprobe.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import me.apika.apikaprobe.hopper.HopperLaneRouteConfig;
import me.apika.apikaprobe.hopper.SlotCooldownAccess;
import me.apika.apikaprobe.monitor.HopperPerSlotMonitor;

import net.minecraft.world.level.block.entity.HopperBlockEntity;
import net.minecraft.world.Container;
import net.minecraft.world.item.ItemStack;
import net.minecraft.core.Direction;

@Mixin(HopperBlockEntity.class)
public abstract class HopperLaneRouteMixin {

	@Inject(
		method = "transfer(Lnet/minecraft/world/Container;Lnet/minecraft/world/Container;Lnet/minecraft/world/item/ItemStack;Lnet/minecraft/core/Direction;)Lnet/minecraft/world/item/ItemStack;",
		at = @At("HEAD"),
		cancellable = true
	)
	private static void ferrite$roundRobinDestRoute(
		Container from, Container to, ItemStack stack, Direction side,
		CallbackInfoReturnable<ItemStack> cir
	) {
		if (!HopperLaneRouteConfig.ENABLE) return;
		if (!(to instanceof HopperBlockEntity)) return;
		if (!(to instanceof SlotCooldownAccess access)) return;

		int n = to.size();
		int last = access.ferrite$getLastInsertSlot();
		int startAt = ((last + 1) % n + n) % n;

		ItemStack remaining = stack;
		int landedSlot = -1;

		for (int i = 0; i < n && !remaining.isEmpty(); i++) {
			int slot = (startAt + i) % n;
			int preCount = remaining.getCount();
			remaining = HopperBlockEntityInvoker.ferrite$invokeTransferToSlot(from, to, remaining, slot, side);
			if (remaining.getCount() < preCount && landedSlot == -1) {
				landedSlot = slot;
			}
		}

		if (landedSlot != -1) {
			access.ferrite$setLastInsertSlot(landedSlot);
			if (landedSlot == startAt) {
				HopperPerSlotMonitor.onLaneHit();
			} else {
				HopperPerSlotMonitor.onLaneFallback();
			}
		}

		cir.setReturnValue(remaining);
	}
}
