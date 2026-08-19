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
import me.apika.apikaprobe.worldgen.chunk.PregenLifecycle;
import me.apika.apikaprobe.redstone.RedstoneOracle;
import me.apika.apikaprobe.RustBridge;
import me.apika.apikaprobe.command.FerriteCommand;
import me.apika.apikaprobe.entity.CrammingDispatcher;
import me.apika.apikaprobe.entity.PhysicsDispatcher;
import me.apika.apikaprobe.entity.PhysicsOracle;
import me.apika.apikaprobe.worldgen.TerrainBulkHandoff;
import me.apika.apikaprobe.worldgen.WorldgenStateBootstrap;
import me.apika.apikaprobe.monitor.AquiferMonitor;
import me.apika.apikaprobe.monitor.ChunkGenMonitor;
import me.apika.apikaprobe.monitor.EntityQueryMonitor;
import me.apika.apikaprobe.monitor.EntityTickMonitor;
import me.apika.apikaprobe.monitor.FerriteDispatcherProbe;
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
import me.apika.apikaprobe.monitor.HopperSlotMonitor;
import me.apika.apikaprobe.monitor.HopperHintMonitor;
import me.apika.apikaprobe.monitor.HopperPerSlotMonitor;
import me.apika.apikaprobe.monitor.ItemFrameMonitor;
import me.apika.apikaprobe.monitor.LightUpdateMonitor;
import me.apika.apikaprobe.monitor.LookControlMonitor;
import me.apika.apikaprobe.monitor.MoveControlMonitor;
import me.apika.apikaprobe.monitor.NavigationMonitor;
import me.apika.apikaprobe.monitor.TargetScanMonitor;
import me.apika.apikaprobe.monitor.TpsMonitor;
import me.apika.apikaprobe.monitor.WorldTickMonitor;

public class ExampleMod implements ModInitializer {
	public static final String MOD_ID = "ferrite";
	public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

	@Override
	public void onInitialize() {
		LOGGER.info("Hello Fabric world!");
		me.apika.apikaprobe.config.FerriteConfig.load();
		// One-line hardware stamp so shared logs self-describe their host.
		LOGGER.info("[hw] arch={} cores={} maxHeap={}MB jvm={} native={} monitors={}",
				System.getProperty("os.arch"),
				Runtime.getRuntime().availableProcessors(),
				Runtime.getRuntime().maxMemory() / (1024 * 1024),
				System.getProperty("java.vm.name"),
				me.apika.apikaprobe.RustBridge.NATIVE_AVAILABLE,
				me.apika.apikaprobe.monitor.MonitorLog.ENABLED
						? "on" : (me.apika.apikaprobe.monitor.MonitorLog.SMALL_HEAP
								? "off(small-heap)" : "off"));

		TpsMonitor.register();
		// NoiseStageMonitor and AquiferMonitor must register BEFORE ChunkGenMonitor
		// so their tick listeners fire first and can read sync-noise counters
		// pre-reset.
		NoiseStageMonitor.register();
		AquiferMonitor.register();
		TerrainBulkHandoff.register();
		ChunkGenMonitor.register();
		LightUpdateMonitor.register();
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
		me.apika.apikaprobe.monitor.ChunkArrivalMonitor.register();
		// Bench must register after the monitor so it reads this tick's deficit.
		me.apika.apikaprobe.monitor.ArrivalFlightBench.register();
		EntityQueryMonitor.register();
		me.apika.apikaprobe.spatial.ColliderSkip.register();
		GoalSelectorMonitor.register();
		MoveControlMonitor.register();
		LookControlMonitor.register();
		NavigationMonitor.register();
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
		FerriteDispatcherProbe.register();
		FerriteCommand.register();
		WorldgenStateBootstrap.register();
		ChunkDecoratorTiming.register();
		LightTimingMonitor.register();
		ChunkPrewarmTrigger.register();
		ChunkForcer.register();
		ChunkForceTrigger.register();
		PregenLifecycle.register();
		// Vanilla loaded a chunk: drop our biome prediction for it.
		// Vanilla now owns the authoritative biome data; keeping our
		// cached int[1536] would just hog memory for chunks the cache
		// will never serve again.  Third lambda param is the
		// generated-this-tick flag (added in fabric-api 4.x); we treat
		// loaded and freshly-generated identically for eviction.
		ServerChunkEvents.CHUNK_LOAD.register((world, chunk, generated) -> {
			net.minecraft.world.level.ChunkPos pos = chunk.getPos();
			ChunkPrewarmer.evict(pos.x(), pos.z());
		});
		// Parity capture mixins append per world load and would otherwise
		// pin every RandomState / biome source graph across open/quit
		// cycles in one JVM session.
		net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents.SERVER_STOPPED.register(server -> {
			me.apika.apikaprobe.worldgen.WorldgenParity.clearCaptures();
			me.apika.apikaprobe.worldgen.BiomeParity.clearCaptures();
		});
		// Nav cache eviction lifecycle. Gate on the raw property, not
		// WALK_CACHE_ENABLED, so the bridge class (~82 KB eager statics)
		// never loads when the feature is off.
		if (Boolean.parseBoolean(System.getProperty("ferrite.nav.cache", "false"))) {
			ServerChunkEvents.CHUNK_UNLOAD.register((world, chunk) -> {
				net.minecraft.world.level.ChunkPos pos = chunk.getPos();
				me.apika.apikaprobe.navigation.NavigationCacheBridge.onChunkUnloaded(
					pos.x(), pos.z(), world.getMinSectionY(), world.getSectionsCount());
			});
			net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents.SERVER_STOPPED.register(server ->
				me.apika.apikaprobe.navigation.NavigationCacheBridge.onServerStopped());
		}

		if (!RustBridge.NATIVE_AVAILABLE) {
			// Explicitly disable every Rust-backed dispatcher so vanilla
			// behavior is restored immediately, instead of relying on each
			// dispatch site's per-call NATIVE_AVAILABLE guard. This makes
			// the fallback state legible in a single place and prevents a
			// regression if a future change removes one of those guards.
			//
			// FerriteWireConfig (pure-Java Alternate Current redstone) is
			// intentionally not touched — it has no native dependency.
			// AC's Rust BFS step has its own NATIVE_AVAILABLE guard inside
			// WireHandler.runRustBatch.
			CrammingDispatcher.ENABLED = false;
			PhysicsDispatcher.ENABLED = false;
			LOGGER.warn("Native engine unavailable — Rust-backed paths disabled, vanilla behavior restored.");
			return;
		}

		int threads = RustBridge.initEngine();
		LOGGER.info("[rust-engine] Rayon pool size = {}", threads);
	}
}
