package me.apika.apikaprobe.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import me.apika.apikaprobe.WorldTickMonitor;

import net.minecraft.world.World;

/**
 * Times {@code World.tickBlockEntities()} at HEAD and RETURN.
 *
 * World.tickBlockEntities is declared on the abstract World and called
 * from both ServerWorld.tick() and ClientWorld.tickEntities(). We only
 * want server-side samples, so the handlers early-out when isClient.
 */
@Mixin(World.class)
public abstract class WorldTickMixin {

	@Inject(method = "tickBlockEntities", at = @At("HEAD"))
	private void ferrite$onBlockEntitiesBegin(CallbackInfo ci) {
		if (((World) (Object) this).isClient()) {
			return;
		}
		WorldTickMonitor.onBlockEntitiesBegin();
	}

	@Inject(method = "tickBlockEntities", at = @At("RETURN"))
	private void ferrite$onBlockEntitiesEnd(CallbackInfo ci) {
		if (((World) (Object) this).isClient()) {
			return;
		}
		WorldTickMonitor.onBlockEntitiesEnd();
	}
}
