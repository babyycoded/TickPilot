package com.tickpilot.command;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import com.mojang.brigadier.CommandDispatcher;
import com.tickpilot.ServerStateHolder;
import com.tickpilot.TickPilot;
import com.tickpilot.TickPilotServerState;
import com.tickpilot.budget.LoadLevel;
import com.tickpilot.budget.TickBudget;
import com.tickpilot.config.ConfigLoadResult;
import com.tickpilot.config.ConfigLoader;
import com.tickpilot.metrics.TickMetrics;
import com.tickpilot.metrics.TickMetricsSnapshot;
import com.tickpilot.profiler.CostTracker;
import com.tickpilot.profiler.TickCategory;
import com.tickpilot.profiler.TickProfiler;

import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;

import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.level.block.entity.BlockEntityType;

/**
 * Owns the {@code /tickpilot} command tree (SPEC FR-12).
 *
 * <p>{@code status} reports the SPEC AC-1 metrics and the SPEC FR-5 load level; {@code reload}
 * re-reads the config (SPEC AC-15). Subcommands are added by the phases that make them meaningful.
 */
public final class TickPilotCommand {
	/**
	 * How many rejected values {@code reload} prints into the chat before it stops and points at
	 * the log. A config with fifty typos would otherwise scroll the operator's chat away.
	 */
	private static final int MAX_PROBLEMS_SHOWN = 8;

	/** How many types /tickpilot top entities|blockentities lists (SPEC AC-3 top-N). */
	private static final int TOP_N = 10;

