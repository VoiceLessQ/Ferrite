package me.apika.apikaprobe.mixin;

import org.spongepowered.asm.mixin.Mixin;

import net.minecraft.world.CompoundContainer;

/**
 * BROKEN ON 26.1.2 — needs redesign.
 * Part of the per-slot Hopper Highway diagnostic infrastructure
 * (default-off via PerSlotFireConfig.ENABLE).  Stubbed pending redesign.
 */
@Mixin(CompoundContainer.class)
public abstract class DoubleInventoryExtractHintMixin {
}
