package me.apika.apikaprobe.mixin;

import org.spongepowered.asm.mixin.Mixin;

import net.minecraft.world.level.block.entity.HopperBlockEntity;

/**
 * BROKEN ON 26.1.2 — needs redesign.
 *
 * <p>Per-slot hopper feature uses Yarn {@code HopperBlockEntity.transfer(
 * Container, Container, ItemStack, int slot, Direction)}, which mojmap
 * 26.1.2 replaced with {@code addItem(Container from, Container to,
 * ItemStack stack, Direction dir)} — the slot parameter was removed.
 *
 * <p>Per-slot logic relied on the slot index to track which inventory
 * slot fed which destination.  Without it, the feature needs a different
 * approach (e.g. computing the slot from the ItemStack identity match).
 *
 * <p>Was default-off (PerSlotFireConfig.ENABLE).  Stubbed to keep the
 * mixin file in tree while build moves forward.
 */
@Mixin(HopperBlockEntity.class)
public abstract class HopperPerSlotChainFeedMixin {
}
