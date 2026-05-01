package me.apika.apikaprobe.command;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;

import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;

import me.apika.apikaprobe.worldgen.BiomeParity;
import me.apika.apikaprobe.worldgen.chunk.ChunkForcer;
import me.apika.apikaprobe.worldgen.chunk.ChunkPrewarmer;
import me.apika.apikaprobe.entity.CrammingDispatcher;
import me.apika.apikaprobe.worldgen.DensityParity;
import me.apika.apikaprobe.bridge.ExampleMod;
import me.apika.apikaprobe.redstone.RedstoneHandoff;
import me.apika.apikaprobe.worldgen.RustAquiferDispatch;
import me.apika.apikaprobe.worldgen.RustBiomeRouter;
import me.apika.apikaprobe.RustBridge;
import me.apika.apikaprobe.worldgen.RustFinalDensityBufferWrapper;
import me.apika.apikaprobe.worldgen.RustFlatCache;
import me.apika.apikaprobe.worldgen.WorldgenParity;
import me.apika.apikaprobe.worldgen.WorldgenStateBootstrap;
import me.apika.apikaprobe.redstone.FerriteWireConfig;
import me.apika.apikaprobe.redstone.RedstoneQueueBench;
import me.apika.apikaprobe.surface.CompiledRuleTree;
import me.apika.apikaprobe.surface.SurfaceDispatcher;
import me.apika.apikaprobe.surface.SurfaceRuleAccess;
import me.apika.apikaprobe.surface.SurfaceRuleCompiler;
import me.apika.apikaprobe.surface.SurfaceBatchHandoff;
import me.apika.apikaprobe.surface.SurfaceRuleDisassembler;
import me.apika.apikaprobe.surface.ColumnContext;
import me.apika.apikaprobe.surface.SurfaceRuleEvaluator;
import me.apika.apikaprobe.surface.SurfaceValidator;
import me.apika.apikaprobe.monitor.FerriteDispatcherProbe;

import net.minecraft.commands.Commands;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.network.chat.Component;

/**
 * /ferrite command — runtime toggles for Ferrite's optional paths.
 *
 * Current surface:
 *   /ferrite redstone rust on     — route wire cascades through Rust BFS
 *   /ferrite redstone rust off    — back to vanilla cascade
 *   /ferrite redstone rust status — report current flag + native availability
 *
 *   /ferrite redstone ac on       — route wire cascades through the
 *                                   Alternate-Current-derived Java algorithm
 *   /ferrite redstone ac off      — back to vanilla cascade
 *   /ferrite redstone ac status   — report current flag + update order
 *
 * rust and ac are independent: rust takes effect via the per-cascade
 * Rust BFS mixin, ac takes effect via FerriteRedstoneController.
 * Don't enable both at the same time — the Rust path expects to see
 * DefaultRedstoneWireEvaluator's behavior underneath, not AC's.
 *
 * All subcommands require op-level 2. Logs to both the command feedback
 * channel (visible in chat) and the [ferrite] logger (visible in
 * latest.log alongside the phase monitor lines) so comparative runs
 * are easy to correlate after the fact.
 */
public final class FerriteCommand {

	private FerriteCommand() {}

	public static void register() {
		CommandRegistrationCallback.EVENT.register(
				(dispatcher, registryAccess, environment) -> registerRoot(dispatcher));
	}

	private static void registerRoot(CommandDispatcher<CommandSourceStack> dispatcher) {
		dispatcher.register(Commands.literal("ferrite")
				.requires(Commands.requirePermissionLevel(Commands.LEVEL_GAMEMASTERS))
				.then(Commands.literal("cramming")
						.then(Commands.literal("on").executes(FerriteCommand::enableCramming))
						.then(Commands.literal("off").executes(FerriteCommand::disableCramming))
						.then(Commands.literal("status").executes(FerriteCommand::statusCramming)))
				.then(Commands.literal("hopper")
						.then(Commands.literal("highway")
								.then(Commands.literal("on").executes(FerriteCommand::enableHopper))
								.then(Commands.literal("off").executes(FerriteCommand::disableHopper))
								.then(Commands.literal("status").executes(FerriteCommand::statusHopper))))
				.then(Commands.literal("redstone")
						.then(Commands.literal("rust")
								.then(Commands.literal("on").executes(FerriteCommand::enableRust))
								.then(Commands.literal("off").executes(FerriteCommand::disableRust))
								.then(Commands.literal("status").executes(FerriteCommand::statusRust)))
						.then(Commands.literal("ac")
								.then(Commands.literal("on").executes(FerriteCommand::enableAc))
								.then(Commands.literal("off").executes(FerriteCommand::disableAc))
								.then(Commands.literal("status").executes(FerriteCommand::statusAc)))
						.then(Commands.literal("bench").executes(FerriteCommand::redstoneBench))
						.then(Commands.literal("bfs")
								.then(Commands.literal("on").executes(FerriteCommand::enableBfs))
								.then(Commands.literal("off").executes(FerriteCommand::disableBfs))
								.then(Commands.literal("status").executes(FerriteCommand::statusBfs)))
						.then(Commands.literal("bfs-min")
								.then(Commands.argument("n", IntegerArgumentType.integer(1, 4096))
										.executes(FerriteCommand::setBfsMin))))
				.then(Commands.literal("worldgen")
						.then(Commands.literal("status").executes(FerriteCommand::worldgenStatus))
						.then(Commands.literal("sample")
								.then(Commands.argument("name", StringArgumentType.greedyString())
										.executes(FerriteCommand::worldgenSample)))
						.then(Commands.literal("validate")
								.executes(ctx -> worldgenValidate(ctx, 100))
								.then(Commands.argument("samples", IntegerArgumentType.integer(1, 100000))
										.executes(ctx -> worldgenValidate(ctx,
												IntegerArgumentType.getInteger(ctx, "samples"))))))
				.then(Commands.literal("density")
						.then(Commands.literal("validate")
								.executes(ctx -> densityValidate(ctx, 200))
								.then(Commands.argument("samples", IntegerArgumentType.integer(1, 100000))
										.executes(ctx -> densityValidate(ctx,
												IntegerArgumentType.getInteger(ctx, "samples")))))
						.then(Commands.literal("sample")
								.then(Commands.argument("name", StringArgumentType.greedyString())
										.executes(FerriteCommand::densitySample)))
						.then(Commands.literal("dump")
								.then(Commands.argument("name", StringArgumentType.greedyString())
										.executes(FerriteCommand::densityDump)))
						.then(Commands.literal("bench-region")
								.executes(FerriteCommand::densityBenchRegion))
						.then(Commands.literal("bench-buffer")
								.executes(FerriteCommand::densityBenchBuffer)))
				.then(Commands.literal("biome")
						.then(Commands.literal("validate")
								.executes(ctx -> biomeValidate(ctx, 1000))
								.then(Commands.argument("samples", IntegerArgumentType.integer(1, 1000000))
										.executes(ctx -> biomeValidate(ctx,
												IntegerArgumentType.getInteger(ctx, "samples")))))
						.then(Commands.literal("at")
								.executes(FerriteCommand::biomeAtPlayer)
								.then(Commands.argument("x", IntegerArgumentType.integer())
										.then(Commands.argument("y", IntegerArgumentType.integer())
												.then(Commands.argument("z", IntegerArgumentType.integer())
														.executes(FerriteCommand::biomeAtCoords)))))
						.then(Commands.literal("chunk")
								.then(Commands.argument("cx", IntegerArgumentType.integer())
										.then(Commands.argument("cz", IntegerArgumentType.integer())
												.executes(FerriteCommand::biomeAtChunk))))
						.then(Commands.literal("actual")
								.then(Commands.argument("x", IntegerArgumentType.integer())
										.then(Commands.argument("y", IntegerArgumentType.integer())
												.then(Commands.argument("z", IntegerArgumentType.integer())
														.executes(FerriteCommand::biomeActual)))))
						.then(Commands.literal("compare")
								.then(Commands.argument("x", IntegerArgumentType.integer())
										.then(Commands.argument("y", IntegerArgumentType.integer())
												.then(Commands.argument("z", IntegerArgumentType.integer())
														.executes(FerriteCommand::biomeCompare)))))
						.then(Commands.literal("rust")
								.executes(FerriteCommand::biomeRustAtPlayer)
								.then(Commands.argument("x", IntegerArgumentType.integer())
										.then(Commands.argument("y", IntegerArgumentType.integer())
												.then(Commands.argument("z", IntegerArgumentType.integer())
														.executes(FerriteCommand::biomeRustAtCoords)))))
						.then(Commands.literal("predict")
								.executes(ctx -> biomePredict(ctx, 256))
								.then(Commands.argument("radius", IntegerArgumentType.integer(16, 8192))
										.executes(ctx -> biomePredict(ctx,
												IntegerArgumentType.getInteger(ctx, "radius")))))
						.then(Commands.literal("route")
								.then(Commands.literal("on").executes(FerriteCommand::biomeRouteOn))
								.then(Commands.literal("off").executes(FerriteCommand::biomeRouteOff))
								.then(Commands.literal("status").executes(FerriteCommand::biomeRouteStatus))))
				.then(Commands.literal("prewarm")
						.then(Commands.literal("on").executes(FerriteCommand::prewarmOn))
						.then(Commands.literal("off").executes(FerriteCommand::prewarmOff))
						.then(Commands.literal("status").executes(FerriteCommand::prewarmStatus))
						.then(Commands.literal("clear").executes(FerriteCommand::prewarmClear)))
				.then(Commands.literal("chunkforce")
						.then(Commands.literal("on").executes(FerriteCommand::chunkForceOn))
						.then(Commands.literal("off").executes(FerriteCommand::chunkForceOff))
						.then(Commands.literal("status").executes(FerriteCommand::chunkForceStatus)))
				.then(Commands.literal("noise")
						.then(Commands.literal("rust")
								.then(Commands.literal("on").executes(FerriteCommand::noiseRustOn))
								.then(Commands.literal("off").executes(FerriteCommand::noiseRustOff))
								.then(Commands.literal("status").executes(FerriteCommand::noiseRustStatus))
								.then(Commands.literal("diag").executes(FerriteCommand::noiseRustDiag))
								.then(Commands.literal("reset").executes(FerriteCommand::noiseRustReset))))
				.then(Commands.literal("surface")
						.then(Commands.literal("compile").executes(FerriteCommand::surfaceCompile))
						.then(Commands.literal("stats").executes(FerriteCommand::surfaceStats))
						.then(Commands.literal("validate").executes(FerriteCommand::surfaceValidate))
						.then(Commands.literal("validate-off").executes(FerriteCommand::surfaceValidateOff))
						.then(Commands.literal("validate-stats").executes(FerriteCommand::surfaceValidateStats))
						.then(Commands.literal("batch-test").executes(FerriteCommand::surfaceBatchTest))
						.then(Commands.literal("trace-next").executes(FerriteCommand::surfaceTraceNext))
						.then(Commands.literal("dump-biomes").executes(FerriteCommand::surfaceDumpBiomes))
						.then(Commands.literal("dump").executes(FerriteCommand::surfaceDump))
						.then(Commands.literal("dispatch")
								.then(Commands.literal("on").executes(FerriteCommand::enableSurfaceDispatch))
								.then(Commands.literal("off").executes(FerriteCommand::disableSurfaceDispatch))
								.then(Commands.literal("status").executes(FerriteCommand::statusSurfaceDispatch)))
						.then(Commands.literal("heightmap-parity")
								.then(Commands.literal("on").executes(FerriteCommand::heightmapParityOn))
								.then(Commands.literal("off").executes(FerriteCommand::heightmapParityOff))
								.then(Commands.literal("stats").executes(FerriteCommand::heightmapParityStats))
								.then(Commands.literal("reset").executes(FerriteCommand::heightmapParityReset))))
				.then(Commands.literal("aquifer")
						.then(Commands.literal("rust")
								.then(Commands.literal("on").executes(FerriteCommand::aquiferRustOn))
								.then(Commands.literal("off").executes(FerriteCommand::aquiferRustOff))
								.then(Commands.literal("status").executes(FerriteCommand::aquiferRustStatus)))
						.then(Commands.literal("parity")
								.then(Commands.literal("on").executes(FerriteCommand::aquiferParityOn))
								.then(Commands.literal("off").executes(FerriteCommand::aquiferParityOff))
								.then(Commands.literal("reset").executes(FerriteCommand::aquiferParityReset))))
				.then(Commands.literal("probe")
						.then(Commands.literal("dispatcher")
								.then(Commands.literal("on").executes(FerriteCommand::dispatcherProbeOn))
								.then(Commands.literal("off").executes(FerriteCommand::dispatcherProbeOff))
								.then(Commands.literal("status").executes(FerriteCommand::dispatcherProbeStatus))
								.then(Commands.literal("reset").executes(FerriteCommand::dispatcherProbeReset)))));
	}

