package me.apika.apikaprobe.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import me.apika.apikaprobe.redstone.RedstoneOracle;

import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.RedStoneWireBlock;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.redstone.Orientation;

/**
 * Feeds every wire update into [RedstoneOracle] for pre- and post-write
 * inspection. Lives alongside [RedstoneWireMixin] — same target method,
 * separate concern: RedstoneWireMixin times cascades, this one captures
 * inputs/outputs for correctness shadow-compute.
 *
 * Two mixins on the same private method work fine — both get @Inject
 * HEAD/RETURN handlers stitched in by the mixin processor. Neither
 * modifies behavior.
 */
@Mixin(RedStoneWireBlock.class)
public abstract class RedstoneOracleMixin {

	@Inject(
		method = "update(Lnet/minecraft/world/level/Level;Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;Lnet/minecraft/world/level/redstone/Orientation;Z)V",
		at = @At("HEAD")
	)
	private void apikaprobe$oracleWireBegin(
			Level world, BlockPos pos, BlockState state, Orientation orientation, boolean blockAdded,
			CallbackInfo ci) {
		RedstoneOracle.onWireUpdateBegin(world, pos, state, orientation, blockAdded);
	}

	@Inject(
		method = "update(Lnet/minecraft/world/level/Level;Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;Lnet/minecraft/world/level/redstone/Orientation;Z)V",
		at = @At("RETURN")
	)
	private void apikaprobe$oracleWireEnd(
			Level world, BlockPos pos, BlockState state, Orientation orientation, boolean blockAdded,
			CallbackInfo ci) {
		RedstoneOracle.onWireUpdateEnd(world, pos);
	}
}
