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

import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.RedstoneWireBlock;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.DefaultRedstoneController;
import net.minecraft.world.World;
import net.minecraft.world.block.WireOrientation;

import org.jspecify.annotations.Nullable;

/**
 * Extends {@link DefaultRedstoneController} so that the
 * {@link RedstoneWireBlock#redstoneController} field — which the
 * installation mixin intercepts via {@code @Redirect(value = NEW)} —
 * gets a drop-in replacement that can either delegate to AC's
 * {@link WireHandler} (when {@link FerriteWireConfig#ENABLED}) or
 * fall through to the inherited vanilla {@code super.update(...)}.
 *
 * <p>Per-world handler map: redstone cascades hold mutable per-cascade
 * state ({@code nodes}, {@code updates}, {@code updating} flag), so
 * each {@link ServerWorld} gets its own {@link WireHandler}. The map
 * uses {@link WeakHashMap} keys so an unloaded / GC'd world doesn't
 * pin the handler in memory.
 */
public class FerriteRedstoneController extends DefaultRedstoneController {

	/** One handler per world. Weak keys so unloaded worlds don't leak. */
	private final Map<ServerWorld, WireHandler> handlers = new WeakHashMap<>();

	public FerriteRedstoneController(RedstoneWireBlock wire) {
		super(wire);
	}

	@Override
	public void update(World world, BlockPos pos, BlockState state, @Nullable WireOrientation orientation, boolean blockAdded) {
		// Client-side, non-server, or A/B flag off — defer to vanilla.
		if (world.isClient() || !(world instanceof ServerWorld serverWorld) || !FerriteWireConfig.ENABLED) {
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
