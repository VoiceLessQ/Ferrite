package me.apika.apikaprobe.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

import me.apika.apikaprobe.redstone.FerriteRedstoneController;

import net.minecraft.world.level.block.RedStoneWireBlock;
import net.minecraft.world.level.redstone.DefaultRedstoneWireEvaluator;

/**
 * Swaps {@link RedStoneWireBlock}'s {@code redstoneController} field
 * from {@link DefaultRedstoneWireEvaluator} to Ferrite's subclass at
 * block-class construction time. The field is initialized inline as:
 *
 * <pre>{@code
 *   private final RedstoneWireEvaluator redstoneController = new DefaultRedstoneWireEvaluator(this);
 * }</pre>
 *
 * We redirect the {@code NEW DefaultRedstoneWireEvaluator} expression to
 * return a {@link FerriteRedstoneController} instead. Because the
 * Ferrite controller extends {@code DefaultRedstoneWireEvaluator}, the
 * returned instance is type-compatible with the field, and virtual
 * dispatch on {@code update(...)} runs the Ferrite logic (which
 * internally gates on {@link me.apika.apikaprobe.redstone.FerriteWireConfig#ENABLED}
 * and falls back to {@code super.update(...)} when AC is disabled).
 */
@Mixin(RedStoneWireBlock.class)
public abstract class FerriteControllerInstallMixin {

	@Redirect(
		method = "<init>(Lnet/minecraft/world/level/block/state/BlockBehaviour$Properties;)V",
		at = @At(
			value = "NEW",
			target = "(Lnet/minecraft/world/level/block/RedStoneWireBlock;)Lnet/minecraft/world/level/redstone/DefaultRedstoneWireEvaluator;"
		)
	)
	private DefaultRedstoneWireEvaluator apikaprobe$installFerriteController(RedStoneWireBlock wire) {
		return new FerriteRedstoneController(wire);
	}
}
