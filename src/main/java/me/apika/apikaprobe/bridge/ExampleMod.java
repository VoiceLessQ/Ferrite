package me.apika.apikaprobe.bridge;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerChunkEvents;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import me.apika.apikaprobe.worldgen.chunk.ChunkDecoratorTiming;
import me.apika.apikaprobe.worldgen.chunk.ChunkForceTrigger;
import me.apika.apikaprobe.worldgen.chunk.ChunkForcer;
import me.apika.apikaprobe.worldgen.chunk.ChunkPrewarmTrigger;
import me.apika.apikaprobe.worldgen.chunk.ChunkPrewarmer;
import me.apika.apikaprobe.redstone.RedstoneHandoff;
import me.apika.apikaprobe.redstone.RedstoneOracle;
import me.apika.apikaprobe.redstone.RedstoneRustDispatcher;
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
import me.apika.apikaprobe.monitor.GoalSelectorMonitor;
import me.apika.apikaprobe.monitor.HopperMonitor;
import me.apika.apikaprobe.monitor.HopperSlotMonitor;
import me.apika.apikaprobe.monitor.HopperHintMonitor;
import me.apika.apikaprobe.monitor.HopperPerSlotMonitor;
import me.apika.apikaprobe.monitor.ItemFrameMonitor;
import me.apika.apikaprobe.monitor.LookControlMonitor;
import me.apika.apikaprobe.monitor.MoveControlMonitor;
import me.apika.apikaprobe.monitor.TargetScanMonitor;
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
		TargetScanMonitor.register();
		GoalSelectorMonitor.register();
		MoveControlMonitor.register();
		LookControlMonitor.register();
		HopperMonitor.register();
		HopperSlotMonitor.register();
		HopperHintMonitor.register();
		HopperPerSlotMonitor.register();
		ItemFrameMonitor.register();
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
		// DISABLED on 26.1.2: ServerChunkEvents.CHUNK_LOAD's lambda type
		// uses class_3218 / class_2818 (intermediary names) in the fabric-api
		// jar, which our architectury-loom + disableObfuscation setup doesn't
		// remap to mojmap (ServerLevel / LevelChunk).  The eviction is a
		// memory-housekeeping nicety, not correctness-critical.  Re-enable
		// once fabric-api remapping is sorted, or replace with a mixin into
		// ServerChunkCache.onChunkReadyToSend.

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
