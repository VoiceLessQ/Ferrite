package me.apika.apikaprobe.mixin.client;

import me.apika.apikaprobe.bridge.ExampleMod;
import me.apika.apikaprobe.worldgen.chunk.PregenWorldOptions;

import net.minecraft.client.gui.screens.worldselection.CreateWorldScreen;
import net.minecraft.client.gui.screens.worldselection.WorldCreationUiState;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * If the pre-gen toggle on the MoreTab is on, stash a request keyed by
 * the world folder name. The SERVER_STARTED handler reads the stash
 * once the IntegratedServer comes up.
 */
@Mixin(CreateWorldScreen.class)
public abstract class CreateWorldScreenPregenMixin {
	@Shadow
	@org.spongepowered.asm.mixin.Final
	private WorldCreationUiState uiState;

	@Inject(method = "onCreate", at = @At("HEAD"))
	private void ferrite$stashPregenRequest(CallbackInfo ci) {
		boolean enabled = PregenWorldOptions.isUiEnabled();
		String folder = uiState.getTargetFolder();
		String displayName = uiState.getName() != null ? uiState.getName().trim() : null;
		int radius = PregenWorldOptions.getUiRadius();
		ExampleMod.LOGGER.info(
				"[ferrite-pregen] onCreate: enabled={} folder='{}' displayName='{}' radius={}",
				enabled, folder, displayName, radius);
		if (!enabled) {
			PregenWorldOptions.clearRequest();
			return;
		}
		// Server side reads server.getWorldData().getLevelName(), which is the
		// trimmed display name (LevelSettings ctor source is uiState.getName().trim()).
		// Key the request by the same string so the SERVER_STARTED consume matches.
		String key = displayName != null && !displayName.isEmpty() ? displayName : folder;
		if (key == null || key.isEmpty()) return;
		PregenWorldOptions.setRequest(key, radius);
	}
}