	private static final long NANOS_PER_MILLI = 1_000_000L;

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
		// `requires` clause. Making the level configurable needs a config key that FR-15 does not
		// define, so it stays at 0.
		dispatcher.register(Commands.literal("tickpilot")
				.then(Commands.literal("status")
						.executes(context -> status(context.getSource())))
				.then(Commands.literal("reload")
						.requires(source -> source.hasPermission(2))
						.executes(context -> reload(context.getSource())))
				.then(Commands.literal("top")
						.requires(source -> source.hasPermission(2))
						.executes(context -> top(context.getSource()))
						.then(Commands.literal("entities")
								.executes(context -> topTypes(context.getSource(), TickCategory.ENTITIES)))
						.then(Commands.literal("blockentities")
								.executes(context -> topTypes(context.getSource(),
										TickCategory.BLOCK_ENTITIES)))));
	}

	/**
	 * Prints the costliest types in a category, plus the costliest mod namespaces (SPEC FR-3,
	 * AC-3).
	 *
	 * <p>Registry lookups and string building happen here, on the command path, and never in the
	 * tick loop: {@code CostTracker} keeps the registry objects themselves as keys precisely so
	 * that turning them into names costs nothing per tick (SPEC INV-6).
	 */
	private static int topTypes(CommandSourceStack source, TickCategory category) {
		try {
			TickPilotServerState state = ServerStateHolder.get(source.getServer());

			if (state == null || state.isDisabled()) {
				source.sendFailure(Component.translatable("command.tickpilot.status.unavailable"));
				return 0;
			}

			TickProfiler profiler = state.profiler();

			if (profiler.sessionTicks() == 0L) {
				source.sendSuccess(() -> Component.translatable("command.tickpilot.top.no_session"), false);
				return 1;
			}

			List<CostTracker.TypeCost> rows = state.costs().top(category, TOP_N);

			if (rows.isEmpty()) {
				source.sendSuccess(() -> Component.translatable("command.tickpilot.top.types.empty",
						Component.translatable(category.translationKey())), false);
				return 1;
			}

			long ticks = profiler.sessionTicks();

			source.sendSuccess(() -> Component.translatable("command.tickpilot.top.types.header",
					Component.translatable(category.translationKey()), rows.size(),
					state.costs().trackedTypes(category), ticks), false);

			for (CostTracker.TypeCost row : rows) {
				ResourceLocation id = idOf(category, row.key());
				// Instances actually ticked per tick, which is what AC-3 means by "count": a type
				// with 400 entities loaded but 20 in range is costing you 20.
				double perTick = (double) row.invocations() / ticks;

				source.sendSuccess(() -> Component.translatable("command.tickpilot.top.types.row",
						id == null ? "unregistered" : id.toString(),
						format(toMsptPerTick(row.totalNanos(), ticks)),
						format(perTick),
						formatMicros(row.averageNanos())), false);
			}

			sendModBreakdown(source, category, rows, ticks);
			return 1;
		} catch (Throwable t) {
			TickPilot.LOGGER.error("/tickpilot top {} failed", category, t);
			source.sendFailure(Component.translatable("command.tickpilot.error"));
			return 0;
		}
	}

	/** Groups the rows by namespace, which is the mod ID breakdown FR-3 asks for. */
	private static void sendModBreakdown(CommandSourceStack source, TickCategory category,
			List<CostTracker.TypeCost> rows, long ticks) {
		Map<String, Long> byNamespace = new LinkedHashMap<>();

		for (CostTracker.TypeCost row : rows) {
			ResourceLocation id = idOf(category, row.key());
			String namespace = id == null ? "unregistered" : id.getNamespace();
			byNamespace.merge(namespace, row.totalNanos(), Long::sum);
		}

		if (byNamespace.size() < 2) {
			// One namespace is not a breakdown, it is the same line again.
			return;
		}

		List<Map.Entry<String, Long>> sorted = new ArrayList<>(byNamespace.entrySet());
		sorted.sort(Map.Entry.<String, Long>comparingByValue().reversed());

		source.sendSuccess(() -> Component.translatable("command.tickpilot.top.mods.header"), false);

		for (Map.Entry<String, Long> entry : sorted) {
			source.sendSuccess(() -> Component.translatable("command.tickpilot.top.mods.row",
					entry.getKey(), format(toMsptPerTick(entry.getValue(), ticks))), false);
		}
	}

	private static ResourceLocation idOf(TickCategory category, Object key) {
		if (category == TickCategory.ENTITIES && key instanceof EntityType<?> type) {
			return BuiltInRegistries.ENTITY_TYPE.getKey(type);
		}

		if (category == TickCategory.BLOCK_ENTITIES && key instanceof BlockEntityType<?> type) {
			return BuiltInRegistries.BLOCK_ENTITY_TYPE.getKey(type);
		}

		return null;
	}

	/** Per-instance costs are microseconds, not milliseconds; printing ms would be all zeroes. */
	private static String formatMicros(double nanos) {
		return String.format(Locale.ROOT, "%.1f", nanos / 1_000.0);
	}

	/**
	 * Prints the SPEC FR-2 category breakdown (SPEC FR-12 {@code /tickpilot top}).
	 *
	 * <p>A category with no injection point prints {@code n/a}, never {@code 0.00} — AC-2 is
	 * explicit that an unmeasured category must not look like a measured-and-idle one.
	 */
	private static int top(CommandSourceStack source) {
		try {
			TickPilotServerState state = ServerStateHolder.get(source.getServer());

			if (state == null || state.isDisabled()) {
				source.sendFailure(Component.translatable("command.tickpilot.status.unavailable"));
				return 0;
			}

			TickProfiler profiler = state.profiler();

			if (profiler.sessionTicks() == 0L) {
				source.sendSuccess(() -> Component.translatable("command.tickpilot.top.no_session"), false);
				return 1;
			}

			long ticks = profiler.sessionTicks();
			double totalMspt = toMsptPerTick(profiler.sessionNanos(TickCategory.TOTAL), ticks);

			source.sendSuccess(() -> Component.translatable("command.tickpilot.top.header",
					ticks, format(totalMspt)), false);

			for (TickCategory category : TickCategory.all()) {
				if (category == TickCategory.TOTAL) {
					continue;
				}

				Component name = Component.translatable(category.translationKey());

				// OTHER is derived from TOTAL, so it is always meaningful; the rest need a hook.
				if (category != TickCategory.OTHER && !profiler.isAvailable(category)) {
					source.sendSuccess(() -> Component.translatable("command.tickpilot.top.row_unavailable",
							name, Component.translatable("tickpilot.value.unavailable")
									.withStyle(ChatFormatting.GRAY)), false);
					continue;
				}

				double mspt = toMsptPerTick(profiler.sessionNanos(category), ticks);
				double share = totalMspt > 0.0 ? mspt / totalMspt * 100.0 : 0.0;

				source.sendSuccess(() -> Component.translatable("command.tickpilot.top.row",
						name, format(mspt), format(share)), false);
			}

			// A self-check counter that is not zero means the numbers above are wrong. Say so
			// rather than let them be read as fact.
			if (!profiler.isConsistent()) {
				source.sendSuccess(() -> Component.translatable("command.tickpilot.top.inconsistent",
						profiler.droppedFrames(), profiler.unbalancedEnds(),
						profiler.abandonedFrames(), profiler.overrunTicks())
						.withStyle(ChatFormatting.RED), false);
			}

			return 1;
		} catch (Throwable t) {
			TickPilot.LOGGER.error("/tickpilot top failed", t);
			source.sendFailure(Component.translatable("command.tickpilot.error"));
			return 0;
		}
	}

	private static double toMsptPerTick(long nanos, long ticks) {
		return ticks <= 0L ? 0.0 : (double) nanos / ticks / 1_000_000.0;
	}

	/**
	 * Re-reads {@code config/tickpilot.toml} and applies it (SPEC AC-15).
	 *
	 * <p>A file that cannot be parsed puts the server back on the defaults rather than leaving the
	 * previous values in place, which is what AC-15 asks for; the message says so explicitly, so
	 * nobody is left thinking their edits took effect. The file itself is never rewritten.
	 */
	private static int reload(CommandSourceStack source) {
		try {
			TickPilotServerState state = ServerStateHolder.get(source.getServer());

			if (state == null || state.isDisabled()) {
				source.sendFailure(Component.translatable("command.tickpilot.status.unavailable"));
				return 0;
			}

			ConfigLoadResult result = ConfigLoader.load(TickPilot.configFile());
			// The log keeps the full list even when chat only gets the first few.
			TickPilot.logConfigResult(result);

			boolean thresholdsChanged = state.reconfigure(result.config(), System.nanoTime());

			switch (result.status()) {
				case CREATED -> source.sendSuccess(() -> Component.translatable(
						"command.tickpilot.reload.created", ConfigLoader.FILE_NAME), true);
				case CREATE_FAILED -> source.sendSuccess(() -> Component.translatable(
						"command.tickpilot.reload.create_failed", ConfigLoader.FILE_NAME)
						.withStyle(ChatFormatting.YELLOW), true);
				case LOADED -> source.sendSuccess(() -> Component.translatable(
						"command.tickpilot.reload.loaded", ConfigLoader.FILE_NAME), true);
				case LOADED_WITH_PROBLEMS -> source.sendSuccess(() -> Component.translatable(
						"command.tickpilot.reload.problems", result.problems().size(),
						ConfigLoader.FILE_NAME).withStyle(ChatFormatting.YELLOW), true);
				case UNREADABLE -> source.sendSuccess(() -> Component.translatable(
						"command.tickpilot.reload.unreadable", ConfigLoader.FILE_NAME)
						.withStyle(ChatFormatting.RED), true);
			}

			sendProblems(source, result);

			if (thresholdsChanged) {
				TickBudget budget = state.budget();
				source.sendSuccess(() -> Component.translatable("command.tickpilot.reload.thresholds",
						format(budget.targetMspt()), format(budget.highMspt()),
						format(budget.criticalMspt())), false);
			}

			// UNREADABLE is a real failure even though the server carries on, so it must not report
			// success to a command block or a script.
			return result.status() == ConfigLoadResult.Status.UNREADABLE ? 0 : 1;
		} catch (Throwable t) {
			TickPilot.LOGGER.error("/tickpilot reload failed", t);
			source.sendFailure(Component.translatable("command.tickpilot.error"));
			return 0;
		}
	}

	private static void sendProblems(CommandSourceStack source, ConfigLoadResult result) {
		int shown = Math.min(result.problems().size(), MAX_PROBLEMS_SHOWN);

		for (int i = 0; i < shown; i++) {
			String problem = result.problems().get(i);
			source.sendSuccess(() -> Component.translatable("command.tickpilot.reload.problem", problem)
					.withStyle(ChatFormatting.GRAY), false);
		}

		int hidden = result.problems().size() - shown;

		if (hidden > 0) {
			source.sendSuccess(() -> Component.translatable("command.tickpilot.reload.more", hidden)
					.withStyle(ChatFormatting.GRAY), false);
		}
	}

	private static int status(CommandSourceStack source) {
		// INV-9: a command must report a problem, never throw into the command dispatcher.
		try {
			TickPilotServerState state = ServerStateHolder.get(source.getServer());

			if (state == null || state.isDisabled()) {
				source.sendFailure(Component.translatable("command.tickpilot.status.unavailable"));
				return 0;
			}

			long nowNanos = System.nanoTime();
			TickMetricsSnapshot metrics = state.snapshot(nowNanos);

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

			// The average windows carry the fixed names AC-1 gives them, so a window the server has
			// not lived through yet shows n/a rather than an average over less time than it claims
			// (same choice as AC-2 makes for an unavailable profiling category).
			source.sendSuccess(() -> Component.translatable("command.tickpilot.status.mspt",
					format(metrics.lastMspt()),
					windowed(metrics, metrics.avgMspt5s(), TickMetrics.WINDOW_5S_NANOS),
					windowed(metrics, metrics.avgMspt1m(), TickMetrics.WINDOW_1M_NANOS),
					windowed(metrics, metrics.avgMspt5m(), TickMetrics.WINDOW_5M_NANOS)), false);

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

			// A pinned NORMAL must never look like a measured one: the numbers above are real,
			// the level is not tracking them yet.
			if (budget.isWarmingUp(nowNanos / NANOS_PER_MILLI)) {
				source.sendSuccess(() -> Component.translatable("command.tickpilot.status.warming_up",
						formatDuration(budget.warmupRemainingMillis(nowNanos / NANOS_PER_MILLI)
								* NANOS_PER_MILLI)).withStyle(ChatFormatting.YELLOW), false);
			}

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

	/**
	 * @return the formatted value, or {@code n/a} when the server has not been up long enough for
	 *         {@code windowNanos} to hold a full window of measurements
	 */
	private static Object windowed(TickMetricsSnapshot metrics, double value, long windowNanos) {
		if (!metrics.covers(windowNanos)) {
			return Component.translatable("tickpilot.value.unavailable").withStyle(ChatFormatting.GRAY);
		}

		return format(value);
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
