package me.apika.apikaprobe.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import me.apika.apikaprobe.navigation.NavigationCacheBridge;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Hooks the 4-arg {@code Level.setBlock} (the actual implementation;
 * the 3-arg overload trivially delegates to it). HEAD inject reads the
 * current state at the position before the change is applied, then
 * dispatches to {@link NavigationCacheBridge}. Server-side only.
 */
@Mixin(Level.class)
public abstract class LevelSetBlockMixin {

	@Inject(
		method = "setBlock(Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;II)Z",
		at = @At("HEAD")
	)
	private void ferrite$onSetBlockBegin(
		BlockPos pos, BlockState newState, int updateFlags, int updateLimit,
		CallbackInfoReturnable<Boolean> cir
	) {
		Level self = (Level) (Object) this;
		if (self.isClientSide()) return;
		BlockState oldState = self.getBlockState(pos);
		if (oldState == newState) return;
		NavigationCacheBridge.onBlockChanged(pos, oldState, newState);
	}
}
