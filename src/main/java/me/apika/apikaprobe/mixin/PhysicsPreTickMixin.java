package me.apika.apikaprobe.mixin;

import java.util.function.BooleanSupplier;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import me.apika.apikaprobe.entity.CrammingDispatcher;
import me.apika.apikaprobe.entity.PhysicsDispatcher;

import net.minecraft.server.level.ServerLevel;

/**
 * Fires once per world, per tick, at the start of ServerLevel.tick.
 * Cramming runs here on the server thread before Async starts entity
 * workers; PhysicsDispatcher also builds its per-world tick state here.
 *
 * Anchored at the "entities" profiler string constant — the vanilla
 * marker immediately before entityTickList.forEach(). Stable across
 * versions; only breaks if Mojang renames the profiler scope.
 */
@Mixin(ServerLevel.class)
public abstract class PhysicsPreTickMixin {

	@Inject(
		method = "tick(Ljava/util/function/BooleanSupplier;)V",
		at = @At("HEAD")
	)
	private void ferrite$onPreEntityTick(BooleanSupplier shouldKeepTicking, CallbackInfo ci) {
		ServerLevel world = (ServerLevel) (Object) this;
		CrammingDispatcher.onPreEntityTick(world);
		PhysicsDispatcher.onPreEntityTick(world);
	}
}
