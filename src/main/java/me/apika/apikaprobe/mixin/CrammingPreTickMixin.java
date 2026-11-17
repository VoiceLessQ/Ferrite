package me.apika.apikaprobe.mixin;

import java.util.function.BooleanSupplier;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import me.apika.apikaprobe.entity.CrammingDispatcher;

import net.minecraft.server.level.ServerLevel;

@Mixin(ServerLevel.class)
public abstract class CrammingPreTickMixin {

	@Inject(
		method = "tick(Ljava/util/function/BooleanSupplier;)V",
		at = @At(value = "CONSTANT", args = "stringValue=entities")
	)
	private void ferrite$onPreEntityTick(BooleanSupplier shouldKeepTicking, CallbackInfo ci) {
		CrammingDispatcher.onPreEntityTick((ServerLevel) (Object) this);
	}
}
