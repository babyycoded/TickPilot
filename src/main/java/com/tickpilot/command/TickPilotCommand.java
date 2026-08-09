package com.tickpilot.command;

import java.util.Locale;
import java.util.concurrent.TimeUnit;

import com.mojang.brigadier.CommandDispatcher;
import com.tickpilot.ServerStateHolder;
import com.tickpilot.TickPilot;
import com.tickpilot.TickPilotServerState;
import com.tickpilot.budget.LoadLevel;
import com.tickpilot.budget.TickBudget;
import com.tickpilot.metrics.TickMetricsSnapshot;

import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;

import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;

/**
 * Owns the {@code /tickpilot} command tree (SPEC FR-12).
 *
 * <p>{@code status} reports the SPEC AC-1 metrics and the SPEC FR-5 load level. Subcommands are
 * added by the phases that make them meaningful.
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

			TickMetricsSnapshot metrics = state.snapshot(System.nanoTime());

			if (metrics.isEmpty()) {
				source.sendSuccess(() -> Component.translatable("command.tickpilot.status.no_metrics"), false);
				return 1;
			}

			TickBudget budget = state.budget();
			LoadLevel level = budget.level();

			source.sendSuccess(() -> Component.translatable("command.tickpilot.status.header",
					metrics.totalTicks(), formatDuration(metrics.uptimeNanos())), false);

			source.sendSuccess(() -> Component.translatable("command.tickpilot.status.tps",
					Component.literal(format(metrics.tps())).withStyle(tpsColour(metrics.tps()))), false);

			source.sendSuccess(() -> Component.translatable("command.tickpilot.status.mspt",
					format(metrics.lastMspt()), format(metrics.avgMspt5s()),
					format(metrics.avgMspt1m()), format(metrics.avgMspt5m())), false);

			// Two windows, deliberately. The short one says whether the server is struggling now;
			// the max line says what the worst moment was and when, so an outlier that is already
			// over is dated rather than left looking current (SPEC AC-1, AC-13).
			source.sendSuccess(() -> Component.translatable("command.tickpilot.status.percentiles",
					format(metrics.p95Mspt1m()), format(metrics.p99Mspt1m()),
					formatDuration(metrics.shortPercentileSpanNanos())), false);

			source.sendSuccess(() -> Component.translatable("command.tickpilot.status.max",
					format(metrics.maxMspt()), formatDuration(metrics.maxAgeNanos()),
					format(metrics.p95MsptHistory()), format(metrics.p99MsptHistory()),
					formatDuration(metrics.retainedSpanNanos())), false);

			source.sendSuccess(() -> Component.translatable("command.tickpilot.status.load",
					Component.translatable(level.translationKey()).withStyle(levelColour(level)),
					format(budget.targetMspt()), format(budget.highMspt()), format(budget.criticalMspt())), false);

			// SPEC AC-1b: say out loud when a low TPS is configured rather than caused by load.
			if (state.isTickRateFrozen()) {
				source.sendSuccess(() -> Component.translatable("command.tickpilot.status.frozen")
						.withStyle(ChatFormatting.YELLOW), false);
			} else if (state.tickRate() != 20.0f) {
				source.sendSuccess(() -> Component.translatable("command.tickpilot.status.tickrate",
						format(state.tickRate())).withStyle(ChatFormatting.YELLOW), false);
			}

			return 1;
		} catch (Throwable t) {
			TickPilot.LOGGER.error("/tickpilot status failed", t);
			source.sendFailure(Component.translatable("command.tickpilot.error"));
			return 0;
		}
	}

	/**
	 * Formats a metric for display. {@link Locale#ROOT} is deliberate: the number must not pick
	 * up the server's locale separator while the surrounding text comes from a translation file.
	 */
	private static String format(double value) {
		return String.format(Locale.ROOT, "%.2f", value);
	}

	/** Formats a duration as {@code 1h 12m 30s}, dropping leading units that are zero. */
	private static String formatDuration(long nanos) {
		long seconds = TimeUnit.NANOSECONDS.toSeconds(Math.max(0L, nanos));
		long hours = seconds / 3600L;
		long minutes = seconds % 3600L / 60L;
		long secs = seconds % 60L;

		if (hours > 0L) {
			return String.format(Locale.ROOT, "%dh %02dm %02ds", hours, minutes, secs);
		}

		if (minutes > 0L) {
			return String.format(Locale.ROOT, "%dm %02ds", minutes, secs);
		}

		return secs + "s";
	}

	private static ChatFormatting tpsColour(double tps) {
		if (tps >= 19.5) {
			return ChatFormatting.GREEN;
		}

		return tps >= 15.0 ? ChatFormatting.YELLOW : ChatFormatting.RED;
	}

	private static ChatFormatting levelColour(LoadLevel level) {
		return switch (level) {
			case NORMAL -> ChatFormatting.GREEN;
			case ELEVATED -> ChatFormatting.YELLOW;
			case HIGH -> ChatFormatting.GOLD;
			case CRITICAL -> ChatFormatting.RED;
		};
	}
}
