package me.apika.apikaprobe.mixin;

import java.util.function.BooleanSupplier;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import me.apika.apikaprobe.PhysicsDispatcher;

import net.minecraft.server.world.ServerWorld;

/**
 * Fires once per world, per tick, just before entity iteration begins.
 * This is the moment PhysicsDispatcher can build the shared world
 * snapshot used by every mob's adjustMovementForCollisions redirect.
 *
 * Anchored at the "entities" profiler string constant — the vanilla
 * marker immediately before entityTickList.forEach(). Stable across
 * versions; only breaks if Mojang renames the profiler scope.
 */
@Mixin(ServerWorld.class)
public abstract class PhysicsPreTickMixin {

	@Inject(
		method = "tick(Ljava/util/function/BooleanSupplier;)V",
		at = @At(value = "INVOKE_STRING", args = "ldc=entities")
	)
	private void ferrite$onPreEntityTick(BooleanSupplier shouldKeepTicking, CallbackInfo ci) {
		PhysicsDispatcher.onPreEntityTick((ServerWorld) (Object) this);
	}
}
