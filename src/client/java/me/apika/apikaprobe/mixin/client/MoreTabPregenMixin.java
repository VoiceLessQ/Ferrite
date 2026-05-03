package me.apika.apikaprobe.mixin.client;

import me.apika.apikaprobe.PregenRadiusSlider;
import me.apika.apikaprobe.worldgen.chunk.PregenWorldOptions;

import net.minecraft.client.gui.components.CycleButton;
import net.minecraft.client.gui.layouts.GridLayout;
import net.minecraft.client.gui.layouts.LayoutSettings;
import net.minecraft.client.gui.screens.worldselection.CreateWorldScreen;
import net.minecraft.network.chat.Component;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Append a pre-gen toggle + radius slider to the MoreTab on Create World.
 *
 * <p>Vanilla MoreTab adds three rows in its constructor (Game Rules,
 * Experiments, Data Packs) at indices 0..2. We append our two rows at
 * indices 3..4. If a future MC version adds another vanilla row at
 * index 3, the indices here need bumping; this is the price of not
 * having a "give me the next free row" API on GridLayout.
 */
@Mixin(targets = "net/minecraft/client/gui/screens/worldselection/CreateWorldScreen$MoreTab")
public abstract class MoreTabPregenMixin {
	private static final int PREGEN_ROW_TOGGLE = 3;
	private static final int PREGEN_ROW_SLIDER = 4;
	private static final int ROW_WIDTH = 210;
	private static final int ROW_HEIGHT = 20;

	@Inject(method = "<init>", at = @At("TAIL"))
	private void ferrite$addPregenRows(CreateWorldScreen screen, CallbackInfo ci) {
		GridLayout grid = ((GridLayoutTabAccessor) (Object) this).ferrite$getLayout();

		CycleButton<Boolean> toggle = CycleButton
				.onOffBuilder(PregenWorldOptions.isUiEnabled())
				.create(0, 0, ROW_WIDTH, ROW_HEIGHT,
						Component.literal("Pre-generate spawn area"),
						(button, value) -> PregenWorldOptions.setUiEnabled(value));

		PregenRadiusSlider slider = new PregenRadiusSlider(0, 0, ROW_WIDTH, ROW_HEIGHT,
				PregenWorldOptions.getUiRadius());

		LayoutSettings centered = LayoutSettings.defaults().alignHorizontallyCenter();
		grid.addChild(toggle, PREGEN_ROW_TOGGLE, 0, 1, 1, centered);
		grid.addChild(slider, PREGEN_ROW_SLIDER, 0, 1, 1, centered);
	}
}