	/**
	 * Toggle Ferrite's batched mob-vs-mob cramming dispatcher. When OFF,
	 * the cancel mixin no-ops and vanilla LivingEntity.tickCramming runs
	 * unmodified — including vanilla cramming damage. Lets users A/B
	 * the perf claim ("stable TPS at 1000+ mobs") in their own world.
	 * Volatile, not persisted.
	 */
	private static int enableCramming(com.mojang.brigadier.context.CommandContext<CommandSourceStack> ctx) {
		CrammingDispatcher.ENABLED = true;
		String msg = "[cramming] Ferrite cramming enabled — batched Rust path active (vanilla cramming damage NOT applied)";
		sendFeedback(ctx, msg, true);
		ExampleMod.LOGGER.info(msg);
		return Command.SINGLE_SUCCESS;
	}

	private static int disableCramming(com.mojang.brigadier.context.CommandContext<CommandSourceStack> ctx) {
		CrammingDispatcher.ENABLED = false;
		String msg = "[cramming] Ferrite cramming disabled — vanilla path active (cramming damage will fire per maxEntityCramming gamerule)";
		sendFeedback(ctx, msg, true);
		ExampleMod.LOGGER.info(msg);
		return Command.SINGLE_SUCCESS;
	}

	private static int statusCramming(com.mojang.brigadier.context.CommandContext<CommandSourceStack> ctx) {
		String msg = String.format(
			"[cramming] ENABLED=%s native=%s  (watch [cramming-dispatch] in latest.log for batch counts)",
			CrammingDispatcher.ENABLED,
			RustBridge.NATIVE_AVAILABLE ? "available" : "MISSING");
		sendFeedback(ctx, msg, false);
		ExampleMod.LOGGER.info(msg);
		return Command.SINGLE_SUCCESS;
	}

	private static int enableHopper(com.mojang.brigadier.context.CommandContext<CommandSourceStack> ctx) {
		me.apika.apikaprobe.monitor.HopperHintMonitor.USE_HINT = true;
		me.apika.apikaprobe.hopper.PerSlotFireConfig.ENABLE = true;
		me.apika.apikaprobe.hopper.HopperLaneRouteConfig.ENABLE = true;
		String msg = "[hopper] Ferrite hopper layer ENABLED (extract hint + per-slot fire + lane routing)";
		sendFeedback(ctx, msg, true);
		ExampleMod.LOGGER.info(msg);
		return Command.SINGLE_SUCCESS;
	}

	private static int disableHopper(com.mojang.brigadier.context.CommandContext<CommandSourceStack> ctx) {
		me.apika.apikaprobe.monitor.HopperHintMonitor.USE_HINT = false;
		me.apika.apikaprobe.hopper.PerSlotFireConfig.ENABLE = false;
		me.apika.apikaprobe.hopper.HopperLaneRouteConfig.ENABLE = false;
		String msg = "[hopper] Ferrite hopper layer DISABLED, vanilla hopper paths active";
		sendFeedback(ctx, msg, true);
		ExampleMod.LOGGER.info(msg);
		return Command.SINGLE_SUCCESS;
	}

	private static int statusHopper(com.mojang.brigadier.context.CommandContext<CommandSourceStack> ctx) {
		String msg = String.format(
			"[hopper] hint=%s perslot=%s lane=%s  (validate flags: hint=%s perslot=%s)",
			me.apika.apikaprobe.monitor.HopperHintMonitor.USE_HINT,
			me.apika.apikaprobe.hopper.PerSlotFireConfig.ENABLE,
			me.apika.apikaprobe.hopper.HopperLaneRouteConfig.ENABLE,
			me.apika.apikaprobe.monitor.HopperHintMonitor.VALIDATE,
			me.apika.apikaprobe.hopper.PerSlotFireConfig.VALIDATE);
		sendFeedback(ctx, msg, false);
		ExampleMod.LOGGER.info(msg);
		return Command.SINGLE_SUCCESS;
	}

	/**
	 * Enables the Rust BFS dispatcher for the current server session only.
	 * Setting is held in a static volatile field, NOT persisted — flips
	 * back to the default ({@code false}) on server restart.
	 */
	private static int enableRust(com.mojang.brigadier.context.CommandContext<CommandSourceStack> ctx) {
		if (!RustBridge.NATIVE_AVAILABLE) {
			sendFeedback(ctx, "Rust native unavailable — flag set but no route will take effect.", false);
		}
		RedstoneHandoff.USE_RUST = true;
		sendFeedback(ctx, "[redstone] Rust BFS enabled (this session only)", true);
		ExampleMod.LOGGER.info("[redstone] Rust BFS enabled (via /ferrite, this session only)");
		return Command.SINGLE_SUCCESS;
	}

	private static int disableRust(com.mojang.brigadier.context.CommandContext<CommandSourceStack> ctx) {
		RedstoneHandoff.USE_RUST = false;
		sendFeedback(ctx, "[redstone] Rust BFS disabled (vanilla path)", true);
		ExampleMod.LOGGER.info("[redstone] Rust BFS disabled (via /ferrite)");
		return Command.SINGLE_SUCCESS;
	}

	private static int statusRust(com.mojang.brigadier.context.CommandContext<CommandSourceStack> ctx) {
		String msg = String.format(
				"[redstone] rust USE_RUST=%s native=%s  (watch latest.log for [redstone] phase numbers every 5s)",
				RedstoneHandoff.USE_RUST,
				RustBridge.NATIVE_AVAILABLE ? "available" : "MISSING");
		sendFeedback(ctx, msg, false);
		ExampleMod.LOGGER.info(msg);
		return Command.SINGLE_SUCCESS;
	}

	/**
	 * Enables the Alternate-Current wire algorithm for the current server
	 * session only. Setting is held in a static volatile field, NOT
	 * persisted — flips back to the default ({@code false}) on server
	 * restart. Re-issue the command after each restart if you want it on.
	 */
	private static int enableAc(com.mojang.brigadier.context.CommandContext<CommandSourceStack> ctx) {
		if (RedstoneHandoff.USE_RUST) {
			sendFeedback(ctx, "Warning: Rust BFS is also enabled. Running both paths at once is untested and not recommended.", false);
		}
		FerriteWireConfig.ENABLED = true;
		sendFeedback(ctx, "[redstone] Alternate-Current wire algorithm enabled (this session only)", true);
		ExampleMod.LOGGER.info("[redstone] AC wire algorithm enabled (via /ferrite, this session only)");
		return Command.SINGLE_SUCCESS;
	}

