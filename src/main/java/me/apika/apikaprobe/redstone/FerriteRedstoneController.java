/*
 * Ferrite-side controller that routes vanilla's wire-update calls
 * through the Alternate-Current-derived WireHandler in this package.
 *
 * WireHandler algorithm is adapted from Alternate Current (MIT,
 * © 2022 Space Walker). This controller class is Ferrite-side glue.
 */
package me.apika.apikaprobe.redstone;

import java.util.Map;
import java.util.WeakHashMap;

import net.minecraft.world.level.block.BlockState;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.RedStoneWireBlock;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.redstone.VanillaRedstoneWireEvaluator;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.redstone.Orientation;

import org.jspecify.annotations.Nullable;

/**
 * Extends {@link VanillaRedstoneWireEvaluator} so that the
 * {@link RedStoneWireBlock#redstoneController} field — which the
 * installation mixin intercepts via {@code @Redirect(value = NEW)} —
 * gets a drop-in replacement that can either delegate to AC's
 * {@link WireHandler} (when {@link FerriteWireConfig#ENABLED}) or
 * fall through to the inherited vanilla {@code super.update(...)}.
 *
 * <p>Per-world handler map: redstone cascades hold mutable per-cascade
 * state ({@code nodes}, {@code updates}, {@code updating} flag), so
 * each {@link ServerLevel} gets its own {@link WireHandler}. The map
 * uses {@link WeakHashMap} keys so an unloaded / GC'd world doesn't
 * pin the handler in memory.
 */
public class FerriteRedstoneController extends VanillaRedstoneWireEvaluator {

	/** One handler per world. Weak keys so unloaded worlds don't leak. */
	private final Map<ServerLevel, WireHandler> handlers = new WeakHashMap<>();

	public FerriteRedstoneController(RedStoneWireBlock wire) {
		super(wire);
	}

	@Override
	public void update(Level world, BlockPos pos, BlockState state, @Nullable Orientation orientation, boolean blockAdded) {
		// Client-side, non-server, or A/B flag off — defer to vanilla.
		if (world.isClient() || !(world instanceof ServerLevel serverWorld) || !FerriteWireConfig.ENABLED) {
			super.update(world, pos, state, orientation, blockAdded);
			return;
		}

		WireHandler handler = handlers.computeIfAbsent(serverWorld, WireHandler::new);

		// Three-way dispatch matching AC's entry points:
		//   blockAdded == true       → wire was placed
		//   world state is a wire    → wire received a neighbor update
		//   world state isn't a wire → wire was removed (state param is the dying state)
		if (blockAdded) {
			handler.onWireAdded(pos, state);
		} else if (serverWorld.getBlockState(pos).isOf(Blocks.REDSTONE_WIRE)) {
			handler.onWireUpdated(pos, state, orientation);
		} else {
			handler.onWireRemoved(pos, state);
		}
	}
}
