package me.apika.apikaprobe.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import me.apika.apikaprobe.redstone.RedstoneOracle;

import net.minecraft.block.BlockState;
import net.minecraft.block.RedstoneWireBlock;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import net.minecraft.world.block.WireOrientation;

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
@Mixin(RedstoneWireBlock.class)
public abstract class RedstoneOracleMixin {

	@Inject(
		method = "update(Lnet/minecraft/world/World;Lnet/minecraft/util/math/BlockPos;Lnet/minecraft/block/BlockState;Lnet/minecraft/world/block/WireOrientation;Z)V",
		at = @At("HEAD")
	)
	private void apikaprobe$oracleWireBegin(
			World world, BlockPos pos, BlockState state, WireOrientation orientation, boolean blockAdded,
			CallbackInfo ci) {
		RedstoneOracle.onWireUpdateBegin(world, pos, state, orientation, blockAdded);
	}

	@Inject(
		method = "update(Lnet/minecraft/world/World;Lnet/minecraft/util/math/BlockPos;Lnet/minecraft/block/BlockState;Lnet/minecraft/world/block/WireOrientation;Z)V",
		at = @At("RETURN")
	)
	private void apikaprobe$oracleWireEnd(
			World world, BlockPos pos, BlockState state, WireOrientation orientation, boolean blockAdded,
			CallbackInfo ci) {
		RedstoneOracle.onWireUpdateEnd(world, pos);
	}
}
