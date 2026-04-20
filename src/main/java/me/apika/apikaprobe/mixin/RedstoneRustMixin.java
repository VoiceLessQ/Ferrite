package me.apika.apikaprobe.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import me.apika.apikaprobe.RedstoneHandoff;
import me.apika.apikaprobe.RedstoneRustDispatcher;

import net.minecraft.block.BlockState;
import net.minecraft.block.RedstoneWireBlock;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import net.minecraft.world.block.WireOrientation;

/**
 * A/B switch: when [RedstoneHandoff.USE_RUST] is true, intercepts
 * RedstoneWireBlock.update at HEAD and routes the cascade through
 * [RedstoneRustDispatcher] (Rust BFS + batch apply) instead of
 * vanilla's per-call cascade.
 *
 * Interception conditions (all must hold):
 *   - USE_RUST flag set (default false)
 *   - server-side call (!world.isClient())
 *   - native library available (RustBridge.NATIVE_AVAILABLE)
 *   - not already inside a dispatcher pass (re-entry guard via
 *     RedstoneRustDispatcher.isActive)
 *
 * Any failure or bailout (e.g. network exceeds MAX_NODES) returns
 * control to vanilla silently — the wire update runs its normal path.
 *
 * Mixin ordering: this mixin sits alongside [RedstoneWireMixin] (phase
 * timing) and [RedstoneOracleMixin] (correctness shadow-check) on the
 * same target method. All three @Inject at HEAD in declaration order
 * in ferrite.mixins.json; RETURN handlers fire regardless of whether
 * an earlier HEAD handler cancelled. (If that assumption fails in
 * practice and the phase monitor's depth counter drifts, we'd need to
 * switch this mixin to @Redirect instead.)
 */
@Mixin(RedstoneWireBlock.class)
public abstract class RedstoneRustMixin {

	@Inject(
		method = "update(Lnet/minecraft/world/World;Lnet/minecraft/util/math/BlockPos;Lnet/minecraft/block/BlockState;Lnet/minecraft/world/block/WireOrientation;Z)V",
		at = @At("HEAD"),
		cancellable = true
	)
	private void apikaprobe$maybeRouteToRust(
			World world, BlockPos pos, BlockState state, WireOrientation orientation, boolean blockAdded,
			CallbackInfo ci) {
		if (!RedstoneHandoff.USE_RUST) return;
		if (world.isClient()) return;
		if (RedstoneRustDispatcher.isActive()) return;

		boolean handled = RedstoneRustDispatcher.runBfsAndApply((ServerWorld) world, pos);
		if (handled) {
			ci.cancel();
		}
	}
}
