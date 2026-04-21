package me.apika.apikaprobe;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.CommandDispatcher;

import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;

import me.apika.apikaprobe.redstone.FerriteWireConfig;
import me.apika.apikaprobe.surface.CompiledRuleTree;
import me.apika.apikaprobe.surface.SurfaceRuleAccess;
import me.apika.apikaprobe.surface.SurfaceRuleCompiler;

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
				.then(CommandManager.literal("redstone")
						.then(CommandManager.literal("rust")
								.then(CommandManager.literal("on").executes(FerriteCommand::enableRust))
								.then(CommandManager.literal("off").executes(FerriteCommand::disableRust))
								.then(CommandManager.literal("status").executes(FerriteCommand::statusRust)))
						.then(CommandManager.literal("ac")
								.then(CommandManager.literal("on").executes(FerriteCommand::enableAc))
								.then(CommandManager.literal("off").executes(FerriteCommand::disableAc))
								.then(CommandManager.literal("status").executes(FerriteCommand::statusAc))))
				.then(CommandManager.literal("surface")
						.then(CommandManager.literal("compile").executes(FerriteCommand::surfaceCompile))
						.then(CommandManager.literal("stats").executes(FerriteCommand::surfaceStats))));
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

	private static void sendFeedback(
			com.mojang.brigadier.context.CommandContext<ServerCommandSource> ctx,
			String message, boolean broadcast) {
		ctx.getSource().sendFeedback(() -> Text.literal(message), broadcast);
	}
}