	private static int disableAc(com.mojang.brigadier.context.CommandContext<CommandSourceStack> ctx) {
		FerriteWireConfig.ENABLED = false;
		sendFeedback(ctx, "[redstone] Alternate-Current wire algorithm disabled (vanilla path)", true);
		ExampleMod.LOGGER.info("[redstone] AC wire algorithm disabled (via /ferrite)");
		return Command.SINGLE_SUCCESS;
	}

	/**
	 * /ferrite redstone bench — Phase 1 of the AC Rust core port
	 * (docs/REDSTONE_PORT_PLAN.md). Micro-benchmarks the Rust priority
	 * queue against Ferrite's Java {@link me.apika.apikaprobe.redstone.PriorityQueue}
	 * on workloads of N = 100, 1000, 10000.
	 *
	 * <p>Gate question: does Rust beat Java by ≥2× on 1000+ nodes
	 * after JNI overhead is counted? If yes → Phase 2 is green-lit.
	 * If no → stop, document, AC-Java is good enough.
	 */
	/**
	 * /ferrite redstone bfs on — enables Phase 2 of the AC Rust core
	 * port. Cascades with ≥{@link FerriteWireConfig#RUST_BFS_MIN_NODES}
	 * wires run their power propagation in one batched JNI call to
	 * the Rust kernel instead of Java's queue-based loop. Java's
	 * emission path (block updates, shape updates) still runs unchanged.
	 *
	 * <p>Default off — enable explicitly after validating against
	 * vanilla in your world. Requires AC also enabled
	 * ({@code /ferrite redstone ac on}); the BFS path runs inside
	 * {@code FerriteRedstoneController}.
	 */
	private static int enableBfs(com.mojang.brigadier.context.CommandContext<CommandSourceStack> ctx) {
		if (!RustBridge.NATIVE_AVAILABLE) {
			sendFeedback(ctx, "Rust native unavailable — flag set but no batch will run.", false);
		}
		FerriteWireConfig.RUST_BFS = true;
		String msg = "[redstone] Rust BFS Phase 2 enabled (this session only)";
		sendFeedback(ctx, msg, true);
		ExampleMod.LOGGER.info(msg);
		return Command.SINGLE_SUCCESS;
	}

	private static int disableBfs(com.mojang.brigadier.context.CommandContext<CommandSourceStack> ctx) {
		FerriteWireConfig.RUST_BFS = false;
		String msg = "[redstone] Rust BFS Phase 2 disabled (Java path)";
		sendFeedback(ctx, msg, true);
		ExampleMod.LOGGER.info(msg);
		return Command.SINGLE_SUCCESS;
	}

	/**
	 * /ferrite redstone bfs-min &lt;n&gt; — set the minimum cascade size
	 * (in wires) at which the Rust BFS path activates. Default 32. Setting
	 * to 1 forces Rust on every cascade, useful for forcing per-bucket
	 * timing data on small cascades that the production gate would skip.
	 * Volatile, not persisted.
	 */
	private static int setBfsMin(com.mojang.brigadier.context.CommandContext<CommandSourceStack> ctx) {
		int n = IntegerArgumentType.getInteger(ctx, "n");
		FerriteWireConfig.RUST_BFS_MIN_NODES = n;
		String msg = "[redstone] bfs-min set to " + n + " (production default is 1)";
		sendFeedback(ctx, msg, true);
		ExampleMod.LOGGER.info(msg);
		return Command.SINGLE_SUCCESS;
	}

	private static int statusBfs(com.mojang.brigadier.context.CommandContext<CommandSourceStack> ctx) {
		String msg = String.format(
			"[redstone] bfs RUST_BFS=%s minNodes=%d native=%s ac=%s",
			FerriteWireConfig.RUST_BFS,
			FerriteWireConfig.RUST_BFS_MIN_NODES,
			RustBridge.NATIVE_AVAILABLE ? "available" : "MISSING",
			FerriteWireConfig.ENABLED ? "on" : "off");
		sendFeedback(ctx, msg, false);
		ExampleMod.LOGGER.info(msg);
		return Command.SINGLE_SUCCESS;
	}

	private static int redstoneBench(com.mojang.brigadier.context.CommandContext<CommandSourceStack> ctx) {
		int[] sizes = {100, 1000, 10000};
		sendFeedback(ctx, "[redstone-bench] running (see latest.log for full results)", false);
		ExampleMod.LOGGER.info("[redstone-bench] Phase 1 priority queue bench — gate: Rust ≥2× Java at N≥1000");
		boolean gateMet = false;
		for (int n : sizes) {
			RedstoneQueueBench.Result r = RedstoneQueueBench.run(n);
			String line = String.format(
				"[redstone-bench] N=%d  java=%.3f ms  rust=%.3f ms  ratio=%.2fx  %s",
				r.n(), r.javaMedianMs(), r.rustMedianMs(), r.ratio(),
				r.rustWins2x() ? "✓ Rust wins ≥2×" : "· below 2× gate");
			sendFeedback(ctx, line, false);
			ExampleMod.LOGGER.info(line);
			if (n >= 1000 && r.rustWins2x()) {
				gateMet = true;
			}
		}
		String verdict = gateMet
			? "[redstone-bench] VERDICT: gate met on 1000+ nodes → Phase 2 green-lit"
			: "[redstone-bench] VERDICT: gate not met on 1000+ nodes → STOP, AC-Java is good enough";
		sendFeedback(ctx, verdict, false);
		ExampleMod.LOGGER.info(verdict);
		return Command.SINGLE_SUCCESS;
	}

	private static int statusAc(com.mojang.brigadier.context.CommandContext<CommandSourceStack> ctx) {
		String msg = String.format(
				"[redstone] ac ENABLED=%s update-order=%s  (watch latest.log for [redstone] phase numbers every 5s)",
				FerriteWireConfig.ENABLED,
				FerriteWireConfig.UPDATE_ORDER.id());
		sendFeedback(ctx, msg, false);
		ExampleMod.LOGGER.info(msg);
		return Command.SINGLE_SUCCESS;
	}

	/**
	 * /ferrite surface compile — extract the active world's surface
	 * rule, compile it through {@link SurfaceRuleCompiler}, and report
	 * opcode count, byte length, and fallback verdict. The fallback
	 * verdict is the headline number — until every condition node in
	 * the default tree has operand extraction, this will be true.
	 */
	private static int surfaceCompile(com.mojang.brigadier.context.CommandContext<CommandSourceStack> ctx) {
		SurfaceRuleAccess.Result extracted = SurfaceRuleAccess.extract(ctx.getSource().getLevel());
		if (!extracted.ok()) {
			String msg = "[surface] compile failed: " + extracted.error();
			sendFeedback(ctx, msg, false);
			ExampleMod.LOGGER.warn(msg);
			return 0;
		}
		CompiledRuleTree tree = SurfaceRuleCompiler.compile(extracted.surfaceRule());
		String msg = String.format(
			"[surface] compiled: opcodes=%d bytes=%d blockstates=%d biomeSets=%d noiseChannels=%d hasFallback=%s generator=%s",
			tree.opcodeCount(),
			tree.bytecode().length,
			tree.blockstateTable().length,
			tree.biomeSetTable().length,
			tree.noiseChannelTable().length,
			tree.hasFallback(),
			extracted.generatorClass());
		sendFeedback(ctx, msg, false);
		ExampleMod.LOGGER.info(msg);
		return Command.SINGLE_SUCCESS;
	}

	/**
	 * /ferrite surface stats — walks the active world's surface rule
	 * tree and reports a count per node simple-name. Unknown nodes
	 * appear as "_UNKNOWN:Foo" entries — those are what the operand
	 * extractor pass needs to handle next. Answers spec open-item #1.
	 */
	private static int surfaceStats(com.mojang.brigadier.context.CommandContext<CommandSourceStack> ctx) {
		SurfaceRuleAccess.Result extracted = SurfaceRuleAccess.extract(ctx.getSource().getLevel());
		if (!extracted.ok()) {
			String msg = "[surface] stats failed: " + extracted.error();
			sendFeedback(ctx, msg, false);
			ExampleMod.LOGGER.warn(msg);
			return 0;
		}
		java.util.Map<String, Integer> stats = SurfaceRuleCompiler.collectStats(extracted.surfaceRule());
		int total = stats.values().stream().mapToInt(Integer::intValue).sum();
		long unknown = stats.keySet().stream().filter(k -> k.startsWith("_UNKNOWN:")).count();
		String summary = String.format(
			"[surface] stats: %d total nodes, %d distinct types, %d unknown types",
			total, stats.size(), unknown);
		sendFeedback(ctx, summary, false);
		ExampleMod.LOGGER.info(summary);
		// Per-type breakdown — log only (chat would spam)
		ExampleMod.LOGGER.info("[surface] node type counts:");
		stats.forEach((name, count) -> ExampleMod.LOGGER.info("[surface]   {} = {}", name, count));
		sendFeedback(ctx, "[surface] full breakdown in latest.log under [surface]", false);
		return Command.SINGLE_SUCCESS;
	}

