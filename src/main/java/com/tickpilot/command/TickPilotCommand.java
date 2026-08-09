package com.tickpilot.command;

import com.mojang.brigadier.CommandDispatcher;
import com.tickpilot.ServerStateHolder;
import com.tickpilot.TickPilot;
import com.tickpilot.TickPilotServerState;

import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;

import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;

/**
 * Owns the {@code /tickpilot} command tree (SPEC FR-12).
 *
 * <p>Only {@code status} exists in this phase, and it reports liveness rather than numbers —
 * nothing is measured yet. Subcommands are added by the phases that make them meaningful.
 */
public final class TickPilotCommand {
	private TickPilotCommand() {
	}

	/**
	 * Subscribes command registration. Called once from the mod entrypoint.
	 */
	public static void register() {
		CommandRegistrationCallback.EVENT.register(
				(dispatcher, registryAccess, environment) -> register(dispatcher));
	}

	private static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
		// SPEC FR-12 gives `status` permission level 0, i.e. available to everyone, so no
		// `requires` clause. Making the level configurable needs the config from FR-15.
		dispatcher.register(Commands.literal("tickpilot")
				.then(Commands.literal("status")
						.executes(context -> status(context.getSource()))));
	}

	private static int status(CommandSourceStack source) {
		// INV-9: a command must report a problem, never throw into the command dispatcher.
		try {
			TickPilotServerState state = ServerStateHolder.get(source.getServer());

			if (state == null || state.isDisabled()) {
				source.sendFailure(Component.translatable("command.tickpilot.status.unavailable"));
				return 0;
			}

			source.sendSuccess(() -> Component.translatable("command.tickpilot.status.no_metrics"), false);
			return 1;
		} catch (Throwable t) {
			TickPilot.LOGGER.error("/tickpilot status failed", t);
			source.sendFailure(Component.translatable("command.tickpilot.error"));
			return 0;
		}
	}
}
