package me.apika.apikaprobe.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import me.apika.apikaprobe.CrammingDispatcher;

import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.mob.MobEntity;

/**
 * Intercepts LivingEntity.tickCramming() for MobEntity subclasses and
 * cancels the vanilla body when the Rust batched dispatcher has already
 * (or will now) handle it for this tick.
 *
 * First MobEntity tickCramming of the tick triggers the batch; every
 * subsequent call in the same tick just cancels (batch is idempotent
 * via CrammingDispatcher.lastProcessedTick).
 */
@Mixin(LivingEntity.class)
public abstract class CrammingCancelMixin {

	@Inject(method = "tickCramming()V", at = @At("HEAD"), cancellable = true)
	private void ferrite$onTickCramming(CallbackInfo ci) {
		if (!((Object) this instanceof MobEntity)) {
			return; // let vanilla handle non-mobs (players, etc.)
		}
		if (CrammingDispatcher.onTickCramming((LivingEntity) (Object) this)) {
			ci.cancel();
		}
	}
}
