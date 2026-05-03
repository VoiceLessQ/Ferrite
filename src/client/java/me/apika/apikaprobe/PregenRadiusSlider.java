package me.apika.apikaprobe;

import me.apika.apikaprobe.worldgen.chunk.PregenWorldOptions;

import net.minecraft.client.gui.components.AbstractSliderButton;
import net.minecraft.network.chat.Component;

/**
 * Radius slider for the Create World "More" tab pre-gen row. Stores its
 * value into {@link PregenWorldOptions} so the screen mixin and the
 * SERVER_STARTED handler can read it.
 */
public final class PregenRadiusSlider extends AbstractSliderButton {
	private static final int RANGE = PregenWorldOptions.RADIUS_MAX - PregenWorldOptions.RADIUS_MIN;

	public PregenRadiusSlider(int x, int y, int width, int height, int initialRadius) {
		super(x, y, width, height, Component.empty(), normalize(initialRadius));
		this.updateMessage();
	}

	private static double normalize(int radius) {
		int clamped = Math.max(PregenWorldOptions.RADIUS_MIN,
				Math.min(PregenWorldOptions.RADIUS_MAX, radius));
		return (double) (clamped - PregenWorldOptions.RADIUS_MIN) / RANGE;
	}

	private int currentRadius() {
		return PregenWorldOptions.RADIUS_MIN + (int) Math.round(this.value * RANGE);
	}

	@Override
	protected void updateMessage() {
		this.setMessage(Component.literal("Radius: " + currentRadius() + " chunks"));
	}

	@Override
	protected void applyValue() {
		PregenWorldOptions.setUiRadius(currentRadius());
	}
}
