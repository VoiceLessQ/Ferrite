package me.apika.apikaprobe.mixin;

import java.util.List;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import me.apika.apikaprobe.monitor.HopperMonitor;

import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.entity.HopperBlockEntity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;

@Mixin(HopperBlockEntity.class)
public abstract class HopperScanMixin {

	@Inject(method = "serverTick", at = @At("HEAD"))
	private static void ferrite$hopperTick(Level world, BlockPos pos, BlockState state, HopperBlockEntity blockEntity, CallbackInfo ci) {
		HopperMonitor.onHopperTick();
	}

	@Inject(method = "getInputItemEntities", at = @At("HEAD"))
	private static void ferrite$scanBegin(Level world, net.minecraft.world.level.block.entity.Hopper hopper, CallbackInfoReturnable<List<ItemEntity>> cir) {
		HopperMonitor.onScanBegin();
	}

	@Inject(method = "getInputItemEntities", at = @At("RETURN"))
	private static void ferrite$scanEnd(Level world, net.minecraft.world.level.block.entity.Hopper hopper, CallbackInfoReturnable<List<ItemEntity>> cir) {
		HopperMonitor.onScanEnd(cir.getReturnValue().size());
	}
}
