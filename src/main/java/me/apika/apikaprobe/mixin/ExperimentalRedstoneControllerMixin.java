package me.apika.apikaprobe.mixin;

import java.util.Collections;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import me.apika.apikaprobe.monitor.RedstonePhaseMonitor;
import me.apika.apikaprobe.redstone.FerriteWireConfig;

import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.redstone.ExperimentalRedstoneWireEvaluator;
import net.minecraft.world.level.redstone.Orientation;

/**
 * Per-call counter for the experimental (Mojang-optimized) redstone
 * controller. Counterpart to [DefaultRedstoneControllerMixin] — the
 * pair lets the 5s log line show `default=X exp=Y` so every test
 * session self-documents which controller is active.
 *
 * <p>Also surfaces a once-per-world WARN when the user enabled AC via
 * {@code /ferrite redstone ac on} but the world has the experimental
 * redstone feature flag set. Vanilla's {@code RedstoneWireBlock.update}
 * routes to {@code new ExperimentalRedstoneController(...).update(...)}
 * before reaching {@code this.redstoneController.update(...)} on
 * experimental worlds, so {@link FerriteRedstoneController} never sees
 * the cascade and AC is silently bypassed. The warning fires here, at
 * the only point we can prove an experimental cascade actually ran on
 * a world the user wanted AC on.
 */
@Mixin(ExperimentalRedstoneWireEvaluator.class)
public abstract class ExperimentalRedstoneControllerMixin {

	private static final Logger LOGGER = LoggerFactory.getLogger("ferrite");

	private static final Set<ResourceKey<Level>> WARNED_WORLDS =
			Collections.newSetFromMap(new ConcurrentHashMap<>());

	@Inject(
		method = "updatePowerStrength(Lnet/minecraft/world/level/Level;Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;Lnet/minecraft/world/level/redstone/Orientation;Z)V",
		at = @At("HEAD")
	)
	private void apikaprobe$onExperimentalControllerUpdate(
			Level world, BlockPos pos, BlockState state, Orientation orientation, boolean blockAdded,
			CallbackInfo ci) {
		if (world.isClientSide()) return;
		RedstonePhaseMonitor.onExperimentalController();

		if (FerriteWireConfig.ENABLED) {
			ResourceKey<Level> key = world.dimension();
			if (WARNED_WORLDS.add(key)) {
				LOGGER.warn("[ferrite] redstone: world '{}' uses experimental redstone, AC is enabled but bypassed on this world. Use /ferrite redstone ac off to suppress this warning.",
						key.identifier());
			}
		}
	}
}
