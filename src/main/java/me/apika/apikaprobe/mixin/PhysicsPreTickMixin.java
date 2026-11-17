package me.apika.apikaprobe.mixin;

import java.util.function.BooleanSupplier;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import me.apika.apikaprobe.entity.PhysicsDispatcher;

import net.minecraft.server.level.ServerLevel;

/**
 * Fires once per world tick at the start of ServerLevel.tick so the
 * physics dispatcher has current world state for earlier block events.
 */
@Mixin(ServerLevel.class)
public abstract class PhysicsPreTickMixin {

	@Inject(
		method = "tick(Ljava/util/function/BooleanSupplier;)V",
		at = @At("HEAD")
	)
	private void ferrite$onPreEntityTick(BooleanSupplier shouldKeepTicking, CallbackInfo ci) {
		ServerLevel world = (ServerLevel) (Object) this;
		PhysicsDispatcher.onPreEntityTick(world);
	}
}
