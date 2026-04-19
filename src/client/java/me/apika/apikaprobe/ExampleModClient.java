package me.apika.apikaprobe;

import net.fabricmc.api.ClientModInitializer;

public class ExampleModClient implements ClientModInitializer {
	@Override
	public void onInitializeClient() {
		ClientLagMonitor.register();
		EntityRenderMonitor.register();
	}
}
