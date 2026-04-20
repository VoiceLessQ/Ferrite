package me.apika.apikaprobe;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.CommandDispatcher;

import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;

import me.apika.apikaprobe.redstone.FerriteWireConfig;

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
								.then(CommandManager.literal("status").executes(FerriteCommand::statusAc)))));
	}

	private static int enableRust(com.mojang.brigadier.context.CommandContext<ServerCommandSource> ctx) {
		if (!RustBridge.NATIVE_AVAILABLE) {
			sendFeedback(ctx, "Rust native unavailable — flag set but no route will take effect.", false);
		}
		RedstoneHandoff.USE_RUST = true;
		sendFeedback(ctx, "[ferrite] Rust redstone BFS enabled", true);
		ExampleMod.LOGGER.info("[ferrite] Rust redstone BFS enabled (via /ferrite)");
		return Command.SINGLE_SUCCESS;
	}

	private static int disableRust(com.mojang.brigadier.context.CommandContext<ServerCommandSource> ctx) {
		RedstoneHandoff.USE_RUST = false;
		sendFeedback(ctx, "[ferrite] Rust redstone BFS disabled (vanilla path)", true);
		ExampleMod.LOGGER.info("[ferrite] Rust redstone BFS disabled (via /ferrite)");
		return Command.SINGLE_SUCCESS;
	}

	private static int statusRust(com.mojang.brigadier.context.CommandContext<ServerCommandSource> ctx) {
		String msg = String.format(
				"[ferrite] redstone rust USE_RUST=%s native=%s  (watch latest.log for [redstone] phase numbers every 5s)",
				RedstoneHandoff.USE_RUST,
				RustBridge.NATIVE_AVAILABLE ? "available" : "MISSING");
		sendFeedback(ctx, msg, false);
		ExampleMod.LOGGER.info(msg);
		return Command.SINGLE_SUCCESS;
	}

	private static int enableAc(com.mojang.brigadier.context.CommandContext<ServerCommandSource> ctx) {
		if (RedstoneHandoff.USE_RUST) {
			sendFeedback(ctx, "Warning: Rust BFS is also enabled. Running both paths at once is untested and not recommended.", false);
		}
		FerriteWireConfig.ENABLED = true;
		sendFeedback(ctx, "[ferrite] Alternate-Current wire algorithm enabled", true);
		ExampleMod.LOGGER.info("[ferrite] AC wire algorithm enabled (via /ferrite)");
		return Command.SINGLE_SUCCESS;
	}

	private static int disableAc(com.mojang.brigadier.context.CommandContext<ServerCommandSource> ctx) {
		FerriteWireConfig.ENABLED = false;
		sendFeedback(ctx, "[ferrite] Alternate-Current wire algorithm disabled (vanilla path)", true);
		ExampleMod.LOGGER.info("[ferrite] AC wire algorithm disabled (via /ferrite)");
		return Command.SINGLE_SUCCESS;
	}

	private static int statusAc(com.mojang.brigadier.context.CommandContext<ServerCommandSource> ctx) {
		String msg = String.format(
				"[ferrite] redstone ac ENABLED=%s update-order=%s  (watch latest.log for [redstone] phase numbers every 5s)",
				FerriteWireConfig.ENABLED,
				FerriteWireConfig.UPDATE_ORDER.id());
		sendFeedback(ctx, msg, false);
		ExampleMod.LOGGER.info(msg);
		return Command.SINGLE_SUCCESS;
	}

	private static void sendFeedback(
			com.mojang.brigadier.context.CommandContext<ServerCommandSource> ctx,
			String message, boolean broadcast) {
		ctx.getSource().sendFeedback(() -> Text.literal(message), broadcast);
	}
}
