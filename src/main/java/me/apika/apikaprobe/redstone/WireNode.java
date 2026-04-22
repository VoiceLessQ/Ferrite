/*
 * Adapted from Alternate Current (https://github.com/SpaceWalkerRS/alternate-current)
 * Copyright (c) 2022 Space Walker — MIT License
 *
 * Yarn-remapped for Fabric 1.21.11. Key yarn renames:
 *   net.minecraft.core.BlockPos          -> net.minecraft.util.math.BlockPos
 *   net.minecraft.server.level.ServerLevel -> net.minecraft.server.world.ServerWorld
 *   net.minecraft.util.Mth               -> net.minecraft.util.math.MathHelper
 *   net.minecraft.world.level.block.*    -> net.minecraft.block.*
 *   RedStoneWireBlock                    -> RedstoneWireBlock
 *   state.is(Blocks.X)                   -> state.isOf(Blocks.X)
 *   state.getValue / setValue            -> state.get / with
 *   Redstone.SIGNAL_MIN / MAX            -> WireConstants.SIGNAL_MIN / MAX (inlined 0 / 15)
 *   Block.UPDATE_CLIENTS                 -> Block.NOTIFY_LISTENERS
 *   Block.dropResources                  -> Block.dropStacks
 *   level.setBlock                       -> world.setBlockState
 */
package me.apika.apikaprobe.redstone;

import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.RedstoneWireBlock;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.MathHelper;

/**
 * A {@link Node} that represents a redstone wire in the world. Extends
 * {@link Node} with all the per-wire state the update algorithm needs
 * to calculate power changes without repeated world queries:
 *
 * <ul>
 *   <li>{@link #currentPower} — last power level written to the world.</li>
 *   <li>{@link #virtualPower} — tentative power during a cascade, may
 *       not equal the world's value until the cascade completes.</li>
 *   <li>{@link #externalPower} — contribution from non-wire sources
 *       (torches, repeaters, levers, redstone blocks) read once per
 *       cascade so the inner loop doesn't hit vanilla APIs repeatedly.</li>
 *   <li>{@link #flowIn} / {@link #iFlowDir} — directional bookkeeping
 *       for the power-flow pruning that makes AC's algorithm O(N).</li>
 * </ul>
 *
 * @author Space Walker (original, Mojmap)
 */
public class WireNode extends Node {

	final WireConnectionManager connections;

	/** The power level this wire currently holds in the world. */
	int currentPower;
	/**
	 * During a cascade, the power level this wire <em>should</em> have;
	 * may differ from {@link #currentPower} until the cascade commits.
	 */
	int virtualPower;
	/** Power level received from non-wire components. */
	int externalPower;
	/**
	 * 4-bit mask tracking which cardinal directions contributed to this
	 * wire's current {@code virtualPower}, i.e. where power flowed in from.
	 */
	int flowIn;
	/** Resolved incoming-flow direction index, derived from {@link #flowIn}. */
	int iFlowDir;
	boolean added;
	boolean removed;
	boolean shouldBreak;
	boolean root;
	boolean discovered;
	boolean searched;

	/** Next wire in the {@link SimpleQueue} linked list. */
	WireNode next_wire;

	/**
	 * Per-cascade slot index used by {@link WireHandler#runRustBatch}.
	 * Set to the wire's position in the serialized request buffer
	 * during the drain pass; read by neighbor-resolution to avoid a
	 * per-cascade {@code HashMap<Long, Integer>} (boxing-heavy).
	 * Reset is implicit — the next batch overwrites it.
	 */
	int rustIndex = -1;

	WireNode(ServerWorld world, BlockPos pos, BlockState state) {
		super(world);

		this.pos = pos.toImmutable();
		this.state = state;

		this.connections = new WireConnectionManager(this);

		this.virtualPower = this.currentPower = this.state.get(RedstoneWireBlock.POWER);
		this.priority = priority();
	}

	@Override
	Node set(BlockPos pos, BlockState state, boolean clearNeighbors) {
		throw new UnsupportedOperationException("Cannot update a WireNode!");
	}

	@Override
	int priority() {
		return MathHelper.clamp(virtualPower, WireConstants.SIGNAL_MIN, WireConstants.SIGNAL_MAX);
	}

	@Override
	public boolean isWire() {
		return true;
	}

	@Override
	public WireNode asWire() {
		return this;
	}

	/**
	 * Offer a power level from a specific incoming direction. If the
	 * offered power is higher than the current virtual power, adopt it
	 * and reset the flow-in mask to only this direction; if equal, OR
	 * the flow-in mask; if lower, ignore.
	 *
	 * @return true if the offer changed this wire's virtual power.
	 */
	boolean offerPower(int power, int iDir) {
		if (removed || shouldBreak) {
			return false;
		}
		if (power == virtualPower) {
			flowIn |= (1 << iDir);
			return false;
		}
		if (power > virtualPower) {
			virtualPower = power;
			flowIn = (1 << iDir);
			return true;
		}
		return false;
	}

	/**
	 * Commit this wire's resolved power to the world. Handles the
	 * special case where the wire should be broken (e.g. its support
	 * block vanished) by dropping items and replacing with air.
	 *
	 * @return true if the world state was modified.
	 */
	boolean setPower() {
		if (removed) {
			return true;
		}

		state = world.getBlockState(pos);

		if (!state.isOf(Blocks.REDSTONE_WIRE)) {
			return false; // should never get here
		}

		if (shouldBreak) {
			Block.dropStacks(state, world, pos);
			world.setBlockState(pos, Blocks.AIR.getDefaultState(), Block.NOTIFY_LISTENERS);
			return true;
		}

		currentPower = MathHelper.clamp(virtualPower, WireConstants.SIGNAL_MIN, WireConstants.SIGNAL_MAX);
		state = state.with(RedstoneWireBlock.POWER, currentPower);

		return LevelHelper.setWireState(world, pos, state, added);
	}
}