	/**
	 * /ferrite surface validate — compile the active world's surface
	 * rule and install it as the validator's active tree. The mixin
	 * picks up subsequent tryApply calls and diffs against vanilla.
	 * Mismatches log to [surface-validate]; rate-limited to first 50.
	 */
	/**
	 * Toggle the surface-rule dispatch swap. When ON, the validator
	 * mixin's tryApply redirect routes through Ferrite's bytecode
	 * evaluator instead of vanilla's MaterialRule tree walk; eval-
	 * returns-null falls through to vanilla as a safety net. Requires
	 * a compiled tree installed first via /ferrite surface validate.
	 *
	 * <p>Watch [surface-phase] in latest.log for the per-phase timing
	 * delta (the tryApply slot specifically) when toggling on/off.
	 * That's the perf signal for whether the bytecode evaluator beats
	 * vanilla's tree walk on the same workload.
	 */
	private static int enableSurfaceDispatch(com.mojang.brigadier.context.CommandContext<CommandSourceStack> ctx) {
		SurfaceDispatcher.ENABLED = true;
		String msg = SurfaceValidator.isEnabled()
				? "[surface-dispatch] enabled — tryApply now routes through bytecode evaluator (validator tree present)"
				: "[surface-dispatch] enabled BUT no tree installed — run /ferrite surface validate first or all calls fall through to vanilla";
		sendFeedback(ctx, msg, true);
		ExampleMod.LOGGER.info(msg);
		return Command.SINGLE_SUCCESS;
	}

	private static int disableSurfaceDispatch(com.mojang.brigadier.context.CommandContext<CommandSourceStack> ctx) {
		SurfaceDispatcher.ENABLED = false;
		String msg = "[surface-dispatch] disabled — tryApply runs vanilla (validator behavior restored if /ferrite surface validate is on)";
		sendFeedback(ctx, msg, true);
		ExampleMod.LOGGER.info(msg);
		return Command.SINGLE_SUCCESS;
	}

	private static int statusSurfaceDispatch(com.mojang.brigadier.context.CommandContext<CommandSourceStack> ctx) {
		String msg = String.format(
			"[surface-dispatch] ENABLED=%s treeInstalled=%s  (watch [surface-phase] tryApply slot for perf delta)",
			SurfaceDispatcher.ENABLED, SurfaceValidator.isEnabled());
		sendFeedback(ctx, msg, false);
		ExampleMod.LOGGER.info(msg);
		return Command.SINGLE_SUCCESS;
	}

	private static int heightmapParityOn(com.mojang.brigadier.context.CommandContext<CommandSourceStack> ctx) {
		me.apika.apikaprobe.surface.SurfaceHeightmapValidator.ENABLED = true;
		String msg = "[surface-heightmap-parity] ENABLED — diffing path-A (vanilla per-write trackUpdate) vs path-B (per-column batched) per chunk; requires /ferrite surface dispatch on";
		sendFeedback(ctx, msg, true);
		ExampleMod.LOGGER.info(msg);
		return Command.SINGLE_SUCCESS;
	}

	private static int heightmapParityOff(com.mojang.brigadier.context.CommandContext<CommandSourceStack> ctx) {
		me.apika.apikaprobe.surface.SurfaceHeightmapValidator.ENABLED = false;
		String msg = "[surface-heightmap-parity] disabled";
		sendFeedback(ctx, msg, true);
		ExampleMod.LOGGER.info(msg);
		return Command.SINGLE_SUCCESS;
	}

	private static int heightmapParityStats(com.mojang.brigadier.context.CommandContext<CommandSourceStack> ctx) {
		String msg = me.apika.apikaprobe.surface.SurfaceHeightmapValidator.statsLine();
		sendFeedback(ctx, msg, false);
		ExampleMod.LOGGER.info(msg);
		return Command.SINGLE_SUCCESS;
	}

	private static int heightmapParityReset(com.mojang.brigadier.context.CommandContext<CommandSourceStack> ctx) {
		me.apika.apikaprobe.surface.SurfaceHeightmapValidator.resetCounters();
		String msg = "[surface-heightmap-parity] counters reset";
		sendFeedback(ctx, msg, true);
		ExampleMod.LOGGER.info(msg);
		return Command.SINGLE_SUCCESS;
	}

	private static int surfaceValidate(com.mojang.brigadier.context.CommandContext<CommandSourceStack> ctx) {
		SurfaceRuleAccess.Result extracted = SurfaceRuleAccess.extract(ctx.getSource().getLevel());
		if (!extracted.ok()) {
			String msg = "[surface-validate] install failed: " + extracted.error();
			sendFeedback(ctx, msg, false);
			ExampleMod.LOGGER.warn(msg);
			return 0;
		}
		CompiledRuleTree tree = SurfaceRuleCompiler.compile(extracted.surfaceRule());
		SurfaceValidator.install(tree);
		String msg = String.format(
			"[surface-validate] enabled (bytes=%d hasFallback=%s) — load chunks to generate samples",
			tree.bytecode().length, tree.hasFallback());
		sendFeedback(ctx, msg, false);
		ExampleMod.LOGGER.info(msg);
		return Command.SINGLE_SUCCESS;
	}

	private static int surfaceValidateOff(com.mojang.brigadier.context.CommandContext<CommandSourceStack> ctx) {
		String finalStats = SurfaceValidator.statsLine();
		SurfaceValidator.uninstall();
		sendFeedback(ctx, "[surface-validate] disabled — final stats: " + finalStats, false);
		return Command.SINGLE_SUCCESS;
	}

	/**
	 * /ferrite surface batch-test — exercises the batch JNI path against
	 * the per-call path on synthetic columns and reports agreement.
	 *
	 * <p>Generates N=256 synthetic ColumnContexts with varied inputs (Y,
	 * runDepth, biome rotated through all biome-set-pool entries),
	 * evaluates each via {@link SurfaceRuleEvaluator#evaluateViaRust}
	 * (per-call) AND via {@link SurfaceBatchHandoff} (one batched call),
	 * then asserts blockstate IDs match column-for-column.
	 *
	 * <p>This is the correctness gate before swapping the dispatcher to
	 * batched mode — if per-call ≡ batched on synthetic input, the
	 * batched path is safe to use against real chunks.
	 */
	private static int surfaceBatchTest(com.mojang.brigadier.context.CommandContext<CommandSourceStack> ctx) {
		SurfaceRuleAccess.Result extracted = SurfaceRuleAccess.extract(ctx.getSource().getLevel());
		if (!extracted.ok()) {
			sendFeedback(ctx, "[surface-batch] extract failed: " + extracted.error(), false);
			return 0;
		}
		CompiledRuleTree tree = SurfaceRuleCompiler.compile(extracted.surfaceRule());
		final int N = 256;
		// Synthetic context generator. Vary inputs to exercise different
		// branches: Y across full overworld range, runDepth bouncing,
		// biome name rotated through one of the entries in each pool set
		// (so we hit a real biome match rather than always "unknown").
		java.util.List<String>[] biomePool = tree.biomeSetTable();
		String[] sampledBiomes = new String[N];
		for (int i = 0; i < N; i++) {
			if (biomePool.length == 0) { sampledBiomes[i] = "unknown"; continue; }
			java.util.List<String> entry = biomePool[i % biomePool.length];
			sampledBiomes[i] = entry.isEmpty() ? "unknown" : entry.get(0);
		}

		ColumnContext[] inputs = new ColumnContext[N];
		double[] zeroNoise = new double[tree.noiseChannelTable().length];
		for (int i = 0; i < N; i++) {
			int blockY = -64 + (i % 384);                  // sweep full overworld Y range
			int runDepth = (i * 7) % 60 - 30;              // -30..29
			int sda = (i * 3) % 12;                        // 0..11
			int sdb = (i * 5) % 12;                        // 0..11
			inputs[i] = new ColumnContext(
					sampledBiomes[i], blockY, runDepth, sda, sdb,
					i % 80,                                 // fluidHeight 0..79
					(i & 1) != 0, (i & 2) != 0,             // isCold/isSteep alternating
					i % 100,                                // surfaceHeight 0..99
					((i % 21) - 10) / 10.0,                 // secondaryDepth -1.0..0.95
					zeroNoise);
		}

		// Per-call results.
		Object[] perCall = new Object[N];
		for (int i = 0; i < N; i++) {
			perCall[i] = SurfaceRuleEvaluator.evaluateViaRust(tree, inputs[i]);
		}

		// Batched results.
		SurfaceBatchHandoff handoff = new SurfaceBatchHandoff();
		handoff.setTree(tree);
		handoff.beginBatch(N);
		for (int i = 0; i < N; i++) {
			handoff.packColumn(i, inputs[i]);
		}
		long t0 = System.nanoTime();
		handoff.dispatch();
		long batchNanos = System.nanoTime() - t0;

		Object[] batched = new Object[N];
		for (int i = 0; i < N; i++) {
			batched[i] = handoff.readResult(i);
		}

		// Diff.
		int agree = 0;
		int divergeFirst = -1;
		for (int i = 0; i < N; i++) {
			boolean same = java.util.Objects.equals(
					perCall[i] == null ? null : perCall[i].toString(),
					batched[i] == null ? null : batched[i].toString());
			if (same) agree++;
			else if (divergeFirst < 0) divergeFirst = i;
		}

		String msg = String.format(
				"[surface-batch] N=%d agree=%d/%d batchDispatch=%.3f ms/N=%.0f ns per col",
				N, agree, N, batchNanos / 1_000_000.0, (double) batchNanos / N);
		sendFeedback(ctx, msg, false);
		ExampleMod.LOGGER.info(msg);
		if (divergeFirst >= 0) {
			ColumnContext c = inputs[divergeFirst];
			String dmsg = String.format(
					"[surface-batch] first divergence at col %d: perCall=%s batched=%s (Y=%d runDepth=%d biome=%s)",
					divergeFirst,
					perCall[divergeFirst] == null ? "null" : perCall[divergeFirst].toString(),
					batched[divergeFirst] == null ? "null" : batched[divergeFirst].toString(),
					c.blockY(), c.runDepth(), c.biomeName());
			sendFeedback(ctx, dmsg, false);
			ExampleMod.LOGGER.warn(dmsg);
		}
		return Command.SINGLE_SUCCESS;
	}

