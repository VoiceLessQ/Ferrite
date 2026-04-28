package me.apika.apikaprobe.mixin;

import java.util.function.Supplier;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * Exposes package-private fields on
 * {@code MaterialRules$MaterialRuleContext} as direct getters so
 * {@link me.apika.apikaprobe.surface.SurfaceValidator}'s per-position
 * dispatch path can read them via a typed cast — no MethodHandle, no
 * reflection.
 *
 * <p>JFR (2026-04-28, post-@Invoker) showed {@code fastReadObjectField}
 * at 3.5% of in-buildSurface samples and {@code Invokers.checkCustomized}
 * at 4.5% — combined ~1.2 ms/chunk burnt on MethodHandle indirection
 * for the {@code biomeSupplier} field read alone (fires every Y).
 * {@code runDepth} fires only on the column-first miss but is included
 * for symmetry with the existing reflective fallback.
 */
@Mixin(targets = "net.minecraft.world.gen.surfacebuilder.MaterialRules$MaterialRuleContext")
public interface MaterialRuleContextAccessor {
	@Accessor("runDepth")
	int ferrite$getRunDepth();

	@Accessor("biomeSupplier")
	Supplier<?> ferrite$getBiomeSupplier();
}
