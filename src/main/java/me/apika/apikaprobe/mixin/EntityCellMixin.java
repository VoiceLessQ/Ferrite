package me.apika.apikaprobe.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;

import net.minecraft.world.entity.Entity;

import me.apika.apikaprobe.spatial.CellHolder;

/** Carries the cached packed block position for the spatial-query filter. */
@Mixin(Entity.class)
public abstract class EntityCellMixin implements CellHolder {
	@Unique private long ferrite$packedPos = Long.MIN_VALUE;

	@Override
	public long ferrite$packedPos() {
		return ferrite$packedPos;
	}

	@Override
	public void ferrite$setPackedPos(long packed) {
		ferrite$packedPos = packed;
	}
}
