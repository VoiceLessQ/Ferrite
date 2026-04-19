package me.apika.apikaprobe.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import me.apika.apikaprobe.MovementInternalsMonitor;

import net.minecraft.entity.ai.pathing.EntityNavigation;

/**
 * Times EntityNavigation.tick() — path step execution for mob navigators.
 *
 * No entity-type guard here — EntityNavigation is owned by MobEntity
 * by construction. Targets the abstract base class; subclasses
 * (MobNavigation, BirdNavigation, etc.) that override tick() and call
 * super.tick() will be counted. If a subclass overrides without calling
 * super, its calls will be missed — if diagnostic shows suspiciously
 * low navigator time, we'd need to add subclass-specific Mixins.
 */
@Mixin(EntityNavigation.class)
public abstract class NavigatorTickMixin {

	@Inject(method = "tick()V", at = @At("HEAD"))
	private void ferrite$onNavigatorBegin(CallbackInfo ci) {
		MovementInternalsMonitor.onNavigatorBegin();
	}

	@Inject(method = "tick()V", at = @At("RETURN"))
	private void ferrite$onNavigatorEnd(CallbackInfo ci) {
		MovementInternalsMonitor.onNavigatorEnd();
	}
}
