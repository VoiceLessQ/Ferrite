package me.apika.apikaprobe.mixin.client;

import net.minecraft.client.gui.components.tabs.GridLayoutTab;
import net.minecraft.client.gui.layouts.GridLayout;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Exposes the protected {@code layout} field of GridLayoutTab so we
 *  can append rows from outside the package. Used by MoreTabPregenMixin. */
@Mixin(GridLayoutTab.class)
public interface GridLayoutTabAccessor {
	@Accessor("layout")
	GridLayout ferrite$getLayout();
}
