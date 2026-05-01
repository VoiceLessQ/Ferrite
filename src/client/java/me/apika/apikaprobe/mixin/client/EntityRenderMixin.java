package me.apika.apikaprobe.mixin.client;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import me.apika.apikaprobe.EntityRenderMonitor;

import net.minecraft.client.render.command.OrderedRenderCommandQueue;
import net.minecraft.client.render.entity.EntityRenderDispatcher;
import net.minecraft.client.render.entity.state.EntityRenderState;
import net.minecraft.client.render.state.CameraRenderState;
import net.minecraft.client.util.math.PoseStack;

/**
 * Per-entity render timing via HEAD/RETURN on EntityRenderDispatcher.render.
 * Fully qualified descriptor disambiguates from overloads (if any).
 *
 * The monitor's sampled counter (1-in-100) keeps the hot-path cost
 * down — every call increments an AtomicLong but only ~1% actually
 * time the render.
 */
@Mixin(EntityRenderDispatcher.class)
public abstract class EntityRenderMixin {

	@Inject(
		method = "render(Lnet/minecraft/client/render/entity/state/EntityRenderState;Lnet/minecraft/client/render/state/CameraRenderState;DDDLnet/minecraft/client/util/math/PoseStack;Lnet/minecraft/client/render/command/OrderedRenderCommandQueue;)V",
		at = @At("HEAD")
	)
	private void apikaprobe$onRenderBegin(
			EntityRenderState renderState,
			CameraRenderState cameraState,
			double offsetX,
			double offsetY,
			double offsetZ,
			PoseStack matrices,
			OrderedRenderCommandQueue queue,
			CallbackInfo ci) {
		EntityRenderMonitor.onRenderBegin();
	}

	@Inject(
		method = "render(Lnet/minecraft/client/render/entity/state/EntityRenderState;Lnet/minecraft/client/render/state/CameraRenderState;DDDLnet/minecraft/client/util/math/PoseStack;Lnet/minecraft/client/render/command/OrderedRenderCommandQueue;)V",
		at = @At("RETURN")
	)
	private void apikaprobe$onRenderEnd(
			EntityRenderState renderState,
			CameraRenderState cameraState,
			double offsetX,
			double offsetY,
			double offsetZ,
			PoseStack matrices,
			OrderedRenderCommandQueue queue,
			CallbackInfo ci) {
		EntityRenderMonitor.onRenderEnd();
	}
}
