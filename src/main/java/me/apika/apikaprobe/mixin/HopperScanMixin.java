package me.apika.apikaprobe.mixin;

import java.util.List;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import me.apika.apikaprobe.monitor.HopperMonitor;

import net.minecraft.block.BlockState;
import net.minecraft.block.entity.HopperBlockEntity;
import net.minecraft.entity.ItemEntity;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;

@Mixin(HopperBlockEntity.class)
public abstract class HopperScanMixin {

	@Inject(method = "serverTick", at = @At("HEAD"))
	private static void ferrite$hopperTick(World world, BlockPos pos, BlockState state, HopperBlockEntity blockEntity, CallbackInfo ci) {
		HopperMonitor.onHopperTick();
	}

	@Inject(method = "getInputItemEntities", at = @At("HEAD"))
	private static void ferrite$scanBegin(World world, net.minecraft.block.entity.Hopper hopper, CallbackInfoReturnable<List<ItemEntity>> cir) {
		HopperMonitor.onScanBegin();
	}

	@Inject(method = "getInputItemEntities", at = @At("RETURN"))
	private static void ferrite$scanEnd(World world, net.minecraft.block.entity.Hopper hopper, CallbackInfoReturnable<List<ItemEntity>> cir) {
		HopperMonitor.onScanEnd(cir.getReturnValue().size());
	}
}
