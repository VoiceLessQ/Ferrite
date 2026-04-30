package me.apika.apikaprobe.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import me.apika.apikaprobe.monitor.HopperSlotMonitor;

import net.minecraft.block.entity.Hopper;
import net.minecraft.block.entity.HopperBlockEntity;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;

@Mixin(HopperBlockEntity.class)
public abstract class HopperSlotProbeMixin {

	@Inject(
		method = "insert(Lnet/minecraft/world/World;Lnet/minecraft/util/math/BlockPos;Lnet/minecraft/block/entity/HopperBlockEntity;)Z",
		at = @At("HEAD")
	)
	private static void ferrite$insertBegin(World world, BlockPos pos, HopperBlockEntity blockEntity, CallbackInfoReturnable<Boolean> cir) {
		HopperSlotMonitor.onInsertBegin();
	}

	@Inject(
		method = "insert(Lnet/minecraft/world/World;Lnet/minecraft/util/math/BlockPos;Lnet/minecraft/block/entity/HopperBlockEntity;)Z",
		at = @At(
			value = "INVOKE",
			target = "Lnet/minecraft/block/entity/HopperBlockEntity;transfer(Lnet/minecraft/inventory/Inventory;Lnet/minecraft/inventory/Inventory;Lnet/minecraft/item/ItemStack;Lnet/minecraft/util/math/Direction;)Lnet/minecraft/item/ItemStack;"
		)
	)
	private static void ferrite$insertAttempt(World world, BlockPos pos, HopperBlockEntity blockEntity, CallbackInfoReturnable<Boolean> cir) {
		HopperSlotMonitor.onInsertAttempt();
	}

	@Inject(
		method = "insert(Lnet/minecraft/world/World;Lnet/minecraft/util/math/BlockPos;Lnet/minecraft/block/entity/HopperBlockEntity;)Z",
		at = @At("RETURN")
	)
	private static void ferrite$insertEnd(World world, BlockPos pos, HopperBlockEntity blockEntity, CallbackInfoReturnable<Boolean> cir) {
		HopperSlotMonitor.onInsertEnd(cir.getReturnValueZ());
	}

	@Inject(
		method = "extract(Lnet/minecraft/world/World;Lnet/minecraft/block/entity/Hopper;)Z",
		at = @At("HEAD")
	)
	private static void ferrite$extractBegin(World world, Hopper hopper, CallbackInfoReturnable<Boolean> cir) {
		HopperSlotMonitor.onExtractBegin();
	}

	@Inject(
		method = "extract(Lnet/minecraft/world/World;Lnet/minecraft/block/entity/Hopper;)Z",
		at = @At(
			value = "INVOKE",
			target = "Lnet/minecraft/block/entity/HopperBlockEntity;extract(Lnet/minecraft/block/entity/Hopper;Lnet/minecraft/inventory/Inventory;ILnet/minecraft/util/math/Direction;)Z"
		)
	)
	private static void ferrite$extractAttempt(World world, Hopper hopper, CallbackInfoReturnable<Boolean> cir) {
		HopperSlotMonitor.onExtractAttempt();
	}

	@Inject(
		method = "extract(Lnet/minecraft/world/World;Lnet/minecraft/block/entity/Hopper;)Z",
		at = @At("RETURN")
	)
	private static void ferrite$extractEnd(World world, Hopper hopper, CallbackInfoReturnable<Boolean> cir) {
		HopperSlotMonitor.onExtractEnd(cir.getReturnValueZ());
	}
}
