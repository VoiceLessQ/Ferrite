package me.apika.apikaprobe.mixin;

import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import net.minecraft.util.math.noise.InterpolatedNoiseSampler;
import net.minecraft.world.gen.chunk.ChunkNoiseSampler;
import net.minecraft.world.gen.densityfunction.DensityFunction;

import me.apika.apikaprobe.RustBlendedNoiseWrapper;

/**
 * Surgical Rust replacement at the {@code BlendedNoise} leaf. Vanilla's
 * {@code ChunkNoiseSampler.getActualDensityFunctionImpl} (mojmap
 * {@code wrapNew}) substitutes {@code Marker(Interpolated, X)} with
 * {@code new NoiseInterpolator(X)} during chunk init. We modify the
 * constructor's argument: if X is a {@code BlendedNoise} (yarn
 * {@code InterpolatedNoiseSampler}), we replace it with a Rust-backed
 * wrapper that pre-bulk-samples corner densities. Otherwise leave X
 * untouched.
 *
 * <p>Vanilla's {@code NoiseInterpolator} runs unchanged — corner sampling
 * goes through our cheap leaf, per-block lerp uses vanilla's hot path.
 * The outer composition (factor × offset + jaggedness × ...) and all
 * other DFs in the noise router are not touched.
 *
 * <p>Math: bit-exact at corner positions (proven by parity validator);
 * lerp matches vanilla because vanilla does the lerp.
 */
@Mixin(ChunkNoiseSampler.class)
public abstract class ChunkNoiseSamplerMixin {

	@Shadow
	@Final
	private int startCellX;

	@Shadow
	@Final
	private int startCellZ;

	@Shadow
	@Final
	private int horizontalCellBlockCount;

	/** Hook into the wrap visitor's leaf processing. {@code wrapNew}
	 *  (yarn {@code getActualDensityFunctionImpl}) is called once per
	 *  unique DF instance during the chunk's {@code mapAll(this::wrap)}
	 *  walk. When the input DF is an {@code InterpolatedNoiseSampler}
	 *  (BlendedNoise leaf), substitute our Rust-backed wrapper.
	 *
	 *  <p>Vanilla's outer Marker(Interpolated) wraps a *composed*
	 *  expression that contains the BlendedNoise inside — so we can't
	 *  catch it at the {@code DensityInterpolator} constructor arg.
	 *  Catching at the leaf level lets vanilla's recursive mapAll
	 *  natively substitute our wrapper anywhere the BlendedNoise
	 *  appears in the tree. */
	@Inject(
			method = "getActualDensityFunctionImpl",
			at = @At("HEAD"),
			cancellable = true
	)
	private void ferrite$swapBlendedNoiseLeaf(DensityFunction function,
			CallbackInfoReturnable<DensityFunction> cir) {
		if (!RustBlendedNoiseWrapper.ENABLED) return;
		if (!(function instanceof InterpolatedNoiseSampler ins)) return;

		Double xzScale = readDoubleField(ins, "xzScale");
		Double yScale = readDoubleField(ins, "yScale");
		Double xzFactor = readDoubleField(ins, "xzFactor");
		Double yFactor = readDoubleField(ins, "yFactor");
		Double smear = readDoubleField(ins, "smearScaleMultiplier");
		if (xzScale == null || yScale == null || xzFactor == null
				|| yFactor == null || smear == null) return;
		String name = RustBlendedNoiseWrapper.identifyDimension(
				xzScale, yScale, xzFactor, yFactor, smear);
		if (name == null) return;

		int chunkMinBlockX = this.startCellX * this.horizontalCellBlockCount;
		int chunkMinBlockZ = this.startCellZ * this.horizontalCellBlockCount;
		cir.setReturnValue(new RustBlendedNoiseWrapper(
				function, name, chunkMinBlockX, chunkMinBlockZ));
	}

	private static Double readDoubleField(Object obj, String name) {
		Class<?> cls = obj.getClass();
		while (cls != null && cls != Object.class) {
			try {
				java.lang.reflect.Field f = cls.getDeclaredField(name);
				f.setAccessible(true);
				if (f.getType() == double.class) return f.getDouble(obj);
				Object v = f.get(obj);
				if (v instanceof Double d) return d;
			} catch (NoSuchFieldException ignored) {
				// next superclass
			} catch (IllegalAccessException ignored) {
				return null;
			}
			cls = cls.getSuperclass();
		}
		return null;
	}
}
