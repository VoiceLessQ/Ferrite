package me.apika.apikaprobe.bridge;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerChunkEvents;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import me.apika.apikaprobe.ChunkDecoratorTiming;
import me.apika.apikaprobe.ChunkForceTrigger;
import me.apika.apikaprobe.ChunkForcer;
import me.apika.apikaprobe.ChunkPrewarmTrigger;
import me.apika.apikaprobe.ChunkPrewarmer;
import me.apika.apikaprobe.RedstoneHandoff;
import me.apika.apikaprobe.RedstoneOracle;
import me.apika.apikaprobe.RedstoneRustDispatcher;
import me.apika.apikaprobe.RustBridge;
import me.apika.apikaprobe.command.FerriteCommand;
import me.apika.apikaprobe.entity.CrammingDispatcher;
import me.apika.apikaprobe.entity.PhysicsDispatcher;
import me.apika.apikaprobe.entity.PhysicsOracle;
import me.apika.apikaprobe.worldgen.TerrainBulkHandoff;
import me.apika.apikaprobe.worldgen.WorldgenStateBootstrap;
import me.apika.apikaprobe.monitor.AquiferMonitor;
import me.apika.apikaprobe.monitor.ChunkGenMonitor;
import me.apika.apikaprobe.monitor.EntityTickMonitor;
import me.apika.apikaprobe.monitor.LightTimingMonitor;
import me.apika.apikaprobe.monitor.MonsterPhaseMonitor;
import me.apika.apikaprobe.monitor.MovementInternalsMonitor;
import me.apika.apikaprobe.monitor.NoiseStageMonitor;
import me.apika.apikaprobe.monitor.PreChunkDispatcher;
import me.apika.apikaprobe.monitor.PreChunkMonitor;
import me.apika.apikaprobe.monitor.RedstonePhaseMonitor;
import me.apika.apikaprobe.monitor.ServerTickPhaseMonitor;
import me.apika.apikaprobe.monitor.SurfacePhaseMonitor;
import me.apika.apikaprobe.monitor.TpsMonitor;
import me.apika.apikaprobe.monitor.WorldTickMonitor;

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
		PhysicsOracle.register();
		// PreChunkMonitor must register BEFORE PreChunkDispatcher so its
		// END_SERVER_TICK report handler fires first and reads the window
		// before the dispatcher's handler increments it further.
		PreChunkMonitor.register();
		PreChunkDispatcher.register();
		RedstonePhaseMonitor.register();
		RedstoneOracle.register();
		RedstoneRustDispatcher.register();
		FerriteCommand.register();
		WorldgenStateBootstrap.register();
		ChunkDecoratorTiming.register();
		LightTimingMonitor.register();
		ChunkPrewarmTrigger.register();
		ChunkForcer.register();
		ChunkForceTrigger.register();
		// Vanilla loaded a chunk — drop our biome prediction for it.
		// Vanilla now owns the authoritative biome data; keeping our
		// cached int[1536] would just hog memory for chunks the cache
		// will never serve again.
		ServerChunkEvents.CHUNK_LOAD.register((world, chunk) -> {
			net.minecraft.util.math.ChunkPos pos = chunk.getPos();
			ChunkPrewarmer.evict(pos.x, pos.z);
		});

		if (!RustBridge.NATIVE_AVAILABLE) {
			// Explicitly disable every Rust-backed dispatcher so vanilla
			// behavior is restored immediately, instead of relying on each
			// dispatch site's per-call NATIVE_AVAILABLE guard. This makes
			// the fallback state legible in a single place and prevents a
			// regression if a future change removes one of those guards.
			//
			// FerriteWireConfig (pure-Java Alternate Current redstone) is
			// intentionally not touched — it has no native dependency.
			CrammingDispatcher.ENABLED = false;
			PhysicsDispatcher.ENABLED = false;
			RedstoneHandoff.USE_RUST = false;
			LOGGER.warn("Native engine unavailable — Rust-backed paths disabled, vanilla behavior restored.");
			return;
		}

		int threads = RustBridge.initEngine();
		LOGGER.info("[rust-engine] Rayon pool size = {}", threads);
	}
}
