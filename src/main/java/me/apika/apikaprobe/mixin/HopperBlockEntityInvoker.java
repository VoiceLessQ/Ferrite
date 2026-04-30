package me.apika.apikaprobe.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
import org.spongepowered.asm.mixin.gen.Invoker;

import net.minecraft.block.BlockState;
import net.minecraft.block.entity.Hopper;
import net.minecraft.block.entity.HopperBlockEntity;
import net.minecraft.inventory.Inventory;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.world.World;

@Mixin(HopperBlockEntity.class)
public interface HopperBlockEntityInvoker {

	@Invoker("getInputInventory")
	static Inventory ferrite$invokeGetInputInventory(World world, Hopper hopper, BlockPos pos, BlockState state) {
		throw new AssertionError();
	}

	@Invoker("getAvailableSlots")
	static int[] ferrite$invokeGetAvailableSlots(Inventory inventory, Direction side) {
		throw new AssertionError();
	}

	@Invoker("extract")
	static boolean ferrite$invokeExtractFromSlot(Hopper hopper, Inventory inventory, int slot, Direction side) {
		throw new AssertionError();
	}

	@Invoker("insert")
	static boolean ferrite$invokeInsert(World world, BlockPos pos, HopperBlockEntity blockEntity) {
		throw new AssertionError();
	}

	@Invoker("transfer")
	static net.minecraft.item.ItemStack ferrite$invokeTransferToSlot(
		net.minecraft.inventory.Inventory from,
		net.minecraft.inventory.Inventory to,
		net.minecraft.item.ItemStack stack,
		int slot,
		net.minecraft.util.math.Direction side
	) {
		throw new AssertionError();
	}

	@Accessor("lastTickTime")
	long ferrite$getLastTickTime();
}
