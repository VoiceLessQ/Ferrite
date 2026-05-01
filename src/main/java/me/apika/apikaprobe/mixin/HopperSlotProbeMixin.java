package me.apika.apikaprobe.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import me.apika.apikaprobe.monitor.HopperSlotMonitor;

import net.minecraft.world.level.block.entity.Hopper;
import net.minecraft.world.level.block.entity.HopperBlockEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;

@Mixin(HopperBlockEntity.class)
public abstract class HopperSlotProbeMixin {

	@Inject(
		method = "insert(Lnet/minecraft/world/level/Level;Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/entity/HopperBlockEntity;)Z",
		at = @At("HEAD")
	)
	private static void ferrite$insertBegin(Level world, BlockPos pos, HopperBlockEntity blockEntity, CallbackInfoReturnable<Boolean> cir) {
		HopperSlotMonitor.onInsertBegin();
	}

	@Inject(
		method = "insert(Lnet/minecraft/world/level/Level;Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/entity/HopperBlockEntity;)Z",
		at = @At(
			value = "INVOKE",
			target = "Lnet/minecraft/world/level/block/entity/HopperBlockEntity;transfer(Lnet/minecraft/world/Container;Lnet/minecraft/world/Container;Lnet/minecraft/world/item/ItemStack;Lnet/minecraft/core/Direction;)Lnet/minecraft/world/item/ItemStack;"
		)
	)
	private static void ferrite$insertAttempt(Level world, BlockPos pos, HopperBlockEntity blockEntity, CallbackInfoReturnable<Boolean> cir) {
		HopperSlotMonitor.onInsertAttempt();
	}

	@Inject(
		method = "insert(Lnet/minecraft/world/level/Level;Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/entity/HopperBlockEntity;)Z",
		at = @At("RETURN")
	)
	private static void ferrite$insertEnd(Level world, BlockPos pos, HopperBlockEntity blockEntity, CallbackInfoReturnable<Boolean> cir) {
		HopperSlotMonitor.onInsertEnd(cir.getReturnValueZ());
	}

	@Inject(
		method = "extract(Lnet/minecraft/world/level/Level;Lnet/minecraft/world/level/block/entity/Hopper;)Z",
		at = @At("HEAD")
	)
	private static void ferrite$extractBegin(Level world, Hopper hopper, CallbackInfoReturnable<Boolean> cir) {
		HopperSlotMonitor.onExtractBegin();
	}

	@Inject(
		method = "extract(Lnet/minecraft/world/level/Level;Lnet/minecraft/world/level/block/entity/Hopper;)Z",
		at = @At(
			value = "INVOKE",
			target = "Lnet/minecraft/world/level/block/entity/HopperBlockEntity;extract(Lnet/minecraft/world/level/block/entity/Hopper;Lnet/minecraft/world/Container;ILnet/minecraft/core/Direction;)Z"
		)
	)
	private static void ferrite$extractAttempt(Level world, Hopper hopper, CallbackInfoReturnable<Boolean> cir) {
		HopperSlotMonitor.onExtractAttempt();
	}

	@Inject(
		method = "extract(Lnet/minecraft/world/level/Level;Lnet/minecraft/world/level/block/entity/Hopper;)Z",
		at = @At("RETURN")
	)
	private static void ferrite$extractEnd(Level world, Hopper hopper, CallbackInfoReturnable<Boolean> cir) {
		HopperSlotMonitor.onExtractEnd(cir.getReturnValueZ());
	}
}
