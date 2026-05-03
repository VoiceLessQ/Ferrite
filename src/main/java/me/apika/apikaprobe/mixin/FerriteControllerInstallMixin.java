package me.apika.apikaprobe.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

import me.apika.apikaprobe.redstone.FerriteRedstoneController;

import net.minecraft.block.RedstoneWireBlock;
import net.minecraft.world.DefaultRedstoneController;

/**
 * Swaps {@link RedstoneWireBlock}'s {@code redstoneController} field
 * from {@link DefaultRedstoneController} to Ferrite's subclass at
 * block-class construction time. The field is initialized inline as:
 *
 * <pre>{@code
 *   private final RedstoneController redstoneController = new DefaultRedstoneController(this);
 * }</pre>
 *
 * We redirect the {@code NEW DefaultRedstoneController} expression to
 * return a {@link FerriteRedstoneController} instead. Because the
 * Ferrite controller extends {@code DefaultRedstoneController}, the
 * returned instance is type-compatible with the field, and virtual
 * dispatch on {@code update(...)} runs the Ferrite logic (which
 * internally gates on {@link me.apika.apikaprobe.redstone.FerriteWireConfig#ENABLED}
 * and falls back to {@code super.update(...)} when AC is disabled).
 */
@Mixin(RedstoneWireBlock.class)
public abstract class FerriteControllerInstallMixin {

	@Redirect(
		method = "<init>(Lnet/minecraft/block/AbstractBlock$Settings;)V",
		at = @At(
			value = "NEW",
			target = "(Lnet/minecraft/block/RedstoneWireBlock;)Lnet/minecraft/world/DefaultRedstoneController;"
		)
	)
	private DefaultRedstoneController apikaprobe$installFerriteController(RedstoneWireBlock wire) {
		return new FerriteRedstoneController(wire);
	}
}