	/**
	 * /ferrite surface trace-next — arms a one-shot flag in the validator.
	 * The next mismatch the validator sees gets a full opcode trace dump
	 * to {@code [surface-validate]} log lines, then the flag clears
	 * itself. Workflow: arm, teleport into a chunk-gen-active area, hit
	 * a mismatch, read the trace from latest.log to pinpoint which
	 * condition diverged.
	 */
	/**
	 * /ferrite surface dump-biomes — compiles the active world's surface
	 * rule and dumps the entire biome-set-pool table to the [surface]
	 * log. Each entry shows the pool ID and the full list of biome
	 * names in that set. Lets us verify whether a given biome (e.g.
	 * warm_ocean) is actually present in the sets it should be in.
	 */
	/**
	 * /ferrite surface dump — disassembles the active world's compiled
	 * surface rule bytecode and writes it to {@code run/surface-dump.txt}
	 * (full ~3800 lines won't fit in chat). Each opcode line shows IP,
	 * mnemonic, immediates, and a brief decode (e.g. biome set contents
	 * for OP_BIOME, channel name for OP_NOISE_THRESH, then/else targets
	 * for OP_IF_ELSE).
	 *
	 * <p>Companion to /ferrite surface trace-next: trace identifies
	 * which IP range the eval skipped; dump reveals what's at those IPs
	 * so we can map "ip 58-429" to the rule structure that should fire
	 * but didn't.
	 */
	private static int surfaceDump(com.mojang.brigadier.context.CommandContext<CommandSourceStack> ctx) {
		SurfaceRuleAccess.Result extracted = SurfaceRuleAccess.extract(ctx.getSource().getLevel());
		if (!extracted.ok()) {
			sendFeedback(ctx, "[surface-dump] extract failed: " + extracted.error(), false);
			return 0;
		}
		CompiledRuleTree tree = SurfaceRuleCompiler.compile(extracted.surfaceRule());
		java.util.List<String> lines = SurfaceRuleDisassembler.disassemble(tree);
		java.nio.file.Path out = java.nio.file.Paths.get("surface-dump.txt").toAbsolutePath();
		try {
			java.nio.file.Files.write(out, lines, java.nio.charset.StandardCharsets.UTF_8);
		} catch (java.io.IOException e) {
			String msg = "[surface-dump] write failed: " + e.getMessage();
			sendFeedback(ctx, msg, false);
			ExampleMod.LOGGER.warn(msg);
			return 0;
		}
		String msg = String.format(
			"[surface-dump] wrote %d lines (%d bytecode) to %s",
			lines.size(), tree.bytecode().length, out);
		sendFeedback(ctx, msg, false);
		ExampleMod.LOGGER.info(msg);
		return Command.SINGLE_SUCCESS;
	}

	private static int surfaceDumpBiomes(com.mojang.brigadier.context.CommandContext<CommandSourceStack> ctx) {
		SurfaceRuleAccess.Result extracted = SurfaceRuleAccess.extract(ctx.getSource().getLevel());
		if (!extracted.ok()) {
			sendFeedback(ctx, "[surface-dump] extract failed: " + extracted.error(), false);
			return 0;
		}
		CompiledRuleTree tree = SurfaceRuleCompiler.compile(extracted.surfaceRule());
		java.util.List<String>[] table = tree.biomeSetTable();
		String summary = String.format("[surface-dump] biome set pool: %d entries", table.length);
		sendFeedback(ctx, summary, false);
		ExampleMod.LOGGER.info(summary);
		for (int i = 0; i < table.length; i++) {
			java.util.List<String> entry = table[i];
			ExampleMod.LOGGER.info("[surface-dump]   set #{} ({} biomes): {}", i, entry.size(), entry);
		}
		sendFeedback(ctx, "[surface-dump] full breakdown in latest.log under [surface-dump]", false);
		return Command.SINGLE_SUCCESS;
	}

	private static int surfaceTraceNext(com.mojang.brigadier.context.CommandContext<CommandSourceStack> ctx) {
		if (!SurfaceValidator.isEnabled()) {
			sendFeedback(ctx, "[surface-validate] not enabled — run /ferrite surface validate first", false);
			return 0;
		}
		SurfaceValidator.traceNextMismatch = true;
		String msg = "[surface-validate] armed: next mismatch will dump full opcode trace to latest.log";
		sendFeedback(ctx, msg, false);
		ExampleMod.LOGGER.info(msg);
		return Command.SINGLE_SUCCESS;
	}

	private static int surfaceValidateStats(com.mojang.brigadier.context.CommandContext<CommandSourceStack> ctx) {
		String line = SurfaceValidator.statsLine();
		sendFeedback(ctx, line, false);
		ExampleMod.LOGGER.info(line);
		return Command.SINGLE_SUCCESS;
	}

	private static int worldgenStatus(CommandContext<CommandSourceStack> ctx) {
		if (!RustBridge.NATIVE_AVAILABLE) {
			sendFeedback(ctx, "[worldgen] native unavailable — Rust state will never finalize", false);
			return Command.SINGLE_SUCCESS;
		}
		int count = RustBridge.worldgenNoiseCount();
		String line = count < 0
				? "[worldgen] Rust state NOT finalized (bootstrap didn't run, or failed)"
				: "[worldgen] Rust state finalized — " + count + " noises registered";
		sendFeedback(ctx, line, false);
		ExampleMod.LOGGER.info(line);
		return Command.SINGLE_SUCCESS;
	}

	private static int worldgenSample(CommandContext<CommandSourceStack> ctx) {
		if (!RustBridge.NATIVE_AVAILABLE) {
			sendFeedback(ctx, "[worldgen] native unavailable", false);
			return Command.SINGLE_SUCCESS;
		}
		String rawName = StringArgumentType.getString(ctx, "name").trim();
		// Be forgiving about the namespace: if the user typed `temperature`
		// (no colon), treat it as `minecraft:temperature`. Rust hashes the
		// full `Identifier.toString()` form, so the colon must be present.
		String name = rawName.contains(":") ? rawName : "minecraft:" + rawName;
		var source = ctx.getSource();
		var pos = source.getPosition();
		// Use INTEGER block coords (matching how DensityFunction.Noise
		// samples). Helps catch off-by-fraction noise mismatches.
		double x = (int) pos.x();
		double y = (int) pos.y;
		double z = (int) pos.z();

		byte[] nameBytes = name.getBytes(StandardCharsets.UTF_8);
		ByteBuffer nameBuf = ByteBuffer.allocateDirect(nameBytes.length).order(ByteOrder.nativeOrder());
		nameBuf.put(nameBytes);
		nameBuf.flip();

		double value = RustBridge.sampleWorldgenNoise(nameBuf, nameBytes.length, x, y, z);
		String line;
		if (Double.isNaN(value)) {
			line = String.format("[worldgen] sample(%s, %.2f, %.2f, %.2f) = NaN (not finalized or name unknown)",
					name, x, y, z);
		} else {
			line = String.format("[worldgen] sample(%s, %.2f, %.2f, %.2f) = %.6f", name, x, y, z, value);
		}
		sendFeedback(ctx, line, false);
		ExampleMod.LOGGER.info(line);
		return Command.SINGLE_SUCCESS;
	}

	private static int worldgenValidate(CommandContext<CommandSourceStack> ctx, int samples) {
		String result = WorldgenParity.runParityCheck(samples, 10000);
		sendFeedback(ctx, result, false);
		return Command.SINGLE_SUCCESS;
	}

	private static int biomeValidate(CommandContext<CommandSourceStack> ctx, int samples) {
		String result = BiomeParity.runParityCheck(samples);
		sendFeedback(ctx, result, false);
		return Command.SINGLE_SUCCESS;
	}

	private static int densityValidate(CommandContext<CommandSourceStack> ctx, int samples) {
		String result = DensityParity.runAll(ctx.getSource().getServer(), samples);
		sendFeedback(ctx, result, false);
		return Command.SINGLE_SUCCESS;
	}

