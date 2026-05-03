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

import net.minecraft.block.BlockState;
import net.minecraft.registry.RegistryKey;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.ExperimentalRedstoneController;
import net.minecraft.world.World;
import net.minecraft.world.block.WireOrientation;

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
@Mixin(ExperimentalRedstoneController.class)
public abstract class ExperimentalRedstoneControllerMixin {

	private static final Logger LOGGER = LoggerFactory.getLogger("ferrite");

	private static final Set<RegistryKey<World>> WARNED_WORLDS =
			Collections.newSetFromMap(new ConcurrentHashMap<>());

	@Inject(
		method = "update(Lnet/minecraft/world/World;Lnet/minecraft/util/math/BlockPos;Lnet/minecraft/block/BlockState;Lnet/minecraft/world/block/WireOrientation;Z)V",
		at = @At("HEAD")
	)
	private void apikaprobe$onExperimentalControllerUpdate(
			World world, BlockPos pos, BlockState state, WireOrientation orientation, boolean blockAdded,
			CallbackInfo ci) {
		if (world.isClient()) return;
		RedstonePhaseMonitor.onExperimentalController();

		if (FerriteWireConfig.ENABLED) {
			RegistryKey<World> key = world.getRegistryKey();
			if (WARNED_WORLDS.add(key)) {
				LOGGER.warn("[ferrite] redstone: world '{}' uses experimental redstone, AC is enabled but bypassed on this world. Use /ferrite redstone ac off to suppress this warning.",
						key.getValue());
			}
		}
	}
}
