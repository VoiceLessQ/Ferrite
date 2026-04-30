package me.apika.apikaprobe.mixin;

import java.util.Optional;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import me.apika.apikaprobe.hopper.SlotCooldownAccess;

import net.minecraft.block.BlockState;
import net.minecraft.block.entity.HopperBlockEntity;
import net.minecraft.storage.ReadView;
import net.minecraft.storage.WriteView;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;

@Mixin(HopperBlockEntity.class)
public abstract class HopperPerSlotCooldownMixin implements SlotCooldownAccess {

	@Unique
	private int[] ferrite$slotCooldowns = new int[]{0, 1, 2, 3, 4};

	@Unique
	private int ferrite$roundRobinPointer = 0;

	@Unique
	private int ferrite$lastInsertSlot = -1;

	@Unique
	private long[] ferrite$lastFireTick = new long[]{-1L, -1L, -1L, -1L, -1L};

	@Override
	public int ferrite$getSlotCooldown(int slot) {
		return this.ferrite$slotCooldowns[slot];
	}

	@Override
	public void ferrite$setSlotCooldown(int slot, int value) {
		this.ferrite$slotCooldowns[slot] = value;
	}

	@Override
	public int[] ferrite$getSlotCooldowns() {
		return this.ferrite$slotCooldowns;
	}

	@Override
	public int ferrite$getRoundRobinPointer() {
		return this.ferrite$roundRobinPointer;
	}

	@Override
	public void ferrite$setRoundRobinPointer(int pointer) {
		this.ferrite$roundRobinPointer = pointer;
	}

	@Override
	public int ferrite$getLastInsertSlot() {
		return this.ferrite$lastInsertSlot;
	}

	@Override
	public void ferrite$setLastInsertSlot(int slot) {
		this.ferrite$lastInsertSlot = slot;
	}

	@Override
	public long[] ferrite$getLastFireTick() {
		return this.ferrite$lastFireTick;
	}

	@Inject(
		method = "serverTick(Lnet/minecraft/world/World;Lnet/minecraft/util/math/BlockPos;Lnet/minecraft/block/BlockState;Lnet/minecraft/block/entity/HopperBlockEntity;)V",
		at = @At("HEAD")
	)
	private static void ferrite$decrementOnTick(World world, BlockPos pos, BlockState state, HopperBlockEntity blockEntity, CallbackInfo ci) {
		if (!me.apika.apikaprobe.hopper.PerSlotFireConfig.ENABLE) return;
		((SlotCooldownAccess) (Object) blockEntity).ferrite$decrementAllSlotCooldowns();
	}

	@Inject(method = "setTransferCooldown(I)V", at = @At("HEAD"))
	private void ferrite$broadcastOnSet(int transferCooldown, CallbackInfo ci) {
		if (me.apika.apikaprobe.hopper.PerSlotFireConfig.ENABLE) return;
		this.ferrite$broadcastCooldown(transferCooldown);
	}

	@Inject(method = "needsCooldown()Z", at = @At("HEAD"), cancellable = true)
	private void ferrite$needsCooldown(CallbackInfoReturnable<Boolean> cir) {
		cir.setReturnValue(this.ferrite$allSlotsOnCooldown());
	}

	@Inject(method = "readData(Lnet/minecraft/storage/ReadView;)V", at = @At("RETURN"))
	private void ferrite$readSlotCooldowns(ReadView view, CallbackInfo ci) {
		Optional<int[]> saved = view.getOptionalIntArray("FerriteSlotCooldowns");
		if (saved.isPresent() && saved.get().length == this.ferrite$slotCooldowns.length) {
			int[] src = saved.get();
			for (int i = 0; i < this.ferrite$slotCooldowns.length; i++) {
				this.ferrite$slotCooldowns[i] = src[i];
			}
		} else {
			int legacy = view.getInt("TransferCooldown", -1);
			this.ferrite$broadcastCooldown(legacy);
		}
	}

	@Inject(method = "writeData(Lnet/minecraft/storage/WriteView;)V", at = @At("RETURN"))
	private void ferrite$writeSlotCooldowns(WriteView view, CallbackInfo ci) {
		if (!me.apika.apikaprobe.hopper.PerSlotFireConfig.ENABLE) return;
		view.putIntArray("FerriteSlotCooldowns", this.ferrite$slotCooldowns.clone());
	}
}
