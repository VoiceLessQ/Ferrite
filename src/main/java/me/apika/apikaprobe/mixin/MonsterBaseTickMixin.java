package me.apika.apikaprobe.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import me.apika.apikaprobe.monitor.MonsterPhaseMonitor;

import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.Mob;

/**
 * Times Entity.baseTick for mobs only. Entity.baseTick fires for every
 * entity; guard with instanceof Mob so items, projectiles, etc.
 * don't pollute the sample.
 */
@Mixin(Entity.class)
public abstract class MonsterBaseTickMixin {

	@Inject(method = "baseTick()V", at = @At("HEAD"))
	private void ferrite$onBaseTickBegin(CallbackInfo ci) {
		if (!((Object) this instanceof Mob)) {
			return;
		}
		MonsterPhaseMonitor.onBaseTickBegin();
	}

	@Inject(method = "baseTick()V", at = @At("RETURN"))
	private void ferrite$onBaseTickEnd(CallbackInfo ci) {
		if (!((Object) this instanceof Mob)) {
			return;
		}
		MonsterPhaseMonitor.onBaseTickEnd();
	}
}
