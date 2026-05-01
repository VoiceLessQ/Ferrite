package me.apika.apikaprobe.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import me.apika.apikaprobe.monitor.WorldTickMonitor;

import net.minecraft.world.level.Level;

/**
 * Times {@code Level.tickBlockEntities()} at HEAD and RETURN.
 *
 * Level.tickBlockEntities is declared on the abstract Level and called
 * from both ServerLevel.tick() and ClientLevel.tickEntities(). We only
 * want server-side samples, so the handlers early-out when isClient.
 */
@Mixin(Level.class)
public abstract class WorldTickMixin {

	@Inject(method = "tickBlockEntities", at = @At("HEAD"))
	private void ferrite$onBlockEntitiesBegin(CallbackInfo ci) {
		if (((Level) (Object) this).isClient()) {
			return;
		}
		WorldTickMonitor.onBlockEntitiesBegin();
	}

	@Inject(method = "tickBlockEntities", at = @At("RETURN"))
	private void ferrite$onBlockEntitiesEnd(CallbackInfo ci) {
		if (((Level) (Object) this).isClient()) {
			return;
		}
		WorldTickMonitor.onBlockEntitiesEnd();
	}
}
