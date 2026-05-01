package me.apika.apikaprobe.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
import org.spongepowered.asm.mixin.gen.Invoker;

import net.minecraft.world.level.block.BlockState;
import net.minecraft.world.level.block.entity.Hopper;
import net.minecraft.world.level.block.entity.HopperBlockEntity;
import net.minecraft.world.Container;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.Level;

@Mixin(HopperBlockEntity.class)
public interface HopperBlockEntityInvoker {

	@Invoker("getInputInventory")
	static Container ferrite$invokeGetInputInventory(Level world, Hopper hopper, BlockPos pos, BlockState state) {
		throw new AssertionError();
	}

	@Invoker("getAvailableSlots")
	static int[] ferrite$invokeGetAvailableSlots(Container inventory, Direction side) {
		throw new AssertionError();
	}

	@Invoker("extract")
	static boolean ferrite$invokeExtractFromSlot(Hopper hopper, Container inventory, int slot, Direction side) {
		throw new AssertionError();
	}

	@Invoker("insert")
	static boolean ferrite$invokeInsert(Level world, BlockPos pos, HopperBlockEntity blockEntity) {
		throw new AssertionError();
	}

	@Invoker("transfer")
	static net.minecraft.world.item.ItemStack ferrite$invokeTransferToSlot(
		net.minecraft.world.Container from,
		net.minecraft.world.Container to,
		net.minecraft.world.item.ItemStack stack,
		int slot,
		net.minecraft.core.Direction side
	) {
		throw new AssertionError();
	}

	@Accessor("lastTickTime")
	long ferrite$getLastTickTime();
}
