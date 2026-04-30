package me.apika.apikaprobe.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import org.spongepowered.asm.mixin.injection.callback.LocalCapture;

import me.apika.apikaprobe.hopper.ExtractHint;
import me.apika.apikaprobe.monitor.HopperHintMonitor;

import net.minecraft.block.BlockState;
import net.minecraft.block.entity.Hopper;
import net.minecraft.block.entity.HopperBlockEntity;
import net.minecraft.inventory.Inventory;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.world.World;

@Mixin(HopperBlockEntity.class)
public abstract class HopperHintExtractRouteMixin {

	@Inject(
		method = "extract(Lnet/minecraft/world/World;Lnet/minecraft/block/entity/Hopper;)Z",
		at = @At(
			value = "INVOKE_ASSIGN",
			target = "Lnet/minecraft/block/entity/HopperBlockEntity;getInputInventory(Lnet/minecraft/world/World;Lnet/minecraft/block/entity/Hopper;Lnet/minecraft/util/math/BlockPos;Lnet/minecraft/block/BlockState;)Lnet/minecraft/inventory/Inventory;"
		),
		locals = LocalCapture.CAPTURE_FAILHARD,
		cancellable = true
	)
	private static void ferrite$hintedExtract(
		World world, Hopper hopper, CallbackInfoReturnable<Boolean> cir,
		BlockPos blockPos, BlockState blockState, Inventory inventory
	) {
		if (!HopperHintMonitor.USE_HINT) return;
		if (inventory == null || !(inventory instanceof ExtractHint h)) {
			HopperHintMonitor.onNoInventory();
			return;
		}

		long t0 = System.nanoTime();
		Direction direction = Direction.DOWN;
		int[] slots = HopperBlockEntityInvoker.ferrite$invokeGetAvailableSlots(inventory, direction);

		int hint = h.ferrite$getExtractHint();
		if (hint < 0 || hint >= slots.length) hint = 0;

		for (int idx = hint; idx < slots.length; idx++) {
			if (HopperBlockEntityInvoker.ferrite$invokeExtractFromSlot(hopper, inventory, slots[idx], direction)) {
				HopperHintMonitor.onHit(idx, System.nanoTime() - t0);
				cir.setReturnValue(true);
				return;
			}
		}
		for (int idx = 0; idx < hint; idx++) {
			if (HopperBlockEntityInvoker.ferrite$invokeExtractFromSlot(hopper, inventory, slots[idx], direction)) {
				HopperHintMonitor.onWrap(System.nanoTime() - t0);
				cir.setReturnValue(true);
				return;
			}
		}
		HopperHintMonitor.onAllFail(System.nanoTime() - t0);
		cir.setReturnValue(false);
	}
}
