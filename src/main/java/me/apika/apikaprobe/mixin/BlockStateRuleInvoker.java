package me.apika.apikaprobe.mixin;

import net.minecraft.world.level.block.state.BlockState;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

/**
 * Exposes {@code SurfaceRules$BlockStateRule.tryApply} (package-private
 * inner interface, package-private method) as a synthetic
 * {@code ferrite$invokeTryApply} method that {@link SurfaceValidatorMixin}'s
 * validator path can call directly via a typed cast — no reflection.
 *
 * <p>Replaces the pattern at the validator's per-call invocation site:
 * <pre>
 *   Method m = rule.getClass().getMethod("tryApply", int.class, int.class, int.class);
 *   return (BlockState) m.invoke(rule, x, y, z);
 * </pre>
 * with a single direct interface call. Removes per-call reflection from
 * the validator's slow path and keeps the design consistent with
 * {@link MaterialRuleContextInvoker}.
 */
@Mixin(targets = "net.minecraft.world.level.levelgen.SurfaceRules$BlockStateRule")
public interface BlockStateRuleInvoker {
	@Invoker("tryApply")
	BlockState ferrite$invokeTryApply(int x, int y, int z);
}
