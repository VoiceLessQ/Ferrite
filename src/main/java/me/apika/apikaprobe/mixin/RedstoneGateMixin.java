package me.apika.apikaprobe.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import me.apika.apikaprobe.RedstonePhaseMonitor;

import net.minecraft.block.AbstractRedstoneGateBlock;
import net.minecraft.block.BlockState;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.random.Random;

/**
 * Times every scheduledTick on repeaters and comparators.
 *
 * AbstractRedstoneGateBlock is the abstract parent of RepeaterBlock and
 * ComparatorBlock; mixing there catches both. Torches (RedstoneTorchBlock)
 * are intentionally out of scope for this pass — add later if gate data
 * shows signal.
 *
 * No recursion concern: a gate's scheduledTick does not re-enter its own
 * scheduledTick within the same call stack. Straight HEAD/RETURN timing.
 *
 * Already server-thread only (ServerWorld parameter), no client filter
 * needed.
 */
@Mixin(AbstractRedstoneGateBlock.class)
public abstract class RedstoneGateMixin {

	@Inject(
		method = "scheduledTick(Lnet/minecraft/block/BlockState;Lnet/minecraft/server/world/ServerWorld;Lnet/minecraft/util/math/BlockPos;Lnet/minecraft/util/math/random/Random;)V",
		at = @At("HEAD")
	)
	private void apikaprobe$onGateScheduledTickBegin(
			BlockState state, ServerWorld world, BlockPos pos, Random random,
			CallbackInfo ci) {
		RedstonePhaseMonitor.onGateBegin();
	}

	@Inject(
		method = "scheduledTick(Lnet/minecraft/block/BlockState;Lnet/minecraft/server/world/ServerWorld;Lnet/minecraft/util/math/BlockPos;Lnet/minecraft/util/math/random/Random;)V",
		at = @At("RETURN")
	)
	private void apikaprobe$onGateScheduledTickEnd(
			BlockState state, ServerWorld world, BlockPos pos, Random random,
			CallbackInfo ci) {
		RedstonePhaseMonitor.onGateEnd();
	}
}
