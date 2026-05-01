package me.apika.apikaprobe.mixin;

import java.util.function.BooleanSupplier;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import me.apika.apikaprobe.hopper.PerSlotFireConfig;
import me.apika.apikaprobe.hopper.SlotCooldownAccess;
import me.apika.apikaprobe.monitor.HopperPerSlotMonitor;

import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.HopperBlock;
import net.minecraft.world.level.block.entity.HopperBlockEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;

@Mixin(HopperBlockEntity.class)
public abstract class HopperPerSlotFireMixin {

	@Inject(
		method = "insertAndExtract(Lnet/minecraft/world/level/Level;Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;Lnet/minecraft/world/level/block/entity/HopperBlockEntity;Ljava/util/function/BooleanSupplier;)Z",
		at = @At("HEAD"),
		cancellable = true
	)
	private static void ferrite$perSlotFire(
		Level world, BlockPos pos, BlockState state, HopperBlockEntity blockEntity, BooleanSupplier extractSupplier,
		CallbackInfoReturnable<Boolean> cir
	) {
		if (!PerSlotFireConfig.ENABLE) return;

		if (world.isClientSide()) {
			cir.setReturnValue(false);
			return;
		}
		if (!(Boolean) state.getValue(HopperBlock.ENABLED)) {
			cir.setReturnValue(false);
			return;
		}

		SlotCooldownAccess access = (SlotCooldownAccess) (Object) blockEntity;
		int[] cd = access.ferrite$getSlotCooldowns();
		int pointer = access.ferrite$getRoundRobinPointer();

		int firedSlot = -1;
		for (int i = 0; i < cd.length; i++) {
			int slot = (pointer + i) % cd.length;
			if (cd[slot] <= 0) {
				firedSlot = slot;
				break;
			}
		}
		if (firedSlot == -1) {
			HopperPerSlotMonitor.onNoReadySlot();
			cir.setReturnValue(false);
			return;
		}

		int preTotalCount = PerSlotFireConfig.VALIDATE ? ferrite$totalItemCount(blockEntity) : 0;

		boolean bl = false;
		if (!blockEntity.isEmpty()) {
			bl = HopperBlockEntityInvoker.ferrite$invokeInsert(world, pos, blockEntity);
		}
		if (!ferrite$isFull(blockEntity)) {
			bl |= extractSupplier.getAsBoolean();
		}

		if (bl) {
			access.ferrite$setSlotCooldown(firedSlot, 8);
			HopperPerSlotMonitor.onFire(firedSlot, 1);

			long now = world.getGameTime();
			long[] lastFire = access.ferrite$getLastFireTick();
			long prev = lastFire[firedSlot];
			if (prev >= 0L) {
				HopperPerSlotMonitor.onSlotInterval(pos, firedSlot, now - prev);
			}
			lastFire[firedSlot] = now;

			if (PerSlotFireConfig.VALIDATE) {
				int postTotalCount = ferrite$totalItemCount(blockEntity);
				int delta = Math.abs(postTotalCount - preTotalCount);
				if (delta > 1) {
					HopperPerSlotMonitor.onTickViolation(pos, delta);
				}
				if (ferrite$staggerCollapsed(cd)) {
					HopperPerSlotMonitor.onStaggerCollapse(pos, cd.clone());
				}
			}
		}

		access.ferrite$setRoundRobinPointer((firedSlot + 1) % cd.length);
		cir.setReturnValue(bl);
	}

	private static int ferrite$totalItemCount(HopperBlockEntity be) {
		int total = 0;
		int n = be.getContainerSize();
		for (int i = 0; i < n; i++) {
			total += be.getItem(i).getCount();
		}
		return total;
	}

	private static boolean ferrite$isFull(HopperBlockEntity be) {
		int n = be.getContainerSize();
		for (int i = 0; i < n; i++) {
			net.minecraft.world.item.ItemStack s = be.getItem(i);
			if (s.isEmpty() || s.getCount() != s.getMaxStackSize()) return false;
		}
		return true;
	}

	private static boolean ferrite$staggerCollapsed(int[] cd) {
		int first = cd[0];
		for (int i = 1; i < cd.length; i++) {
			if (cd[i] != first) return false;
		}
		return true;
	}
}
