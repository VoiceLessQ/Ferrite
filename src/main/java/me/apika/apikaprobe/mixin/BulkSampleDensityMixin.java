package me.apika.apikaprobe.mixin;

import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.List;

import net.minecraft.world.level.levelgen.NoiseChunk;

import me.apika.apikaprobe.worldgen.BulkInterpolatorFill;

/**
 * Phase 2.5 step 2b — bulk-fill the slice arrays in
 * {@link NoiseChunk#sampleDensity}. Cancels the per-z-row ×
 * per-interpolator vanilla loop in favor of one Rust JNI call per
 * interpolator that fills all z-rows at once.
 *
 * <p>Gated by {@link BulkInterpolatorFill#ENABLED}; mixin is a no-op
 * when off so vanilla runs unmodified for parity validation. If any
 * interpolator's rustName isn't registered (shouldn't happen post step
 * 2a's 100% capture, but defensive), we let vanilla run that whole
 * chunk as a fallback.
 *
 * <p>The yarn target is {@code sampleDensity(boolean, int)} —
 * private method, called 9 times per chunk (1 start + 4 advances × 2).
 */
@Mixin(NoiseChunk.class)
public abstract class BulkSampleDensityMixin {

	@Shadow @Final
	private List<NoiseChunk.DensityInterpolator> interpolators;

	@Shadow @Final
	int horizontalCellBlockCount;

	@Shadow @Final
	int verticalCellBlockCount;

	@Shadow @Final
	int horizontalCellCount;

	@Shadow @Final
	int verticalCellCount;

	@Shadow @Final
	int minimumCellY;

	@Shadow @Final
	int startCellZ;

	@Inject(
			method = "sampleDensity",
			at = @At("HEAD"),
			cancellable = true
	)
	private void ferrite$bulkFillSlices(boolean start, int cellX, CallbackInfo ci) {
		if (!BulkInterpolatorFill.ENABLED) return;
		boolean filled = BulkInterpolatorFill.fillAllSlices(
				this.interpolators,
				start,
				cellX,
				this.startCellZ,
				this.horizontalCellBlockCount,
				this.verticalCellBlockCount,
				this.horizontalCellCount,
				this.verticalCellCount,
				this.minimumCellY);
		if (filled) ci.cancel();
	}
}
