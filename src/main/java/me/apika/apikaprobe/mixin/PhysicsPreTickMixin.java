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
 * Fires at ServerLevel's "entities" profiler marker, after the world time
 * advances and before the entity list is ticked.
 *
 * Anchored at the "entities" profiler string constant — the vanilla
 * marker immediately before entityTickList.forEach(). Stable across
 * versions; only breaks if Mojang renames the profiler scope.
 */
@Mixin(ServerLevel.class)
public abstract class PhysicsPreTickMixin {

	@Inject(
		method = "tick(Ljava/util/function/BooleanSupplier;)V",
		at = @At(value = "CONSTANT", args = "stringValue=entities")
	)
	private void ferrite$onPreEntityTick(BooleanSupplier shouldKeepTicking, CallbackInfo ci) {
		ServerLevel world = (ServerLevel) (Object) this;
		CrammingDispatcher.onPreEntityTick(world);
		PhysicsDispatcher.onPreEntityTick(world);
	}
}
