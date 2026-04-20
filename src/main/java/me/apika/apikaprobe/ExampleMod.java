package me.apika.apikaprobe;

import net.fabricmc.api.ModInitializer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class ExampleMod implements ModInitializer {
	public static final String MOD_ID = "ferrite";
	public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

	@Override
	public void onInitialize() {
		LOGGER.info("Hello Fabric world!");

		TpsMonitor.register();
		// NoiseStageMonitor and AquiferMonitor must register BEFORE ChunkGenMonitor
		// so their tick listeners fire first and can read sync-noise counters
		// pre-reset.
		NoiseStageMonitor.register();
		AquiferMonitor.register();
		TerrainBulkHandoff.register();
		ChunkGenMonitor.register();
		SurfacePhaseMonitor.register();
		// ServerTickPhaseMonitor must register BEFORE WorldTickMonitor so its
		// END_SERVER_TICK handler fires first and reads
		// WorldTickMonitor.getEntityPlusBlockEntityNs() before that monitor
		// resets its cumulative counters.
		ServerTickPhaseMonitor.register();
		WorldTickMonitor.register();
		EntityTickMonitor.register();
		// MovementInternalsMonitor must register BEFORE MonsterPhaseMonitor so
		// its END_SERVER_TICK listener fires first and reads
		// MonsterPhaseMonitor.getMovementSelfNs() before that monitor resets.
		MovementInternalsMonitor.register();
		MonsterPhaseMonitor.register();
		// PreChunkMonitor must register BEFORE PreChunkDispatcher so its
		// END_SERVER_TICK report handler fires first and reads the window
		// before the dispatcher's handler increments it further.
		PreChunkMonitor.register();
		PreChunkDispatcher.register();
		RedstonePhaseMonitor.register();
		RedstoneOracle.register();
		FerriteCommand.register();

		if (!RustBridge.NATIVE_AVAILABLE) {
			LOGGER.warn("Native engine unavailable — falling back to pure Java.");
			return;
		}

		int threads = RustBridge.initEngine();
		LOGGER.info("[rust-engine] Rayon pool size = {}", threads);
	}
}
