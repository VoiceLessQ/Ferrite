package me.apika.apikaprobe.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import net.minecraft.world.level.block.entity.AbstractFurnaceBlockEntity;

/**
 * Exposes the package-private idle-state fields on
 * AbstractFurnaceBlockEntity so the unified ticker gate mixin can
 * decide whether the BE is doing useful work this tick. The fields
 * live in the vanilla class but aren't public; vanilla's own
 * isBurning() is also private. We replicate its logic
 * (litTimeRemaining > 0) via the accessor instead of an Invoker for
 * simplicity.
 *
 * <p>26.1.2 mojmap names: litTimeRemaining unchanged; cookingTimer
 * is the field that yarn called cookingTimeSpent. NBT key
 * "cooking_time_spent" preserved for save compat.
 */
@Mixin(AbstractFurnaceBlockEntity.class)
public interface AbstractFurnaceBlockEntityAccessor {

	@Accessor("litTimeRemaining")
	int apikaprobe$getLitTimeRemaining();

	@Accessor("cookingTimer")
	int apikaprobe$getCookingTimer();
}
