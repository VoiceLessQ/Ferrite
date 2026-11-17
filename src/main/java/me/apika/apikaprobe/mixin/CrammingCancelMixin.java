package me.apika.apikaprobe.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import me.apika.apikaprobe.entity.CrammingDispatcher;

import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;

/**
 * Intercepts LivingEntity.tickCramming() for Mob subclasses and cancels the
 * vanilla body only after the server-thread pre-entity hook completed the
 * Rust batch for this world tick.
 *
 * Entity worker threads never build or mutate the batch. They only read its
 * published completion marker before deciding whether to cancel vanilla.
 */
@Mixin(LivingEntity.class)
public abstract class CrammingCancelMixin {

	@Inject(method = "pushEntities()V", at = @At("HEAD"), cancellable = true)
	private void ferrite$onTickCramming(CallbackInfo ci) {
		if (!((Object) this instanceof Mob)) {
			return; // let vanilla handle non-mobs (players, etc.)
		}
		if (CrammingDispatcher.shouldCancelVanilla((LivingEntity) (Object) this)) {
			ci.cancel();
		}
	}
}
