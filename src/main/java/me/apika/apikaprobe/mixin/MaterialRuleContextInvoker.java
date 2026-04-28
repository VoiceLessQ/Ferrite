package me.apika.apikaprobe.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

/**
 * Exposes {@code MaterialRules$MaterialRuleContext.initVerticalContext}
 * (package-private inner class, protected method) as a synthetic
 * {@code ferrite$invokeInitVerticalContext} method that {@link
 * SurfaceValidatorMixin}'s {@code @Redirect} can call directly via
 * a typed cast — no reflection.
 *
 * <p>Replaces the pattern at the redirect site:
 * <pre>
 *   Method m = ctx.getClass().getMethod("initVerticalContext", ...);
 *   m.invoke(ctx, a, b, c, d, e, f);
 * </pre>
 * with a single direct virtual call (~5 ns, JIT-inlinable) instead
 * of {@code getMethod} (~150 ns) + {@code Method.invoke} (~150 ns)
 * per per-Y position. Per-chunk savings: ~9-10 ms when the surface
 * dispatcher is on (~30K per-Y calls × ~300 ns/call).
 *
 * <p>JFR profile (2026-04-28) identified this site as the dominant
 * dispatch overhead — see {@code docs/PIANO_STATUS.md}.
 */
@Mixin(targets = "net.minecraft.world.gen.surfacebuilder.MaterialRules$MaterialRuleContext")
public interface MaterialRuleContextInvoker {
	@Invoker("initVerticalContext")
	void ferrite$invokeInitVerticalContext(
			int stoneDepthAbove, int stoneDepthBelow, int fluidHeight,
			int blockX, int blockY, int blockZ);
}
