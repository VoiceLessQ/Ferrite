package me.apika.apikaprobe.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Coerce;
import org.spongepowered.asm.mixin.injection.Redirect;

import me.apika.apikaprobe.surface.SurfaceValidator;

import net.minecraft.block.BlockState;
import net.minecraft.world.gen.surfacebuilder.SurfaceBuilder;

/**
 * Redirects the per-column-Y {@code tryApply} call inside
 * {@code SurfaceBuilder.buildSurface} so that, when the validator is
 * enabled (a compiled tree is installed), every result can be diffed
 * against the bytecode evaluator.
 *
 * <p>Lives on the same INVOKE site as {@code SurfacePhaseMixin}'s
 * tryApply hook, but as a {@code @Redirect} not a paired {@code @Inject} —
 * we need the return value, not just timing brackets. The two mixins
 * coexist (Mixin allows multiple injectors at one call site provided
 * their interactions don't conflict; this redirect simply wraps the
 * call, the inject pair still observes HEAD/AFTER timestamps).
 *
 * <p>The redirect handler keeps the hot-path cost minimal: one static
 * boolean check (`isEnabled()`), and only on enabled+sampled calls
 * does it do reflection work. With sampling at 1-in-1000, overhead
 * is essentially zero on disabled and ~negligible on enabled.
 */
@Mixin(SurfaceBuilder.class)
public abstract class SurfaceValidatorMixin {

	@Redirect(
		method = "buildSurface",
		at = @At(
			value = "INVOKE",
			target = "Lnet/minecraft/world/gen/surfacebuilder/MaterialRules$BlockStateRule;tryApply(III)Lnet/minecraft/block/BlockState;"
		)
	)
	private BlockState ferrite$validateTryApply(@Coerce Object rule, int x, int y, int z) {
		// rule is MaterialRules$BlockStateRule (package-private interface).
		// Invoke tryApply via reflection — single virtual dispatch is cheap
		// and avoids needing an access widener for a one-off mixin.
		BlockState vanilla = invokeTryApply(rule, x, y, z);
		if (SurfaceValidator.isEnabled()) {
			SurfaceValidator.maybeValidate(this, x, y, z, vanilla);
		}
		return vanilla;
	}

	private static BlockState invokeTryApply(Object rule, int x, int y, int z) {
		try {
			java.lang.reflect.Method m = rule.getClass().getMethod("tryApply", int.class, int.class, int.class);
			return (BlockState) m.invoke(rule, x, y, z);
		} catch (ReflectiveOperationException e) {
			throw new RuntimeException("tryApply invocation failed", e);
		}
	}

	/**
	 * Capture the MaterialRuleContext receiver + vertical-state args
	 * just before tryApply fires for this column-Y position. The context
	 * is a local in buildSurface (not a field on SurfaceBuilder), so
	 * this redirect is the only stable way to grab it without an access
	 * widener.
	 */
	@Redirect(
		method = "buildSurface",
		at = @At(
			value = "INVOKE",
			target = "Lnet/minecraft/world/gen/surfacebuilder/MaterialRules$MaterialRuleContext;initVerticalContext(IIIIII)V"
		)
	)
	private void ferrite$captureContext(@Coerce Object ctx,
			int a, int b, int c, int d, int e, int f) {
		if (SurfaceValidator.isEnabled()) {
			SurfaceValidator.captureVerticalContext(ctx, a, b, c, d, e, f);
		}
		// Call through to vanilla via reflection.
		try {
			java.lang.reflect.Method m = ctx.getClass().getMethod("initVerticalContext",
					int.class, int.class, int.class, int.class, int.class, int.class);
			m.invoke(ctx, a, b, c, d, e, f);
		} catch (ReflectiveOperationException ex) {
			throw new RuntimeException("initVerticalContext invocation failed", ex);
		}
	}
}