	/** Microbench Rust bulk-region sampling vs Java per-cell vanilla
	 *  compute, both over a chunk-sized region of ferrite:terrain/
	 *  final_density. Reports per-cell µs and total ms each side.
	 *  Sanity-checks Rust output against Java at a few sample cells. */
	/** Phase 2 bench: time the bulk per-chunk density buffer JNI. The
	 *  output should match what vanilla's per-block compute would
	 *  produce; we sample-check 16 random positions for parity. */
	private static int densityBenchBuffer(CommandContext<CommandSourceStack> ctx) {
		final String name = "ferrite:terrain/final_density";
		final int chunkMinX = 0;
		final int chunkMinZ = 0;
		final int chunkSize = 16;
		final int height = 384;
		final int total = chunkSize * height * chunkSize;

		byte[] nameBytes = name.getBytes(java.nio.charset.StandardCharsets.UTF_8);
		java.nio.ByteBuffer nameBuf = java.nio.ByteBuffer.allocateDirect(nameBytes.length)
				.order(java.nio.ByteOrder.nativeOrder());
		nameBuf.put(nameBytes); nameBuf.flip();
		java.nio.ByteBuffer outBuf = java.nio.ByteBuffer.allocateDirect(total * 8)
				.order(java.nio.ByteOrder.nativeOrder());

		// Warmup
		int written = RustBridge.populateNoiseBufferRust(
				nameBuf, nameBytes.length, chunkMinX, chunkMinZ, outBuf);
		if (written != total) {
			sendFeedback(ctx, "[noise-buffer] Rust returned " + written + ", expected " + total
					+ " (state finalized? name registered?)", false);
			return Command.SINGLE_SUCCESS;
		}

		int iters = 5;
		long rustNs = 0;
		for (int i = 0; i < iters; i++) {
			outBuf.position(0);
			long t0 = System.nanoTime();
			RustBridge.populateNoiseBufferRust(
					nameBuf, nameBytes.length, chunkMinX, chunkMinZ, outBuf);
			rustNs += System.nanoTime() - t0;
		}

		// Sample-check 16 random block positions for parity with vanilla
		// computeVanilla(finalDensity). At cell-corner-aligned positions
		// the lerp degenerates; at sub-cell positions Rust does the lerp
		// matching vanilla's NoiseInterpolator pattern.
		outBuf.position(0);
		java.nio.DoubleBuffer rustDoubles = outBuf.asDoubleBuffer();
		java.util.Random rng = new java.util.Random(0xCAFEBABEL);
		int mismatch = 0;
		double maxDiff = 0.0;
		StringBuilder worst = new StringBuilder();
		for (int s = 0; s < 16; s++) {
			int bx = rng.nextInt(16);
			int by = rng.nextInt(384);
			int bz = rng.nextInt(16);
			int idx = (by * 16 + bz) * 16 + bx;
			double rust = rustDoubles.get(idx);
			Double yarn = DensityParity.sampleVanilla(
					ctx.getSource().getServer(), name,
					chunkMinX + bx, -64 + by, chunkMinZ + bz);
			if (yarn == null || Double.isNaN(yarn)) continue;
			double d = Math.abs(rust - yarn);
			if (d > maxDiff) {
				maxDiff = d;
				worst.setLength(0);
				worst.append("(").append(bx).append(",").append(by - 64).append(",").append(bz)
						.append(") rust=").append(String.format("%.6f", rust))
						.append(" yarn=").append(String.format("%.6f", yarn));
			}
			if (d > 1.0e-9) mismatch++;
		}

		double rustMs = rustNs / iters / 1_000_000.0;
		double rustUsCell = rustNs / (double) iters / total / 1_000.0;
		String line = String.format(
				"[noise-buffer] %s, %d cells: rust=%.2fms (%.2fµs/cell, avg of %d) "
						+ "mismatch=%d/16 maxDiff=%.3e %s",
				name, total, rustMs, rustUsCell, iters, mismatch, maxDiff,
				maxDiff > 1.0e-9 ? "worst=" + worst : "");
		ExampleMod.LOGGER.info(line);
		sendFeedback(ctx, line, false);
		return Command.SINGLE_SUCCESS;
	}

	private static int densityBenchRegion(CommandContext<CommandSourceStack> ctx) {
		final String name = "ferrite:terrain/final_density";
		// Cell-corner grid: 4 × 97 × 4. Y range -64..320 at step 4 = 97
		// inclusive cell-Y rows. Same shape NoiseChunk would corner-sample.
		final int sideX = 4, sideY = 97, sideZ = 4, step = 4;
		final int total = sideX * sideY * sideZ;
		final int originX = 0, originY = -64, originZ = 0;

		byte[] nameBytes = name.getBytes(java.nio.charset.StandardCharsets.UTF_8);
		java.nio.ByteBuffer nameBuf = java.nio.ByteBuffer.allocateDirect(nameBytes.length)
				.order(java.nio.ByteOrder.nativeOrder());
		nameBuf.put(nameBytes);
		nameBuf.flip();
		java.nio.ByteBuffer outBuf = java.nio.ByteBuffer.allocateDirect(total * 8)
				.order(java.nio.ByteOrder.nativeOrder());

		// Warm-up: one untimed call so JIT + Rust threadpool spin up.
		int written = RustBridge.sampleDensityRegion3DRust(
				nameBuf, nameBytes.length,
				originX, originY, originZ, sideX, sideY, sideZ, step, outBuf);
		if (written != total) {
			sendFeedback(ctx, "[density-bench] Rust returned " + written + ", expected " + total
					+ " (state finalized? name registered?)", false);
			return Command.SINGLE_SUCCESS;
		}
		int iters = 5;
		long rustNs = 0;
		for (int i = 0; i < iters; i++) {
			outBuf.position(0);
			long t0 = System.nanoTime();
			RustBridge.sampleDensityRegion3DRust(
					nameBuf, nameBytes.length,
					originX, originY, originZ, sideX, sideY, sideZ, step, outBuf);
			rustNs += System.nanoTime() - t0;
		}

		// Java side: vanilla per-cell compute via DensityParity.sampleVanilla
		// at every (originX + ix*step, originY + iy*step, originZ + iz*step).
		// One iter — Java is much slower; 5 iters would extend the bench.
		long javaNs;
		double[] javaVals = new double[total];
		{
			long t0 = System.nanoTime();
			for (int iy = 0; iy < sideY; iy++) {
				int by = originY + iy * step;
				for (int iz = 0; iz < sideZ; iz++) {
					int bz = originZ + iz * step;
					for (int ix = 0; ix < sideX; ix++) {
						int bx = originX + ix * step;
						Double v = DensityParity.sampleVanilla(
								ctx.getSource().getServer(), name, bx, by, bz);
						javaVals[(iy * sideZ + iz) * sideX + ix] =
								(v == null) ? Double.NaN : v;
					}
				}
			}
			javaNs = System.nanoTime() - t0;
		}

		// Sanity check 8 sample positions for parity.
		outBuf.position(0);
		java.nio.DoubleBuffer rustDoubles = outBuf.asDoubleBuffer();
		int mismatch = 0;
		double maxDiff = 0.0;
		for (int k = 0; k < total; k++) {
			double r = rustDoubles.get(k);
			double j = javaVals[k];
			if (Double.isNaN(r) || Double.isNaN(j)) continue;
			double d = Math.abs(r - j);
			if (d > maxDiff) maxDiff = d;
			if (d > 1.0e-9) mismatch++;
		}

		double rustMs = rustNs / iters / 1_000_000.0;
		double javaMs = javaNs / 1_000_000.0;
		double rustUsCell = rustNs / (double) iters / total / 1_000.0;
		double javaUsCell = javaNs / (double) total / 1_000.0;

		String line = String.format(
				"[density-bench] %s, %d cells: rust=%.2fms (%.2fµs/cell, avg of %d) java=%.2fms (%.2fµs/cell) speedup=%.1fx mismatch=%d/%d maxDiff=%.3e",
				name, total, rustMs, rustUsCell, iters, javaMs, javaUsCell,
				javaMs / Math.max(rustMs, 0.001), mismatch, total, maxDiff);
		ExampleMod.LOGGER.info(line);
		sendFeedback(ctx, line, false);
		return Command.SINGLE_SUCCESS;
	}

	private static int densityDump(CommandContext<CommandSourceStack> ctx) {
		String name = StringArgumentType.getString(ctx, "name").trim();
		if (!name.contains(":")) name = "minecraft:" + name;
		byte[] nameBytes = name.getBytes(java.nio.charset.StandardCharsets.UTF_8);
		java.nio.ByteBuffer nameBuf = java.nio.ByteBuffer.allocateDirect(nameBytes.length)
				.order(java.nio.ByteOrder.nativeOrder());
		nameBuf.put(nameBytes);
		nameBuf.flip();
		String dump = RustBridge.dumpDensityFunction(nameBuf, nameBytes.length);
		if (dump == null) {
			sendFeedback(ctx, "[density] " + name + " — not registered", false);
		} else {
			ExampleMod.LOGGER.info("[density-dump] {}\n{}", name, dump);
			sendFeedback(ctx, "[density] dumped " + name + " to log (" + dump.length() + " chars)", false);
		}
		return Command.SINGLE_SUCCESS;
	}

	private static int densitySample(CommandContext<CommandSourceStack> ctx) {
		String name = StringArgumentType.getString(ctx, "name").trim();
		if (!name.contains(":")) name = "minecraft:" + name;
		var pos = ctx.getSource().getPosition();
		int x = (int) pos.x();
		int y = (int) pos.y;
		int z = (int) pos.z();
		byte[] nameBytes = name.getBytes(java.nio.charset.StandardCharsets.UTF_8);
		java.nio.ByteBuffer nameBuf = java.nio.ByteBuffer.allocateDirect(nameBytes.length)
				.order(java.nio.ByteOrder.nativeOrder());
		nameBuf.put(nameBytes);
		nameBuf.flip();
		double rust = RustBridge.sampleDensityFunction(nameBuf, nameBytes.length, x, y, z);
		// Side-by-side: also call vanilla's DF at the same coord.
		Double yarn = DensityParity.sampleVanilla(ctx.getSource().getServer(), name, x, y, z);
		String line;
		if (Double.isNaN(rust)) {
			line = String.format("[density] %s @(%d,%d,%d) rust=NaN yarn=%s", name, x, y, z,
					yarn == null ? "?" : String.format("%.6f", yarn));
		} else if (yarn == null) {
			line = String.format("[density] %s @(%d,%d,%d) rust=%.6f yarn=?", name, x, y, z, rust);
		} else {
			double diff = Math.abs(rust - yarn);
			String status = diff < 1e-9 ? "MATCH" : String.format("DIFF=%.6e", diff);
			line = String.format("[density] %s @(%d,%d,%d) rust=%.6f yarn=%.6f [%s]",
					name, x, y, z, rust, yarn, status);
		}
		sendFeedback(ctx, line, false);
		return Command.SINGLE_SUCCESS;
	}

