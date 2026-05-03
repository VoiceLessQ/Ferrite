package me.apika.apikaprobe.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import net.minecraft.block.entity.AbstractFurnaceBlockEntity;

/**
 * Exposes the package-private idle-state fields on
 * {@link AbstractFurnaceBlockEntity} so {@link WorldChunkFurnaceTickerMixin}
 * can decide whether the BE is doing useful work this tick. The fields
 * live in the vanilla class but aren't public; vanilla's own
 * {@code isBurning()} is also private. We replicate its logic
 * ({@code litTimeRemaining > 0}) via the accessor instead of an
 * Invoker for simplicity.
 */
@Mixin(AbstractFurnaceBlockEntity.class)
public interface AbstractFurnaceBlockEntityAccessor {

	@Accessor("litTimeRemaining")
	int apikaprobe$getLitTimeRemaining();

	@Accessor("cookingTimeSpent")
	int apikaprobe$getCookingTimeSpent();
}
