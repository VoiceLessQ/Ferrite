package me.apika.apikaprobe.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.redstone.RedstoneWireEvaluator;
import net.minecraft.world.level.Level;

/**
 * Public accessors for RedstoneWireEvaluator's protected power-math
 * helpers. Lets [RedstoneOracle] call vanilla's own implementations of
 * calculateWirePowerAt and getStrongPowerAt — so the oracle's
 * correctness test inherits vanilla's exact up-step/down-step rules
 * rather than reimplementing them (and risking divergence on every
 * Mojang patch).
 */
@Mixin(RedstoneWireEvaluator.class)
public interface RedstoneControllerInvoker {

	@Invoker("calculateWirePowerAt")
	int apikaprobe$calculateWirePowerAt(Level world, BlockPos pos);

	@Invoker("getStrongPowerAt")
	int apikaprobe$getStrongPowerAt(Level world, BlockPos pos);
}
