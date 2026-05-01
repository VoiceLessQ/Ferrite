package me.apika.apikaprobe.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import me.apika.apikaprobe.entity.CrammingDispatcher;

import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;

/**
 * Intercepts LivingEntity.tickCramming() for Mob subclasses and
 * cancels the vanilla body when the Rust batched dispatcher has already
 * (or will now) handle it for this tick.
 *
 * First Mob tickCramming of the tick triggers the batch; every
 * subsequent call in the same tick just cancels (batch is idempotent
 * via CrammingDispatcher.lastProcessedTick).
 */
@Mixin(LivingEntity.class)
public abstract class CrammingCancelMixin {

	@Inject(method = "tickCramming()V", at = @At("HEAD"), cancellable = true)
	private void ferrite$onTickCramming(CallbackInfo ci) {
		if (!((Object) this instanceof Mob)) {
			return; // let vanilla handle non-mobs (players, etc.)
		}
		if (CrammingDispatcher.onTickCramming((LivingEntity) (Object) this)) {
			ci.cancel();
		}
	}
}
