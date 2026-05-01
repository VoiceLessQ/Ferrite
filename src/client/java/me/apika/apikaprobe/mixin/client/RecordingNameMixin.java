package me.apika.apikaprobe.mixin.client;

import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.player.Player;
import net.minecraft.network.chat.Component;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(Player.class)
public abstract class RecordingNameMixin {

	@Inject(method = "getDisplayName", at = @At("RETURN"), cancellable = true)
	private void apikaprobe$hideLocalPlayerName(CallbackInfoReturnable<Component> cir) {
		Player self = (Player) (Object) this;
		if (!self.level().isClientSide()) return;
		if (Minecraft.getInstance().player != self) return;
		cir.setReturnValue(Component.empty());
	}
}
