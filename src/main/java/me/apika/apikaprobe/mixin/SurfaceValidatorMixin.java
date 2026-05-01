package me.apika.apikaprobe.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Coerce;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.levelgen.WorldGenerationContext;
import net.minecraft.world.level.levelgen.NoiseChunk;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.level.levelgen.SurfaceRules;
import net.minecraft.world.level.biome.BiomeManager;
import net.minecraft.core.Registry;
import net.minecraft.world.level.biome.Biome;

import me.apika.apikaprobe.surface.SurfaceDispatcher;
import me.apika.apikaprobe.surface.SurfaceValidator;

import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.SurfaceRules;

/**
 * Redirects the per-column-Y {@code tryApply} call inside
 * {@code SurfaceRules.buildSurface} so that, when the validator is
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
@Mixin(SurfaceRules.class)
public abstract class SurfaceValidatorMixin {

	/**
	 * Arm the per-thread batched dispatcher at the start of buildSurface.
	 * The actual chunk reference comes from a local variable (protoChunk
	 * is the 6th argument in the yarn signature); we capture it via the
	 * dispatcher's beginChunk hook. Subsequent tryApply redirect calls on
	 * this thread will route through SurfaceDispatcher.enqueue.
	 *
	 * <p>The chunk argument is bound positionally — Mixin matches @Inject
	 * parameters to the target method's parameters in order. Letting the
	 * unused ones be Object keeps us decoupled from yarn signature drift
	 * across MC versions.
	 */
	@Inject(method = "buildSurface", at = @At("HEAD"))
	private void ferrite$dispatchBegin(
			RandomState noiseConfig, BiomeManager biomeAccess, Registry<Biome> biomeRegistry,
			boolean useLegacyRandom, WorldGenerationContext heightContext,
			ChunkAccess protoChunk, NoiseChunk chunkNoiseSampler,
			SurfaceRules.RuleSource ruleSource,
			CallbackInfo ci) {
		SurfaceDispatcher.beginChunk(protoChunk);
	}

	/**
	 * Flush the per-thread batched dispatcher at the end of buildSurface.
	 * One JNI call evaluates every captured (x, y, z); results write back
	 * via ChunkAccess.setBlockState. No-op if dispatch wasn't active for this
	 * chunk (toggle off, no tree installed, or beginChunk failed).
	 */
	@Inject(method = "buildSurface", at = @At("RETURN"))
	private void ferrite$dispatchEnd(
			RandomState noiseConfig, BiomeManager biomeAccess, Registry<Biome> biomeRegistry,
			boolean useLegacyRandom, WorldGenerationContext heightContext,
			ChunkAccess protoChunk, NoiseChunk chunkNoiseSampler,
			SurfaceRules.RuleSource ruleSource,
			CallbackInfo ci) {
		SurfaceDispatcher.flushChunk();
	}

	@Redirect(
		method = "buildSurface",
		at = @At(
			value = "INVOKE",
			target = "Lnet/minecraft/world/level/levelgen/SurfaceRules$BlockStateRule;tryApply(III)Lnet/minecraft/world/level/block/state/BlockState;"
		)
	)
	private BlockState ferrite$validateTryApply(@Coerce Object rule, int x, int y, int z) {
		// Dispatch swap: when /ferrite surface dispatch on AND a tree is
		// installed, run Ferrite's bytecode evaluator and return its
		// result instead of vanilla's. Eval-returns-null falls through
		// to vanilla (matches "no rule matched" semantics + safety net
		// for any residual evaluator gap, e.g. an unrecognized node type
		// the compiler emitted as OP_FALLBACK).
		// Batched dispatch path: per-thread state armed by the buildSurface
		// HEAD inject. If active, capture the per-position context via
		// cached reflective handles into the dispatcher's primitive arrays
		// and return null — vanilla's "if (state != null) setBlock" check
		// then skips its own write. The dispatcher flushes after the loop
		// (one JNI call, all writes).
		if (SurfaceDispatcher.batchActive()) {
			boolean enqueued = SurfaceValidator.dispatchEnqueue(
					SurfaceValidator.capturedLiveCtx(), x, y, z);
			if (enqueued) {
				return null;
			}
			// Dispatcher overflowed → fall through to vanilla as a safety net.
			return invokeTryApply(rule, x, y, z);
		}

		// Legacy simple per-call swap (kept for diagnostic A/B; the
		// 15× regression is documented and known).
		if (SurfaceDispatcher.ENABLED) {
			Object dispatched = SurfaceValidator.tryDispatchEvaluator(this, x, y, z);
			if (dispatched instanceof BlockState bs) {
				return bs;
			}
			return invokeTryApply(rule, x, y, z);
		}

		// rule is SurfaceRules$BlockStateRule (package-private interface).
		// Invoke tryApply via reflection — single virtual dispatch is cheap
		// and avoids needing an access widener for a one-off mixin.
		BlockState vanilla = invokeTryApply(rule, x, y, z);
		if (SurfaceValidator.isEnabled()) {
			SurfaceValidator.maybeValidate(this, x, y, z, vanilla);
		}
		return vanilla;
	}

	private static BlockState invokeTryApply(Object rule, int x, int y, int z) {
		// Direct interface call via @Invoker — replaces per-call reflection
		// (~300ns: getMethod lookup + Method.invoke) with a virtual call
		// (~5ns, JIT-inlinable). See BlockStateRuleInvoker.
		return ((BlockStateRuleInvoker) rule).ferrite$invokeTryApply(x, y, z);
	}

	/**
	 * Capture the MaterialRuleContext receiver + vertical-state args
	 * just before tryApply fires for this column-Y position. The context
	 * is a local in buildSurface (not a field on SurfaceRules), so
	 * this redirect is the only stable way to grab it without an access
	 * widener.
	 */
	@Redirect(
		method = "buildSurface",
		at = @At(
			value = "INVOKE",
			target = "Lnet/minecraft/world/level/levelgen/SurfaceRules$MaterialRuleContext;initVerticalContext(IIIIII)V"
		)
	)
	private void ferrite$captureContext(@Coerce Object ctx,
			int a, int b, int c, int d, int e, int f) {
		if (SurfaceValidator.isEnabled()) {
			SurfaceValidator.captureVerticalContext(ctx, a, b, c, d, e, f);
		}
		// Direct typed call via @Invoker — replaces per-call reflection
		// (~300ns/call × ~30K per-Y positions per chunk = ~9-10ms/chunk
		// of pure overhead identified by JFR profile 2026-04-28) with a
		// direct virtual call (~5ns, JIT-inlinable).
		// See MaterialRuleContextInvoker + docs/PIANO_STATUS.md.
		((MaterialRuleContextInvoker) ctx).ferrite$invokeInitVerticalContext(a, b, c, d, e, f);
	}
}
