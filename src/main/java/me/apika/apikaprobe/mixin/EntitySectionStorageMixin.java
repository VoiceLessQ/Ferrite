package me.apika.apikaprobe.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import me.apika.apikaprobe.monitor.EntityQueryMonitor;

import net.minecraft.world.level.entity.EntitySectionStorage;

/**
 * Probes both getEntities overloads (plain AABB and EntityTypeTest):
 * every spatial "entities in this box" scan lands here, whatever the
 * caller (targeting, sensing, collision, cramming).
 */
@Mixin(EntitySectionStorage.class)
public abstract class EntitySectionStorageMixin {

	@Inject(method = "getEntities(Lnet/minecraft/world/phys/AABB;Lnet/minecraft/util/AbortableIterationConsumer;)V", at = @At("HEAD"))
	private void ferrite$onQueryBegin(CallbackInfo ci) {
		EntityQueryMonitor.onQueryBegin();
	}

	@Inject(method = "getEntities(Lnet/minecraft/world/phys/AABB;Lnet/minecraft/util/AbortableIterationConsumer;)V", at = @At("RETURN"))
	private void ferrite$onQueryEnd(CallbackInfo ci) {
		EntityQueryMonitor.onQueryEnd();
	}

	@Inject(method = "getEntities(Lnet/minecraft/world/level/entity/EntityTypeTest;Lnet/minecraft/world/phys/AABB;Lnet/minecraft/util/AbortableIterationConsumer;)V", at = @At("HEAD"))
	private void ferrite$onTypedQueryBegin(CallbackInfo ci) {
		EntityQueryMonitor.onQueryBegin();
	}

	@Inject(method = "getEntities(Lnet/minecraft/world/level/entity/EntityTypeTest;Lnet/minecraft/world/phys/AABB;Lnet/minecraft/util/AbortableIterationConsumer;)V", at = @At("RETURN"))
	private void ferrite$onTypedQueryEnd(CallbackInfo ci) {
		EntityQueryMonitor.onQueryEnd();
	}

	@Inject(method = "createSection", at = @At("RETURN"))
	private void ferrite$stampOrigin(long sectionPos,
			org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable<net.minecraft.world.level.entity.EntitySection<?>> cir) {
		((me.apika.apikaprobe.spatial.SectionExtents) cir.getReturnValue()).ferrite$setOrigin(
				net.minecraft.core.SectionPos.x(sectionPos) << 4,
				net.minecraft.core.SectionPos.y(sectionPos) << 4,
				net.minecraft.core.SectionPos.z(sectionPos) << 4);
	}
}
