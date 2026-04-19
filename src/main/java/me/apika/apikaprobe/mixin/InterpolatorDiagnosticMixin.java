package me.apika.apikaprobe.mixin;

import java.lang.reflect.Field;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import net.minecraft.world.gen.chunk.ChunkNoiseSampler;
import net.minecraft.world.gen.densityfunction.DensityFunction;

/**
 * One-shot diagnostic. Fires on the first sampleStartDensity() completion.
 * Logs per-interpolator:
 *   - index + delegate simple class name + min/max value
 *   - startDensityBuffer dimensions
 *   - each delegate's declared fields (reflection, to peek one level
 *     into wrapping structures like RangeChoice)
 */
@Mixin(ChunkNoiseSampler.class)
public abstract class InterpolatorDiagnosticMixin {

	private static final Logger LOGGER = LoggerFactory.getLogger("rusty");
	private static final AtomicBoolean LOGGED = new AtomicBoolean(false);

	@Inject(method = "sampleStartDensity", at = @At("RETURN"))
	private void apikaprobe$dumpInterpolators(CallbackInfo ci) {
		if (!LOGGED.compareAndSet(false, true)) {
			return;
		}

		ChunkNoiseSamplerAccessor sampler = (ChunkNoiseSamplerAccessor) this;
		List<Object> interpolators = sampler.apikaprobe$getInterpolators();

		LOGGER.info("[interp-diag] interpolator count = {}", interpolators.size());

		for (int i = 0; i < interpolators.size(); i++) {
			DensityInterpolatorAccessor acc = (DensityInterpolatorAccessor) interpolators.get(i);
			DensityFunction delegate = acc.apikaprobe$getDelegate();

			double[][] startBuf = acc.apikaprobe$getStartDensityBuffer();
			String startDims = describeBufferDims(startBuf);

			double[][] endBuf = acc.apikaprobe$getEndDensityBuffer();
			String endDims = describeBufferDims(endBuf);

			LOGGER.info("[interp-diag] [{}] delegate={}  min={}  max={}  start={}  end={}",
					i,
					delegate.getClass().getSimpleName(),
					delegate.minValue(),
					delegate.maxValue(),
					startDims,
					endDims);

			// Peek one level deeper via reflection — for RangeChoice and similar,
			// this reveals inner field names and their class types so we can tell
			// the 7 RangeChoice entries apart structurally.
			describeFields(i, delegate);
		}
	}

	private static String describeBufferDims(double[][] buf) {
		if (buf == null) {
			return "null";
		}
		int outer = buf.length;
		int inner = outer > 0 && buf[0] != null ? buf[0].length : -1;
		return "[" + outer + "][" + inner + "]";
	}

	private static void describeFields(int idx, Object obj) {
		Class<?> cls = obj.getClass();
		Field[] fields = cls.getDeclaredFields();
		for (Field f : fields) {
			if (java.lang.reflect.Modifier.isStatic(f.getModifiers())) {
				continue;
			}
			try {
				f.setAccessible(true);
				Object value = f.get(obj);
				String valueClass = value == null ? "null" : value.getClass().getSimpleName();
				String valueToString = value == null
						? "null"
						: (value instanceof Number || value instanceof Boolean
								? value.toString()
								: valueClass);
				LOGGER.info("[interp-diag] [{}]   .{} : {} = {}",
						idx, f.getName(), f.getType().getSimpleName(), valueToString);
			} catch (IllegalAccessException e) {
				LOGGER.info("[interp-diag] [{}]   .{} : {} = <inaccessible>",
						idx, f.getName(), f.getType().getSimpleName());
			}
		}
	}
}
