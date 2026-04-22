package me.apika.apikaprobe;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;

import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;

import me.apika.apikaprobe.redstone.FerriteWireConfig;
import me.apika.apikaprobe.redstone.RedstoneQueueBench;
import me.apika.apikaprobe.surface.CompiledRuleTree;
import me.apika.apikaprobe.surface.SurfaceRuleAccess;
import me.apika.apikaprobe.surface.SurfaceRuleCompiler;
import me.apika.apikaprobe.surface.SurfaceBatchHandoff;
import me.apika.apikaprobe.surface.SurfaceRuleDisassembler;
import me.apika.apikaprobe.surface.ColumnContext;
import me.apika.apikaprobe.surface.SurfaceRuleEvaluator;
import me.apika.apikaprobe.surface.SurfaceValidator;

import net.minecraft.server.command.CommandManager;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.text.Text;

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
 * DefaultRedstoneController's behavior underneath, not AC's.
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

	private static void registerRoot(CommandDispatcher<ServerCommandSource> dispatcher) {
		dispatcher.register(CommandManager.literal("ferrite")
				.requires(CommandManager.requirePermissionLevel(CommandManager.GAMEMASTERS_CHECK))
				.then(CommandManager.literal("cramming")
						.then(CommandManager.literal("on").executes(FerriteCommand::enableCramming))
						.then(CommandManager.literal("off").executes(FerriteCommand::disableCramming))
						.then(CommandManager.literal("status").executes(FerriteCommand::statusCramming)))
				.then(CommandManager.literal("redstone")
						.then(CommandManager.literal("rust")
								.then(CommandManager.literal("on").executes(FerriteCommand::enableRust))
								.then(CommandManager.literal("off").executes(FerriteCommand::disableRust))
								.then(CommandManager.literal("status").executes(FerriteCommand::statusRust)))
						.then(CommandManager.literal("ac")
								.then(CommandManager.literal("on").executes(FerriteCommand::enableAc))
								.then(CommandManager.literal("off").executes(FerriteCommand::disableAc))
								.then(CommandManager.literal("status").executes(FerriteCommand::statusAc)))
						.then(CommandManager.literal("bench").executes(FerriteCommand::redstoneBench))
						.then(CommandManager.literal("bfs")
								.then(CommandManager.literal("on").executes(FerriteCommand::enableBfs))
								.then(CommandManager.literal("off").executes(FerriteCommand::disableBfs))
								.then(CommandManager.literal("status").executes(FerriteCommand::statusBfs)))
						.then(CommandManager.literal("bfs-min")
								.then(CommandManager.argument("n", IntegerArgumentType.integer(1, 4096))
										.executes(FerriteCommand::setBfsMin))))
				.then(CommandManager.literal("surface")
						.then(CommandManager.literal("compile").executes(FerriteCommand::surfaceCompile))
						.then(CommandManager.literal("stats").executes(FerriteCommand::surfaceStats))
						.then(CommandManager.literal("validate").executes(FerriteCommand::surfaceValidate))
						.then(CommandManager.literal("validate-off").executes(FerriteCommand::surfaceValidateOff))
						.then(CommandManager.literal("validate-stats").executes(FerriteCommand::surfaceValidateStats))
						.then(CommandManager.literal("batch-test").executes(FerriteCommand::surfaceBatchTest))
						.then(CommandManager.literal("trace-next").executes(FerriteCommand::surfaceTraceNext))
						.then(CommandManager.literal("dump-biomes").executes(FerriteCommand::surfaceDumpBiomes))
						.then(CommandManager.literal("dump").executes(FerriteCommand::surfaceDump))));
	}

	/**
	 * Toggle Ferrite's batched mob-vs-mob cramming dispatcher. When OFF,
	 * the cancel mixin no-ops and vanilla LivingEntity.tickCramming runs
	 * unmodified — including vanilla cramming damage. Lets users A/B
	 * the perf claim ("stable TPS at 1000+ mobs") in their own world.
	 * Volatile, not persisted.
	 */
	private static int enableCramming(com.mojang.brigadier.context.CommandContext<ServerCommandSource> ctx) {
		CrammingDispatcher.ENABLED = true;
		String msg = "[cramming] Ferrite cramming enabled — batched Rust path active (vanilla cramming damage NOT applied)";
		sendFeedback(ctx, msg, true);
		ExampleMod.LOGGER.info(msg);
		return Command.SINGLE_SUCCESS;
	}

	private static int disableCramming(com.mojang.brigadier.context.CommandContext<ServerCommandSource> ctx) {
		CrammingDispatcher.ENABLED = false;
		String msg = "[cramming] Ferrite cramming disabled — vanilla path active (cramming damage will fire per maxEntityCramming gamerule)";
		sendFeedback(ctx, msg, true);
		ExampleMod.LOGGER.info(msg);
		return Command.SINGLE_SUCCESS;
	}

	private static int statusCramming(com.mojang.brigadier.context.CommandContext<ServerCommandSource> ctx) {
		String msg = String.format(
			"[cramming] ENABLED=%s native=%s  (watch [cramming-dispatch] in latest.log for batch counts)",
			CrammingDispatcher.ENABLED,
			RustBridge.NATIVE_AVAILABLE ? "available" : "MISSING");
		sendFeedback(ctx, msg, false);
		ExampleMod.LOGGER.info(msg);
		return Command.SINGLE_SUCCESS;
	}

	/**
	 * Enables the Rust BFS dispatcher for the current server session only.
	 * Setting is held in a static volatile field, NOT persisted — flips
	 * back to the default ({@code false}) on server restart.
	 */
	private static int enableRust(com.mojang.brigadier.context.CommandContext<ServerCommandSource> ctx) {
		if (!RustBridge.NATIVE_AVAILABLE) {
			sendFeedback(ctx, "Rust native unavailable — flag set but no route will take effect.", false);
		}
		RedstoneHandoff.USE_RUST = true;
		sendFeedback(ctx, "[redstone] Rust BFS enabled (this session only)", true);
		ExampleMod.LOGGER.info("[redstone] Rust BFS enabled (via /ferrite, this session only)");
		return Command.SINGLE_SUCCESS;
	}

	private static int disableRust(com.mojang.brigadier.context.CommandContext<ServerCommandSource> ctx) {
		RedstoneHandoff.USE_RUST = false;
		sendFeedback(ctx, "[redstone] Rust BFS disabled (vanilla path)", true);
		ExampleMod.LOGGER.info("[redstone] Rust BFS disabled (via /ferrite)");
		return Command.SINGLE_SUCCESS;
	}

	private static int statusRust(com.mojang.brigadier.context.CommandContext<ServerCommandSource> ctx) {
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
	private static int enableAc(com.mojang.brigadier.context.CommandContext<ServerCommandSource> ctx) {
		if (RedstoneHandoff.USE_RUST) {
			sendFeedback(ctx, "Warning: Rust BFS is also enabled. Running both paths at once is untested and not recommended.", false);
		}
		FerriteWireConfig.ENABLED = true;
		sendFeedback(ctx, "[redstone] Alternate-Current wire algorithm enabled (this session only)", true);
		ExampleMod.LOGGER.info("[redstone] AC wire algorithm enabled (via /ferrite, this session only)");
		return Command.SINGLE_SUCCESS;
	}

	private static int disableAc(com.mojang.brigadier.context.CommandContext<ServerCommandSource> ctx) {
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
	private static int enableBfs(com.mojang.brigadier.context.CommandContext<ServerCommandSource> ctx) {
		if (!RustBridge.NATIVE_AVAILABLE) {
			sendFeedback(ctx, "Rust native unavailable — flag set but no batch will run.", false);
		}
		FerriteWireConfig.RUST_BFS = true;
		String msg = "[redstone] Rust BFS Phase 2 enabled (this session only)";
		sendFeedback(ctx, msg, true);
		ExampleMod.LOGGER.info(msg);
		return Command.SINGLE_SUCCESS;
	}

	private static int disableBfs(com.mojang.brigadier.context.CommandContext<ServerCommandSource> ctx) {
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
	private static int setBfsMin(com.mojang.brigadier.context.CommandContext<ServerCommandSource> ctx) {
		int n = IntegerArgumentType.getInteger(ctx, "n");
		FerriteWireConfig.RUST_BFS_MIN_NODES = n;
		String msg = "[redstone] bfs-min set to " + n + " (production default is 1)";
		sendFeedback(ctx, msg, true);
		ExampleMod.LOGGER.info(msg);
		return Command.SINGLE_SUCCESS;
	}

	private static int statusBfs(com.mojang.brigadier.context.CommandContext<ServerCommandSource> ctx) {
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

	private static int redstoneBench(com.mojang.brigadier.context.CommandContext<ServerCommandSource> ctx) {
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

	private static int statusAc(com.mojang.brigadier.context.CommandContext<ServerCommandSource> ctx) {
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
	private static int surfaceCompile(com.mojang.brigadier.context.CommandContext<ServerCommandSource> ctx) {
		SurfaceRuleAccess.Result extracted = SurfaceRuleAccess.extract(ctx.getSource().getWorld());
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
	private static int surfaceStats(com.mojang.brigadier.context.CommandContext<ServerCommandSource> ctx) {
		SurfaceRuleAccess.Result extracted = SurfaceRuleAccess.extract(ctx.getSource().getWorld());
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
	private static int surfaceValidate(com.mojang.brigadier.context.CommandContext<ServerCommandSource> ctx) {
		SurfaceRuleAccess.Result extracted = SurfaceRuleAccess.extract(ctx.getSource().getWorld());
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

	private static int surfaceValidateOff(com.mojang.brigadier.context.CommandContext<ServerCommandSource> ctx) {
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
	private static int surfaceBatchTest(com.mojang.brigadier.context.CommandContext<ServerCommandSource> ctx) {
		SurfaceRuleAccess.Result extracted = SurfaceRuleAccess.extract(ctx.getSource().getWorld());
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
	private static int surfaceDump(com.mojang.brigadier.context.CommandContext<ServerCommandSource> ctx) {
		SurfaceRuleAccess.Result extracted = SurfaceRuleAccess.extract(ctx.getSource().getWorld());
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

	private static int surfaceDumpBiomes(com.mojang.brigadier.context.CommandContext<ServerCommandSource> ctx) {
		SurfaceRuleAccess.Result extracted = SurfaceRuleAccess.extract(ctx.getSource().getWorld());
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

	private static int surfaceTraceNext(com.mojang.brigadier.context.CommandContext<ServerCommandSource> ctx) {
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

	private static int surfaceValidateStats(com.mojang.brigadier.context.CommandContext<ServerCommandSource> ctx) {
		String line = SurfaceValidator.statsLine();
		sendFeedback(ctx, line, false);
		ExampleMod.LOGGER.info(line);
		return Command.SINGLE_SUCCESS;
	}

	private static void sendFeedback(
			com.mojang.brigadier.context.CommandContext<ServerCommandSource> ctx,
			String message, boolean broadcast) {
		ctx.getSource().sendFeedback(() -> Text.literal(message), broadcast);
	}
}
