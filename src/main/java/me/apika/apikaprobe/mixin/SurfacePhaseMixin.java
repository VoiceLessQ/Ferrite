package me.apika.apikaprobe.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import me.apika.apikaprobe.monitor.SurfacePhaseMonitor;

import net.minecraft.world.level.levelgen.SurfaceRules;

/**
 * Times SurfaceRules.buildSurface and the six inner call sites that
 * make up the hot path. Before/After pairs at each INVOKE site record
 * nanos into the per-phase accumulator.
 *
 * Two @At targets on the same INVOKE site — one at default (BEFORE the
 * call) and one at AFTER — let us measure the call itself without
 * needing @WrapOperation. This matches the pattern used for the
 * existing movement-internals hooks.
 *
 * Mixin method= uses simple name "buildSurface" since there's exactly
 * one on SurfaceRules; descriptor disambiguation isn't needed.
 */
@Mixin(SurfaceRules.class)
public abstract class SurfacePhaseMixin {

	// --- Outer envelope ----------------------------------------------------

	@Inject(method = "buildSurface", at = @At("HEAD"))
	private void ferrite$onBuildBegin(CallbackInfo ci) {
		SurfacePhaseMonitor.onBuildSurfaceBegin();
	}

	@Inject(method = "buildSurface", at = @At("RETURN"))
	private void ferrite$onBuildEnd(CallbackInfo ci) {
		SurfacePhaseMonitor.onBuildSurfaceEnd();
	}

	// --- tryApply (rule-tree walk — expected hot) --------------------------

	@Inject(
		method = "buildSurface",
		at = @At(
			value = "INVOKE",
			target = "Lnet.minecraft.world.level.levelgen.SurfaceRules$BlockStateRule;tryApply(III)Lnet.minecraft.world.level.block.state.BlockState;"
		)
	)
	private void ferrite$onTryApplyBegin(CallbackInfo ci) {
		SurfacePhaseMonitor.onTryApplyBegin();
	}

	@Inject(
		method = "buildSurface",
		at = @At(
			value = "INVOKE",
			target = "Lnet.minecraft.world.level.levelgen.SurfaceRules$BlockStateRule;tryApply(III)Lnet.minecraft.world.level.block.state.BlockState;",
			shift = At.Shift.AFTER
		)
	)
	private void ferrite$onTryApplyEnd(CallbackInfo ci) {
		SurfacePhaseMonitor.onTryApplyEnd();
	}

	// --- Block column read / write -----------------------------------------

	@Inject(
		method = "buildSurface",
		at = @At(
			value = "INVOKE",
			target = "Lnet.minecraft.world.level.levelgen.BlockColumn;getState(I)Lnet.minecraft.world.level.block.state.BlockState;"
		)
	)
	private void ferrite$onBlockReadBegin(CallbackInfo ci) {
		SurfacePhaseMonitor.onBlockReadBegin();
	}

	@Inject(
		method = "buildSurface",
		at = @At(
			value = "INVOKE",
			target = "Lnet.minecraft.world.level.levelgen.BlockColumn;getState(I)Lnet.minecraft.world.level.block.state.BlockState;",
			shift = At.Shift.AFTER
		)
	)
	private void ferrite$onBlockReadEnd(CallbackInfo ci) {
		SurfacePhaseMonitor.onBlockReadEnd();
	}

	@Inject(
		method = "buildSurface",
		at = @At(
			value = "INVOKE",
			target = "Lnet.minecraft.world.level.levelgen.BlockColumn;setState(ILnet.minecraft.world.level.block.state.BlockState;)V"
		)
	)
	private void ferrite$onBlockWriteBegin(CallbackInfo ci) {
		SurfacePhaseMonitor.onBlockWriteBegin();
	}

