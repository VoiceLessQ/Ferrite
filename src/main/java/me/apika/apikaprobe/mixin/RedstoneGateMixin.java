package me.apika.apikaprobe.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import me.apika.apikaprobe.monitor.RedstonePhaseMonitor;

import net.minecraft.world.level.block.DiodeBlock;
import net.minecraft.world.level.block.BlockState;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.util.RandomSource;

/**
 * Times every scheduledTick on repeaters and comparators.
 *
 * DiodeBlock is the abstract parent of RepeaterBlock and
 * ComparatorBlock; mixing there catches both. Torches (RedstoneTorchBlock)
 * are intentionally out of scope for this pass — add later if gate data
 * shows signal.
 *
 * No recursion concern: a gate's scheduledTick does not re-enter its own
 * scheduledTick within the same call stack. Straight HEAD/RETURN timing.
 *
 * Already server-thread only (ServerLevel parameter), no client filter
 * needed.
 */
@Mixin(DiodeBlock.class)
public abstract class RedstoneGateMixin {

	@Inject(
		method = "scheduledTick(Lnet.minecraft.world.level.block.BlockState;Lnet.minecraft.server.level.ServerLevel;Lnet.minecraft.core.BlockPos;Lnet.minecraft.util.RandomSource;)V",
		at = @At("HEAD")
	)
	private void apikaprobe$onGateScheduledTickBegin(
			BlockState state, ServerLevel world, BlockPos pos, RandomSource random,
			CallbackInfo ci) {
		RedstonePhaseMonitor.onGateBegin();
		RedstonePhaseMonitor.GATE_ACTIVE.get()[0] = true;
	}

	@Inject(
		method = "scheduledTick(Lnet.minecraft.world.level.block.BlockState;Lnet.minecraft.server.level.ServerLevel;Lnet.minecraft.core.BlockPos;Lnet.minecraft.util.RandomSource;)V",
		at = @At("RETURN")
	)
	private void apikaprobe$onGateScheduledTickEnd(
			BlockState state, ServerLevel world, BlockPos pos, RandomSource random,
			CallbackInfo ci) {
		RedstonePhaseMonitor.GATE_ACTIVE.get()[0] = false;
		RedstonePhaseMonitor.onGateEnd();
	}
}
