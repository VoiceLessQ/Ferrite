package me.apika.apikaprobe.mixin.client;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import me.apika.apikaprobe.EntityRenderMonitor;

import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.entity.EntityRenderDispatcher;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import com.mojang.blaze3d.vertex.PoseStack;

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
		method = "submit(Lnet/minecraft/client/renderer/entity/state/EntityRenderState;Lnet/minecraft/client/renderer/state/level/CameraRenderState;DDDLcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/SubmitNodeCollector;)V",
		at = @At("HEAD")
	)
	private void apikaprobe$onRenderBegin(
			EntityRenderState renderState,
			CameraRenderState cameraState,
			double offsetX,
			double offsetY,
			double offsetZ,
			PoseStack matrices,
			SubmitNodeCollector queue,
			CallbackInfo ci) {
		EntityRenderMonitor.onRenderBegin();
	}

	@Inject(
		method = "submit(Lnet/minecraft/client/renderer/entity/state/EntityRenderState;Lnet/minecraft/client/renderer/state/level/CameraRenderState;DDDLcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/SubmitNodeCollector;)V",
		at = @At("RETURN")
	)
	private void apikaprobe$onRenderEnd(
			EntityRenderState renderState,
			CameraRenderState cameraState,
			double offsetX,
			double offsetY,
			double offsetZ,
			PoseStack matrices,
			SubmitNodeCollector queue,
			CallbackInfo ci) {
		EntityRenderMonitor.onRenderEnd();
	}
}