	@Inject(
		method = "buildSurface",
		at = @At(
			value = "INVOKE",
			target = "Lnet.minecraft.world.level.levelgen.BlockColumn;setState(ILnet.minecraft.world.level.block.state.BlockState;)V",
			shift = At.Shift.AFTER
		)
	)
	private void ferrite$onBlockWriteEnd(CallbackInfo ci) {
		SurfacePhaseMonitor.onBlockWriteEnd();
	}

	// --- Rule context updates (initHorizontalContext + initVerticalContext) ---

	@Inject(
		method = "buildSurface",
		at = @At(
			value = "INVOKE",
			target = "Lnet.minecraft.world.level.levelgen.SurfaceRules$MaterialRuleContext;initHorizontalContext(II)V"
		)
	)
	private void ferrite$onCtxUpdateXZBegin(CallbackInfo ci) {
		SurfacePhaseMonitor.onCtxUpdateBegin();
	}

	@Inject(
		method = "buildSurface",
		at = @At(
			value = "INVOKE",
			target = "Lnet.minecraft.world.level.levelgen.SurfaceRules$MaterialRuleContext;initHorizontalContext(II)V",
			shift = At.Shift.AFTER
		)
	)
	private void ferrite$onCtxUpdateXZEnd(CallbackInfo ci) {
		SurfacePhaseMonitor.onCtxUpdateEnd();
	}

	@Inject(
		method = "buildSurface",
		at = @At(
			value = "INVOKE",
			target = "Lnet.minecraft.world.level.levelgen.SurfaceRules$MaterialRuleContext;initVerticalContext(IIIIII)V"
		)
	)
	private void ferrite$onCtxUpdateYBegin(CallbackInfo ci) {
		SurfacePhaseMonitor.onCtxUpdateBegin();
	}

	@Inject(
		method = "buildSurface",
		at = @At(
			value = "INVOKE",
			target = "Lnet.minecraft.world.level.levelgen.SurfaceRules$MaterialRuleContext;initVerticalContext(IIIIII)V",
			shift = At.Shift.AFTER
		)
	)
	private void ferrite$onCtxUpdateYEnd(CallbackInfo ci) {
		SurfacePhaseMonitor.onCtxUpdateEnd();
	}

	// --- Biome lookup ------------------------------------------------------

	@Inject(
		method = "buildSurface",
		at = @At(
			value = "INVOKE",
			target = "Lnet.minecraft.world.level.biome.BiomeManager;getBiome(Lnet.minecraft.core.BlockPos;)Lnet.minecraft.core.Holder;"
		)
	)
	private void ferrite$onBiomeLookupBegin(CallbackInfo ci) {
		SurfacePhaseMonitor.onBiomeLookupBegin();
	}

	@Inject(
		method = "buildSurface",
		at = @At(
			value = "INVOKE",
			target = "Lnet.minecraft.world.level.biome.BiomeManager;getBiome(Lnet.minecraft.core.BlockPos;)Lnet.minecraft.core.Holder;",
			shift = At.Shift.AFTER
		)
	)
	private void ferrite$onBiomeLookupEnd(CallbackInfo ci) {
		SurfacePhaseMonitor.onBiomeLookupEnd();
	}

	// --- Heightmap sample --------------------------------------------------

	@Inject(
		method = "buildSurface",
		at = @At(
			value = "INVOKE",
			target = "Lnet.minecraft.world.level.chunk.ChunkAccess;sampleHeightmap(Lnet.minecraft.world.level.levelgen.Heightmap.Types;II)I"
		)
	)
	private void ferrite$onHeightmapBegin(CallbackInfo ci) {
		SurfacePhaseMonitor.onHeightmapBegin();
	}

	@Inject(
		method = "buildSurface",
		at = @At(
			value = "INVOKE",
			target = "Lnet.minecraft.world.level.chunk.ChunkAccess;sampleHeightmap(Lnet.minecraft.world.level.levelgen.Heightmap.Types;II)I",
			shift = At.Shift.AFTER
		)
	)
	private void ferrite$onHeightmapEnd(CallbackInfo ci) {
		SurfacePhaseMonitor.onHeightmapEnd();
	}
}
