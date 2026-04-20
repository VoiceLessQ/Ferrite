package me.apika.apikaprobe.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

import net.minecraft.util.math.BlockPos;
import net.minecraft.world.RedstoneController;
import net.minecraft.world.World;

/**
 * Public accessors for RedstoneController's protected power-math
 * helpers. Lets [RedstoneOracle] call vanilla's own implementations of
 * calculateWirePowerAt and getStrongPowerAt — so the oracle's
 * correctness test inherits vanilla's exact up-step/down-step rules
 * rather than reimplementing them (and risking divergence on every
 * Mojang patch).
 */
@Mixin(RedstoneController.class)
public interface RedstoneControllerInvoker {

	@Invoker("calculateWirePowerAt")
	int apikaprobe$calculateWirePowerAt(World world, BlockPos pos);

	@Invoker("getStrongPowerAt")
	int apikaprobe$getStrongPowerAt(World world, BlockPos pos);
}
