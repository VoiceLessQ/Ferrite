package me.apika.apikaprobe.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

import me.apika.apikaprobe.redstone.RedstoneHandoff;
import me.apika.apikaprobe.redstone.RedstoneRustDispatcher;

import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.RedStoneWireBlock;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.redstone.RedstoneWireEvaluator;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.redstone.Orientation;

/**
 * A/B switch: redirects the {@code this.redstoneController.update(...)}
 * call inside RedStoneWireBlock's private dispatcher (line 275 in yarn
 * 1.21.11) to [RedstoneRustDispatcher].
 *
 * Why @Redirect instead of @Inject(HEAD, cancellable=true):
 * The previous @Inject approach let vanilla's controller update fire
 * in every case — the cancel flag wasn't preventing the INVOKE at line
 * 275 from executing. @Redirect wholly replaces that specific INVOKE
 * instruction, so when USE_RUST is active we simply skip the vanilla
 * controller call and run Rust's BFS instead. Zero reliance on
 * cancel-flag propagation.
 *
 * Falls back to vanilla in three cases:
 *   1. USE_RUST = false (default) — switch is off
 *   2. client side — server-side only
 *   3. Rust dispatcher is already ACTIVE — re-entry during our own
 *      setBlockState/updateNeighbors apply pass; Rust has already
 *      written the correct power, vanilla's re-evaluation would be
 *      redundant. (We still call vanilla here so non-wire consumers
 *      see consistent notifier semantics — but see note below.)
 *   4. runBfsAndApply returned false (network overflow, native
 *      unavailable) — bail cleanly to vanilla.
 *
 * Note on re-entry: during apply, the ACTIVE branch calls vanilla.
 * That's safe because by the time ACTIVE is set, every wire in the
 * network is already at its correct power — vanilla's controller
 * recomputes the same answer, sees no state change, skips its own
 * setBlockState, and terminates its recursion immediately. The cost
 * is ~O(N) vanilla-update calls during our apply pass, each cheap;
 * vanilla's own explosive cascade doesn't fire because nothing is
 * changing.
 */
@Mixin(RedStoneWireBlock.class)
public abstract class RedstoneRustMixin {

	@Redirect(
		method = "update(Lnet/minecraft/world/level/Level;Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;Lnet/minecraft/world/level/redstone/Orientation;Z)V",
		at = @At(
			value = "INVOKE",
			target = "Lnet/minecraft/world/level/redstone/RedstoneWireEvaluator;update(Lnet/minecraft/world/level/Level;Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;Lnet/minecraft/world/level/redstone/Orientation;Z)V"
		)
	)
	private void apikaprobe$redirectControllerUpdate(
			RedstoneWireEvaluator controller,
			Level world, BlockPos pos, BlockState state, Orientation orientation, boolean blockAdded) {
		if (!RedstoneHandoff.USE_RUST || world.isClientSide() || RedstoneRustDispatcher.isActive()) {
			controller.update(world, pos, state, orientation, blockAdded);
			return;
		}
		if (!RedstoneRustDispatcher.runBfsAndApply((ServerLevel) world, pos)) {
			// Rust bailed (overflow / native missing) — fall back so the
			// wire still gets updated.
			controller.update(world, pos, state, orientation, blockAdded);
		}
	}
}