	private static int biomeAtPlayer(CommandContext<CommandSourceStack> ctx) {
		var pos = ctx.getSource().getPosition();
		return reportBiomeAt(ctx, (int) pos.x(), (int) pos.y, (int) pos.z());
	}

	private static int biomeAtCoords(CommandContext<CommandSourceStack> ctx) {
		int x = IntegerArgumentType.getInteger(ctx, "x");
		int y = IntegerArgumentType.getInteger(ctx, "y");
		int z = IntegerArgumentType.getInteger(ctx, "z");
		return reportBiomeAt(ctx, x, y, z);
	}

	private static int biomeAtChunk(CommandContext<CommandSourceStack> ctx) {
		int cx = IntegerArgumentType.getInteger(ctx, "cx");
		int cz = IntegerArgumentType.getInteger(ctx, "cz");
		// Center of chunk; y=64 = a stable mid-range height. The biome
		// sampler uses quart coords so y matters less than (x, z) for most
		// surface biomes — y=64 gives a representative answer.
		return reportBiomeAt(ctx, (cx << 4) + 8, 64, (cz << 4) + 8);
	}

	private static int reportBiomeAt(CommandContext<CommandSourceStack> ctx, int x, int y, int z) {
		String biome = BiomeParity.lookupBiomeAt(x, y, z);
		String line;
		if (biome == null) {
			line = String.format("[biome] (%d,%d,%d) — unavailable (sampler not captured or Rust state not finalized)", x, y, z);
		} else {
			line = String.format("[biome] (%d,%d,%d) → %s", x, y, z, biome);
		}
		sendFeedback(ctx, line, false);
		return Command.SINGLE_SUCCESS;
	}

	private static int biomeActual(CommandContext<CommandSourceStack> ctx) {
		int x = IntegerArgumentType.getInteger(ctx, "x");
		int y = IntegerArgumentType.getInteger(ctx, "y");
		int z = IntegerArgumentType.getInteger(ctx, "z");
		String biome = BiomeParity.lookupActualBiomeAt(ctx.getSource().getLevel(), x, y, z);
		String line = String.format("[biome-actual] (%d,%d,%d) → %s", x, y, z, biome);
		sendFeedback(ctx, line, false);
		return Command.SINGLE_SUCCESS;
	}

	private static int biomeRustAtPlayer(CommandContext<CommandSourceStack> ctx) {
		var pos = ctx.getSource().getPosition();
		return reportBiomeRust(ctx, (int) pos.x(), (int) pos.y, (int) pos.z());
	}

	private static int biomeRustAtCoords(CommandContext<CommandSourceStack> ctx) {
		int x = IntegerArgumentType.getInteger(ctx, "x");
		int y = IntegerArgumentType.getInteger(ctx, "y");
		int z = IntegerArgumentType.getInteger(ctx, "z");
		return reportBiomeRust(ctx, x, y, z);
	}

	private static int reportBiomeRust(CommandContext<CommandSourceStack> ctx, int x, int y, int z) {
		int rustId = RustBridge.findBiomeAtBlockRust(x, y, z);
		java.util.List<String> names = WorldgenStateBootstrap.registeredBiomeNames();
		String rustName = (rustId < 0 || rustId >= names.size())
				? "<rust:invalid id=" + rustId + ">"
				: names.get(rustId);
		// Side-by-side with vanilla path (lookupBiomeAt uses vanilla's
		// Climate.Sampler then Rust's R-tree).
		String vanillaPath = BiomeParity.lookupBiomeAt(x, y, z);
		String status = (vanillaPath != null && vanillaPath.equals(rustName)) ? "MATCH" : "DIFF";
		String line = String.format("[biome-rust] (%d,%d,%d) rust=%s vanilla-climate=%s [%s]",
				x, y, z, rustName, vanillaPath == null ? "?" : vanillaPath, status);
		sendFeedback(ctx, line, false);
		// On DIFF, dump per-axis climate from both sides so we can see
		// which axis diverged. Vanilla's TargetPoint quantizes after
		// `(float)compute(...)`, so we un-quantize the vanilla side back
		// to ~float for direct comparison with rust.
		if (!"MATCH".equals(status)) {
			String[] axes = {"temperature", "vegetation", "continents", "erosion", "depth", "ridges"};
			int qx = (x >> 2) << 2, qy = (y >> 2) << 2, qz = (z >> 2) << 2;
			double[] vanillaClimate = BiomeParity.sampleVanillaClimate(x, y, z);
			for (int i = 0; i < axes.length; i++) {
				String axis = axes[i];
				String name = "ferrite:climate/" + axis;
				byte[] nb = name.getBytes(java.nio.charset.StandardCharsets.UTF_8);
				java.nio.ByteBuffer buf = java.nio.ByteBuffer.allocateDirect(nb.length)
						.order(java.nio.ByteOrder.nativeOrder());
				buf.put(nb); buf.flip();
				double rust = RustBridge.sampleDensityFunction(buf, nb.length, qx, qy, qz);
				double vanilla = (vanillaClimate == null) ? Double.NaN : vanillaClimate[i];
				double diff = Math.abs(rust - vanilla);
				ExampleMod.LOGGER.info("[biome-rust-axis] {} rust={} vanilla={} diff={}",
						axis,
						String.format("%.6f", rust),
						String.format("%.6f", vanilla),
						String.format("%.6e", diff));
			}
		}
		return Command.SINGLE_SUCCESS;
	}

	/**
	 * Bulk biome prediction over an NxN region centered on the player.
	 * Samples on the 4-block quart grid (matching vanilla's biome cell
	 * size) at player Y. Outputs total cells, unique biomes, and the top
	 * 5 by count plus elapsed time. Works at unloaded coords because
	 * findBiomeAtBlockRust depends only on seed-derived climate noises,
	 * not chunk data.
	 */
	private static int biomePredict(CommandContext<CommandSourceStack> ctx, int radiusBlocks) {
		var pos = ctx.getSource().getPosition();
		int cx = (int) pos.x();
		int cy = (int) pos.y;
		int cz = (int) pos.z();
		int step = 4; // quart-pos cells
		int side = (radiusBlocks * 2) / step;
		java.util.List<String> names = WorldgenStateBootstrap.registeredBiomeNames();
		int[] counts = new int[names.size()];
		int unknown = 0;
		int total = side * side;
		// Batch path: one JNI call fills an int[] of biome IDs, replacing
		// `total` per-cell calls. Per-cell work in Rust is identical;
		// what we save is the JNI boundary cost per cell.
		java.nio.ByteBuffer out = java.nio.ByteBuffer.allocateDirect(total * 4)
				.order(java.nio.ByteOrder.nativeOrder());
		long t0 = System.nanoTime();
		int written = RustBridge.findBiomeRegionRust(
				cx - radiusBlocks, cy, cz - radiusBlocks,
				side, side, step, out);
		long elapsedNs = System.nanoTime() - t0;
		if (written != total) {
			String err = String.format(
					"[biome-predict] batch returned %d, expected %d — falling back",
					written, total);
			sendFeedback(ctx, err, false);
			return Command.SINGLE_SUCCESS;
		}
		java.nio.IntBuffer ids = out.asIntBuffer();
		for (int i = 0; i < total; i++) {
			int id = ids.get(i);
			if (id < 0 || id >= counts.length) unknown++;
			else counts[id]++;
		}

		// Top 5 by count.
		Integer[] order = new Integer[counts.length];
		for (int i = 0; i < order.length; i++) order[i] = i;
		java.util.Arrays.sort(order, (a, b) -> Integer.compare(counts[b], counts[a]));
		int distinct = 0;
		for (int c : counts) if (c > 0) distinct++;

		String header = String.format(
				"[biome-predict] center=(%d,%d) radius=%d cells=%d distinct=%d unknown=%d elapsed=%.2fms (%.0fns/cell)",
				cx, cz, radiusBlocks, total, distinct, unknown,
				elapsedNs / 1_000_000.0,
				(double) elapsedNs / Math.max(1, total));
		sendFeedback(ctx, header, false);
		ExampleMod.LOGGER.info(header);
		int top = Math.min(5, distinct);
		for (int i = 0; i < top; i++) {
			int id = order[i];
			if (counts[id] == 0) break;
			double pct = 100.0 * counts[id] / total;
			String line = String.format("[biome-predict]   %s: %d (%.1f%%)",
					names.get(id), counts[id], pct);
			sendFeedback(ctx, line, false);
			ExampleMod.LOGGER.info(line);
		}
		return Command.SINGLE_SUCCESS;
	}

	private static int biomeRouteOn(CommandContext<CommandSourceStack> ctx) {
		RustBiomeRouter.ENABLED = true;
		String line = String.format("[biome-route] ENABLED (router has %d holders)",
				RustBiomeRouter.size());
		sendFeedback(ctx, line, false);
		ExampleMod.LOGGER.info(line);
		return Command.SINGLE_SUCCESS;
	}

	private static int biomeRouteOff(CommandContext<CommandSourceStack> ctx) {
		RustBiomeRouter.ENABLED = false;
		sendFeedback(ctx, "[biome-route] disabled", false);
		return Command.SINGLE_SUCCESS;
	}

	private static int biomeRouteStatus(CommandContext<CommandSourceStack> ctx) {
		String line = String.format("[biome-route] enabled=%s holders=%d",
				RustBiomeRouter.ENABLED, RustBiomeRouter.size());
		sendFeedback(ctx, line, false);
		return Command.SINGLE_SUCCESS;
	}

