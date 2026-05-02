package me.apika.apikaprobe.mixin;

import org.spongepowered.asm.mixin.Mixin;

import net.minecraft.world.level.block.entity.HopperBlockEntity;

/**
 * BROKEN ON 26.1.2 — needs redesign.
 *
 * <p>Part of the per-slot Hopper Highway diagnostic infrastructure.
 * Targeted Yarn methods that were renamed and/or had their signatures
 * changed in mojmap 26.1.2 (transfer slot-arg removed, getInputInventory
 * / getAvailableSlots / extract / insert renames, etc.).  All default-off
 * via {@code PerSlotFireConfig.ENABLE}.  Stubbed to empty mixin to keep
 * the file in tree while build moves forward.
 */
@Mixin(HopperBlockEntity.class)
public abstract class HopperExtractHintMaintainMixin {
}