	private static int prewarmOn(CommandContext<CommandSourceStack> ctx) {
		ChunkPrewarmer.ENABLED = true;
		ChunkPrewarmer.start();
		// Auto-enable the router so cache hits actually short-circuit vanilla.
		RustBiomeRouter.ENABLED = true;
		String line = "[prewarm] ENABLED (router auto-enabled). "
				+ "Worker pool started; trigger fires per server tick.";
		sendFeedback(ctx, line, false);
		ExampleMod.LOGGER.info(line);
		return Command.SINGLE_SUCCESS;
	}

	private static int prewarmOff(CommandContext<CommandSourceStack> ctx) {
		ChunkPrewarmer.ENABLED = false;
		sendFeedback(ctx, "[prewarm] disabled (router untouched)", false);
		return Command.SINGLE_SUCCESS;
	}

	private static int prewarmStatus(CommandContext<CommandSourceStack> ctx) {
		String line = String.format(
				"[prewarm] enabled=%s cached=%d inflight=%d warmed=%d hits=%d misses=%d avgWarm=%dus",
				ChunkPrewarmer.ENABLED, ChunkPrewarmer.cachedChunks(),
				ChunkPrewarmer.inflightCount(), ChunkPrewarmer.warmed(),
				ChunkPrewarmer.hits(), ChunkPrewarmer.misses(),
				ChunkPrewarmer.warmAvgUs());
		sendFeedback(ctx, line, false);
		return Command.SINGLE_SUCCESS;
	}

	private static int prewarmClear(CommandContext<CommandSourceStack> ctx) {
		ChunkPrewarmer.clear();
		sendFeedback(ctx, "[prewarm] cache cleared, stats reset", false);
		return Command.SINGLE_SUCCESS;
	}

	private static int chunkForceOn(CommandContext<CommandSourceStack> ctx) {
		ChunkForcer.ENABLED = true;
		sendFeedback(ctx, "[chunkforce] ENABLED — vanilla chunkgen forced ahead in rings (viewDist+16)", false);
		return Command.SINGLE_SUCCESS;
	}

	private static int chunkForceOff(CommandContext<CommandSourceStack> ctx) {
		ChunkForcer.ENABLED = false;
		sendFeedback(ctx, "[chunkforce] disabled (in-flight tickets allowed to complete naturally)", false);
		return Command.SINGLE_SUCCESS;
	}

	private static int chunkForceStatus(CommandContext<CommandSourceStack> ctx) {
		String line = String.format(
				"[chunkforce] enabled=%s inflight=%d scheduled=%d completed=%d errored=%d",
				ChunkForcer.ENABLED, ChunkForcer.inflightCount(),
				ChunkForcer.scheduledCount(), ChunkForcer.completedCount(),
				ChunkForcer.erroredCount());
		sendFeedback(ctx, line, false);
		return Command.SINGLE_SUCCESS;
	}

	private static int noiseRustOn(CommandContext<CommandSourceStack> ctx) {
		RustFinalDensityBufferWrapper.ENABLED = true;
		sendFeedback(ctx, "[noise-rust] ENABLED — newly-generated chunks bulk-prefill density via Rust (Phase 2). Existing chunks unaffected. Math may drift ~0.02 at sub-cell positions; toggle off if visual artifacts appear.", false);
		return Command.SINGLE_SUCCESS;
	}

	private static int noiseRustOff(CommandContext<CommandSourceStack> ctx) {
		RustFinalDensityBufferWrapper.ENABLED = false;
		sendFeedback(ctx, "[noise-rust] disabled — newly-generated chunks use vanilla finalDensity.", false);
		return Command.SINGLE_SUCCESS;
	}

	private static int noiseRustStatus(CommandContext<CommandSourceStack> ctx) {
		sendFeedback(ctx, "[noise-rust] enabled=" + RustFinalDensityBufferWrapper.ENABLED, false);
		return Command.SINGLE_SUCCESS;
	}

	private static int noiseRustDiag(CommandContext<CommandSourceStack> ctx) {
		String bufferLine = RustFinalDensityBufferWrapper.diagSummary();
		String flatLine = RustFlatCache.diagSummary();
		ExampleMod.LOGGER.info(bufferLine);
		ExampleMod.LOGGER.info(flatLine);
		sendFeedback(ctx, bufferLine, false);
		sendFeedback(ctx, flatLine, false);
		return Command.SINGLE_SUCCESS;
	}

	private static int noiseRustReset(CommandContext<CommandSourceStack> ctx) {
		RustFinalDensityBufferWrapper.resetDiag();
		RustFlatCache.resetDiag();
		sendFeedback(ctx, "[noise-rust] diagnostic counters reset", false);
		return Command.SINGLE_SUCCESS;
	}

	private static int biomeCompare(CommandContext<CommandSourceStack> ctx) {
		int x = IntegerArgumentType.getInteger(ctx, "x");
		int y = IntegerArgumentType.getInteger(ctx, "y");
		int z = IntegerArgumentType.getInteger(ctx, "z");
		String predicted = BiomeParity.lookupBiomeAt(x, y, z);
		String actual = BiomeParity.lookupActualBiomeAt(ctx.getSource().getLevel(), x, y, z);
		String status;
		if (predicted == null) {
			status = "rust=<unavailable>";
		} else if (predicted.equals(actual)) {
			status = "MATCH";
		} else {
			status = "MISMATCH";
		}
		String line = String.format("[biome-compare] (%d,%d,%d) rust=%s vanilla=%s [%s]",
				x, y, z, predicted, actual, status);
		sendFeedback(ctx, line, false);
		return Command.SINGLE_SUCCESS;
	}

	private static int aquiferRustOn(
			com.mojang.brigadier.context.CommandContext<CommandSourceStack> ctx) {
		RustAquiferDispatch.ENABLED = true;
		String msg = "[aquifer-rust] enabled — wrappers will be constructed for newly-loaded chunks (existing chunks unchanged)";
		sendFeedback(ctx, msg, true);
		ExampleMod.LOGGER.info(msg);
		return Command.SINGLE_SUCCESS;
	}

	private static int aquiferRustOff(
			com.mojang.brigadier.context.CommandContext<CommandSourceStack> ctx) {
		RustAquiferDispatch.ENABLED = false;
		String msg = "[aquifer-rust] disabled — vanilla Aquifer.Impl path active for newly-loaded chunks";
		sendFeedback(ctx, msg, true);
		ExampleMod.LOGGER.info(msg);
		return Command.SINGLE_SUCCESS;
	}

	private static int aquiferRustStatus(
			com.mojang.brigadier.context.CommandContext<CommandSourceStack> ctx) {
		String msg = RustAquiferDispatch.diagSummary();
		sendFeedback(ctx, msg, false);
		ExampleMod.LOGGER.info(msg);
		return Command.SINGLE_SUCCESS;
	}

	private static int aquiferParityOn(
			com.mojang.brigadier.context.CommandContext<CommandSourceStack> ctx) {
		RustAquiferDispatch.PARITY_MODE = true;
		String msg = "[aquifer-parity] enabled — every Rust apply will also call vanilla and compare (~2x cost). Mismatches log to [aquifer-parity] tag.";
		sendFeedback(ctx, msg, true);
		ExampleMod.LOGGER.info(msg);
		return Command.SINGLE_SUCCESS;
	}

	private static int aquiferParityOff(
			com.mojang.brigadier.context.CommandContext<CommandSourceStack> ctx) {
		RustAquiferDispatch.PARITY_MODE = false;
		sendFeedback(ctx, "[aquifer-parity] disabled", true);
		return Command.SINGLE_SUCCESS;
	}

	private static int aquiferParityReset(
			com.mojang.brigadier.context.CommandContext<CommandSourceStack> ctx) {
		RustAquiferDispatch.parityCompared.set(0);
		RustAquiferDispatch.parityBlockMismatch.set(0);
		RustAquiferDispatch.parityTickMismatch.set(0);
		sendFeedback(ctx, "[aquifer-parity] counters reset", true);
		return Command.SINGLE_SUCCESS;
	}

	private static int dispatcherProbeOn(
			com.mojang.brigadier.context.CommandContext<CommandSourceStack> ctx) {
		FerriteDispatcherProbe.ENABLED = true;
		FerriteDispatcherProbe.resetDiag();
		String msg = "[ferrite/dispatcher-probe] enabled (samples reset; status with /ferrite probe dispatcher status)";
		sendFeedback(ctx, msg, true);
		ExampleMod.LOGGER.info(msg);
		return Command.SINGLE_SUCCESS;
	}

	private static int dispatcherProbeOff(
			com.mojang.brigadier.context.CommandContext<CommandSourceStack> ctx) {
		FerriteDispatcherProbe.ENABLED = false;
		String msg = "[ferrite/dispatcher-probe] disabled";
		sendFeedback(ctx, msg, true);
		ExampleMod.LOGGER.info(msg);
		return Command.SINGLE_SUCCESS;
	}

	private static int dispatcherProbeStatus(
			com.mojang.brigadier.context.CommandContext<CommandSourceStack> ctx) {
		String msg = FerriteDispatcherProbe.diagSummary();
		sendFeedback(ctx, msg, false);
		ExampleMod.LOGGER.info(msg);
		return Command.SINGLE_SUCCESS;
	}

	private static int dispatcherProbeReset(
			com.mojang.brigadier.context.CommandContext<CommandSourceStack> ctx) {
		FerriteDispatcherProbe.resetDiag();
		String msg = "[ferrite/dispatcher-probe] reset";
		sendFeedback(ctx, msg, true);
		ExampleMod.LOGGER.info(msg);
		return Command.SINGLE_SUCCESS;
	}

	private static void sendFeedback(
			com.mojang.brigadier.context.CommandContext<CommandSourceStack> ctx,
			String message, boolean broadcast) {
		ctx.getSource().sendSuccess(() -> Component.literal(message), broadcast);
	}
}
