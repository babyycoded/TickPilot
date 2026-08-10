package com.tickpilot.command;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.tickpilot.ServerStateHolder;
import com.tickpilot.TickPilot;
import com.tickpilot.TickPilotServerState;
import com.tickpilot.budget.LoadLevel;
import com.tickpilot.budget.TickBudget;
import com.tickpilot.config.ConfigLoadResult;
import com.tickpilot.config.ConfigLoader;
import com.tickpilot.metrics.OverheadMeter;
import com.tickpilot.metrics.TickMetrics;
import com.tickpilot.metrics.TickMetricsSnapshot;
import com.tickpilot.policy.PolicyDiagnostics;
import com.tickpilot.policy.ThrottleVerdict;
import com.tickpilot.profiler.CostTracker;
import com.tickpilot.profiler.TickCategory;
import com.tickpilot.profiler.TickProfiler;
import com.tickpilot.scheduler.SchedulerStats;

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

	/** How many rows each list in {@code /tickpilot explain} holds (SPEC AC-13 asks for three). */
	private static final int EXPLAIN_TOP_N = 3;

	/**
	 * How many times {@code OverheadMeter.record} runs in one tick: once around each half of the
	 * tick listener. Used to turn the mean slice into a per-tick figure.
	 */
	private static final int OVERHEAD_SLICES_PER_TICK = 2;

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
				.then(Commands.literal("explain")
						.requires(source -> source.hasPermission(2))
						.executes(context -> explain(context.getSource())))
				.then(Commands.literal("top")
						.requires(source -> source.hasPermission(2))
						.executes(context -> top(context.getSource()))
						.then(Commands.literal("entities")
								.executes(context -> topTypes(context.getSource(), TickCategory.ENTITIES)))
						.then(Commands.literal("blockentities")
								.executes(context -> topTypes(context.getSource(),
										TickCategory.BLOCK_ENTITIES))))
				.then(Commands.literal("profile")
						.requires(source -> source.hasPermission(2))
						.then(Commands.literal("stop")
								.executes(context -> profileStop(context.getSource())))
						// SPEC FR-12 bounds the session at 1..300 s. Brigadier rejects anything else
						// with its own message, which is AC-12's "understandable error, not an
						// exception" for free.
						.then(Commands.argument("seconds", IntegerArgumentType.integer(1, 300))
								.executes(context -> profileStart(context.getSource(),
										IntegerArgumentType.getInteger(context, "seconds"))))));
	}

	private static int profileStart(CommandSourceStack source, int seconds) {
		try {
			TickPilotServerState state = ServerStateHolder.get(source.getServer());

			if (state == null || state.isDisabled()) {
				source.sendFailure(Component.translatable("command.tickpilot.status.unavailable"));
				return 0;
			}

			// AC-4: starting twice is a message, never a crash.
			if (!state.startProfiling(seconds, System.nanoTime())) {
				source.sendFailure(Component.translatable("command.tickpilot.profile.already_running",
						state.profilingSecondsLeft(System.nanoTime())));
				return 0;
			}

			TickPilot.LOGGER.info("Profiling session started for {}s", seconds);
			source.sendSuccess(() -> Component.translatable("command.tickpilot.profile.started",
					seconds), true);
			return 1;
		} catch (Throwable t) {
			TickPilot.LOGGER.error("/tickpilot profile failed", t);
			source.sendFailure(Component.translatable("command.tickpilot.error"));
			return 0;
		}
	}

	private static int profileStop(CommandSourceStack source) {
		try {
			TickPilotServerState state = ServerStateHolder.get(source.getServer());

			if (state == null || state.isDisabled()) {
				source.sendFailure(Component.translatable("command.tickpilot.status.unavailable"));
				return 0;
			}

			if (!state.stopProfiling()) {
				source.sendFailure(Component.translatable("command.tickpilot.profile.not_running"));
				return 0;
			}

			TickPilot.LOGGER.info("Profiling session stopped after {} ticks",
					state.profiler().sessionTicks());
			source.sendSuccess(() -> Component.translatable("command.tickpilot.profile.stopped",
					state.profiler().sessionTicks()), true);
			// The data survives the stop, so show it straight away rather than making them ask.
			top(source);
			return 1;
		} catch (Throwable t) {
			TickPilot.LOGGER.error("/tickpilot profile stop failed", t);
			source.sendFailure(Component.translatable("command.tickpilot.error"));
			return 0;
		}
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
		aggregateByNamespace(category, rows, byNamespace);

		if (byNamespace.size() < 2) {
			// One namespace is not a breakdown, it is the same line again.
			return;
		}

		List<Map.Entry<String, Long>> sorted = sortedByCostDescending(byNamespace);

		source.sendSuccess(() -> Component.translatable("command.tickpilot.top.mods.header"), false);

		for (Map.Entry<String, Long> entry : sorted) {
			source.sendSuccess(() -> Component.translatable("command.tickpilot.top.mods.row",
					entry.getKey(), format(toMsptPerTick(entry.getValue(), ticks))), false);
		}
	}

	/**
	 * Adds {@code rows} into a namespace-keyed accumulator. Shared by the per-category breakdown of
	 * {@code top} and the combined one of {@code explain}, so both answer "which mod" the same way.
	 */
	private static void aggregateByNamespace(TickCategory category, List<CostTracker.TypeCost> rows,
			Map<String, Long> into) {
		for (CostTracker.TypeCost row : rows) {
			ResourceLocation id = idOf(category, row.key());
			String namespace = id == null ? "unregistered" : id.getNamespace();
			into.merge(namespace, row.totalNanos(), Long::sum);
		}
	}

	private static List<Map.Entry<String, Long>> sortedByCostDescending(Map<String, Long> costs) {
		List<Map.Entry<String, Long>> sorted = new ArrayList<>(costs.entrySet());
		sorted.sort(Map.Entry.<String, Long>comparingByValue().reversed());
		return sorted;
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
	 * What the throttling policies of SPEC FR-8 and FR-9 would do, and what is stopping them.
	 *
	 * <p>Nothing is thinned in this version, and the output says so on its own line rather than
	 * leaving an operator to infer it from a number. The useful figure is the last one: the reason
	 * that stopped the most objects is what an operator would have to change for thinning to do
	 * anything at all, and on a default install that reason is "nobody put anything on the
	 * allowlist", which is exactly what SPEC INV-5 intends.
	 */
	private static void sendPolicyLines(CommandSourceStack source, TickPilotServerState state) {
		PolicyDiagnostics policy = state.policyDiagnostics();

		if (policy.isEmpty()) {
			return;
		}

		source.sendSuccess(() -> Component.translatable("command.tickpilot.policy.header")
				.withStyle(ChatFormatting.GRAY), false);

		sendPolicyRow(source, policy, true);
		sendPolicyRow(source, policy, false);

		// The one number here that is not hypothetical: AI steps that were actually not run.
		if (policy.aiConsideredPerTick() > 0.0) {
			source.sendSuccess(() -> Component.translatable("command.tickpilot.policy.ai",
					format(policy.aiConsideredPerTick()), format(policy.aiSkippedPerTick()),
					state.config().minEntityUpdateIntervalTicks())
					.withStyle(policy.aiSkipped() > 0L ? ChatFormatting.GOLD : ChatFormatting.GRAY),
					false);
		}
	}

	private static void sendPolicyRow(CommandSourceStack source, PolicyDiagnostics policy,
			boolean entities) {
		long seen = entities ? policy.entitiesSeen() : policy.blockEntitiesSeen();

		if (seen == 0L) {
			return;
		}

		double eligible = entities
				? policy.eligibleEntitiesPerTick()
				: policy.eligibleBlockEntitiesPerTick();
		double perTick = policy.ticks() <= 0L ? 0.0 : (double) seen / policy.ticks();

		source.sendSuccess(() -> Component.translatable(
				entities ? "command.tickpilot.policy.entities" : "command.tickpilot.policy.block_entities",
				format(perTick), format(eligible)), false);

		// The whole breakdown, not just the top reason: the commonest one is often something an
		// operator cannot act on ("they are all protected"), while the line under it is the one
		// that says the allowlist is empty.
		for (PolicyDiagnostics.Blocker blocker : policy.blockersDescending(entities)) {
			source.sendSuccess(() -> Component.translatable("command.tickpilot.policy.reason",
					format(blocker.perTick()),
					Component.translatable(blocker.verdict().translationKey())), false);
		}
	}

	/**
	 * The deferred-task lines of SPEC FR-12 and AC-13, shared by {@code status} and
	 * {@code explain} so both report the queue the same way.
	 *
	 * <p>A server where no mod uses the API says so instead of printing a row of zeros: "nobody
	 * submitted anything" and "the queue is keeping up" are different facts, and only the second
	 * one is about performance.
	 */
	private static void sendSchedulerLine(CommandSourceStack source, TickPilotServerState state) {
		SchedulerStats scheduler = state.scheduler().stats();

		if (scheduler.isUnused()) {
			source.sendSuccess(() -> Component.translatable("command.tickpilot.scheduler.idle",
					scheduler.maxQueued()).withStyle(ChatFormatting.GRAY), false);
			return;
		}

		source.sendSuccess(() -> Component.translatable("command.tickpilot.scheduler",
				scheduler.queued(), scheduler.maxQueued(), scheduler.peakQueued(),
				scheduler.executedFromQueue(), scheduler.executedForced(),
				format(scheduler.msptPerTick())), false);

		if (scheduler.lost() > 0L) {
			source.sendSuccess(() -> Component.translatable("command.tickpilot.scheduler.lost",
					scheduler.dropped(), scheduler.rejected(), scheduler.discarded())
					.withStyle(ChatFormatting.YELLOW), false);
		}

		if (scheduler.failed() > 0L) {
			source.sendSuccess(() -> Component.translatable("command.tickpilot.scheduler.failed",
					scheduler.failed()).withStyle(ChatFormatting.YELLOW), false);
		}

		if (scheduler.emergency()) {
			source.sendSuccess(() -> Component.translatable("command.tickpilot.scheduler.emergency")
					.withStyle(ChatFormatting.RED), false);
		}

		if (!state.scheduler().isDeferralEnabled()) {
			source.sendSuccess(() -> Component.translatable("command.tickpilot.scheduler.disabled")
					.withStyle(ChatFormatting.GRAY), false);
		}
	}

	/**
	 * The human-readable breakdown of SPEC FR-13 (SPEC FR-12 {@code /tickpilot explain}).
	 *
	 * <p>Everything AC-13 lists is printed from something that was measured, and everything that
	 * was not measured says so instead of showing a zero: a server with no profiling session gets
	 * one honest line where the category breakdown would be, and a server where no mod uses the
	 * scheduler API says that rather than printing an empty queue.
	 *
	 * <p>The single recommendation and its effect estimate come from {@link ExplainAdvisor}, which
	 * holds the whole decision table and is unit-tested without the game.
	 */
	private static int explain(CommandSourceStack source) {
		try {
			TickPilotServerState state = ServerStateHolder.get(source.getServer());

			if (state == null || state.isDisabled()) {
				source.sendFailure(Component.translatable("command.tickpilot.status.unavailable"));
				return 0;
			}

			long nowNanos = System.nanoTime();
			TickMetricsSnapshot metrics = state.snapshot(nowNanos);

			if (metrics.isEmpty()) {
				// Nothing measured at all is the one case with no verdict of any kind.
				source.sendSuccess(() -> Component.translatable("command.tickpilot.status.no_metrics"),
						false);
				return 1;
			}

			TickBudget budget = state.budget();
			boolean warmingUp = budget.isWarmingUp(nowNanos / NANOS_PER_MILLI);

			sendExplainMetrics(source, state, metrics, budget, warmingUp, nowNanos);
			ExplainAdvisor.Profile profile = buildProfile(state);
			sendExplainProfile(source, state, profile);
			sendExplainRecommendation(source, ExplainAdvisor.advise(metrics, budget.level(),
					budget.targetMspt(), budget.criticalMspt(), warmingUp,
					state.isTickRateModified(), profile));
			return 1;
		} catch (Throwable t) {
			TickPilot.LOGGER.error("/tickpilot explain failed", t);
			source.sendFailure(Component.translatable("command.tickpilot.error"));
			return 0;
		}
	}

	/**
	 * The AC-13 metric lines, plus the state that decides how much they are worth.
	 *
	 * <p>The two percentile pairs are used for what they were introduced for in Phase 3: the 1 min
	 * pair says whether drops are happening now, the history pair whether they happened earlier.
	 * Saying "there were drops" from a single number cannot distinguish an ongoing problem from a
	 * recovered one.
	 */
	private static void sendExplainMetrics(CommandSourceStack source, TickPilotServerState state,
			TickMetricsSnapshot metrics, TickBudget budget, boolean warmingUp, long nowNanos) {
		source.sendSuccess(() -> Component.translatable("command.tickpilot.explain.header",
				metrics.totalTicks(), formatDuration(metrics.uptimeNanos())), false);

		// Not a refusal to answer: the numbers below are real even at thirty seconds of uptime, and
		// a server dying that early really is dying. This says what the window covers so that an
		// early verdict is read as early (SPEC AC-13, "say when there is little data").
		if (!ExplainAdvisor.hasFullWindow(metrics)) {
			source.sendSuccess(() -> Component.translatable("command.tickpilot.explain.short_uptime",
					formatDuration(metrics.uptimeNanos())).withStyle(ChatFormatting.YELLOW), false);
		}

		source.sendSuccess(() -> Component.translatable("command.tickpilot.explain.tps",
				Component.literal(format(metrics.tps())).withStyle(tpsColour(metrics.tps())),
				format(metrics.avgMspt5s()),
				windowed(metrics, metrics.avgMspt1m(), TickMetrics.WINDOW_1M_NANOS)), false);

		source.sendSuccess(() -> Component.translatable("command.tickpilot.explain.percentiles",
				format(metrics.p95Mspt1m()), format(metrics.p99Mspt1m()),
				formatDuration(metrics.shortPercentileSpanNanos())), false);

		source.sendSuccess(() -> Component.translatable("command.tickpilot.explain.max",
				format(metrics.maxMspt()), formatDuration(metrics.maxAgeNanos()),
				format(metrics.p99MsptHistory()), formatDuration(metrics.retainedSpanNanos())), false);

		double critical = budget.criticalMspt();

		if (ExplainAdvisor.spikingNow(metrics, critical)) {
			source.sendSuccess(() -> Component.translatable("command.tickpilot.explain.note.spiking_now",
					formatDuration(metrics.shortPercentileSpanNanos()), format(metrics.p99Mspt1m()),
					format(critical), format(metrics.avgMspt5s()))
					.withStyle(ChatFormatting.GOLD), false);
		} else if (ExplainAdvisor.spikedBefore(metrics, critical)) {
			source.sendSuccess(() -> Component.translatable("command.tickpilot.explain.note.past_spikes",
					format(metrics.p99Mspt1m()), format(metrics.p99MsptHistory()),
					formatDuration(metrics.retainedSpanNanos()))
					.withStyle(ChatFormatting.YELLOW), false);
		}

		LoadLevel level = budget.level();

		source.sendSuccess(() -> Component.translatable("command.tickpilot.explain.load",
				Component.translatable(level.translationKey()).withStyle(levelColour(level)),
				Component.translatable(state.config().effectiveMode().translationKey()),
				Component.translatable(state.config().enableAdaptiveMode()
						? "tickpilot.value.enabled" : "tickpilot.value.disabled")), false);

		// effectiveMode() silently overrides default_mode, so a mode nobody configured must not
		// appear without its reason.
		if (state.config().safeCompatibilityMode()) {
			source.sendSuccess(() -> Component.translatable("command.tickpilot.explain.mode_forced")
					.withStyle(ChatFormatting.GRAY), false);
		}

		if (warmingUp) {
			source.sendSuccess(() -> Component.translatable("command.tickpilot.status.warming_up",
					formatDuration(budget.warmupRemainingMillis(nowNanos / NANOS_PER_MILLI)
							* NANOS_PER_MILLI)).withStyle(ChatFormatting.YELLOW), false);
		}

		if (state.isTickRateFrozen()) {
			source.sendSuccess(() -> Component.translatable("command.tickpilot.status.frozen")
					.withStyle(ChatFormatting.YELLOW), false);
		} else if (state.tickRate() != 20.0f) {
			source.sendSuccess(() -> Component.translatable("command.tickpilot.status.tickrate",
					format(state.tickRate())).withStyle(ChatFormatting.YELLOW), false);
		}

		// AC-13 asks for the deferred task count. Since FR-6 exists there is a real queue to count,
		// and a zero here now means a measured empty queue rather than a missing feature.
		sendSchedulerLine(source, state);
	}

	/**
	 * Flattens what the profiler knows into the input {@link ExplainAdvisor} reads.
	 *
	 * <p>The dominant category is picked over the categories that have a working hook, plus OTHER,
	 * which is derived from TOTAL and therefore always meaningful. A category that was never
	 * measured cannot win by being zero, and TOTAL is excluded because it is the whole tick rather
	 * than a part of it.
	 */
	private static ExplainAdvisor.Profile buildProfile(TickPilotServerState state) {
		TickProfiler profiler = state.profiler();
		long ticks = profiler.sessionTicks();

		if (ticks == 0L) {
			return ExplainAdvisor.Profile.none();
		}

		TickCategory dominant = null;
		long dominantNanos = -1L;

		for (TickCategory category : TickCategory.all()) {
			if (category == TickCategory.TOTAL) {
				continue;
			}

			if (category != TickCategory.OTHER && !profiler.isAvailable(category)) {
				continue;
			}

			long nanos = profiler.sessionNanos(category);

			if (nanos > dominantNanos) {
				dominantNanos = nanos;
				dominant = category;
			}
		}

		double totalMspt = toMsptPerTick(profiler.sessionNanos(TickCategory.TOTAL), ticks);
		double dominantMspt = toMsptPerTick(Math.max(0L, dominantNanos), ticks);
		double share = totalMspt > 0.0 ? dominantMspt / totalMspt * 100.0 : 0.0;

		String topTypeId = null;
		double topTypeMspt = 0.0;
		double topTypeInstances = 0.0;
		List<CostTracker.TypeCost> top = dominant == null
				? List.of()
				: state.costs().top(dominant, 1);

		if (!top.isEmpty()) {
			CostTracker.TypeCost row = top.get(0);
			ResourceLocation id = idOf(dominant, row.key());
			topTypeId = id == null ? "unregistered" : id.toString();
			topTypeMspt = toMsptPerTick(row.totalNanos(), ticks);
			topTypeInstances = (double) row.invocations() / ticks;
		}

		return new ExplainAdvisor.Profile(ticks, profiler.isConsistent(), dominant, dominantMspt,
				share, totalMspt, topTypeId, topTypeMspt, topTypeInstances);
	}

	/** The AC-13 breakdown: main category, top-3 entities, top-3 block entities, top-3 mods. */
	private static void sendExplainProfile(CommandSourceStack source, TickPilotServerState state,
			ExplainAdvisor.Profile profile) {
		if (!profile.hasSession()) {
			source.sendSuccess(() -> Component.translatable("command.tickpilot.explain.no_session",
					ExplainAdvisor.SUGGESTED_PROFILE_SECONDS).withStyle(ChatFormatting.GRAY), false);
			return;
		}

		source.sendSuccess(() -> Component.translatable("command.tickpilot.explain.session",
				profile.sessionTicks(), format(profile.totalMsptPerTick())), false);

		source.sendSuccess(() -> Component.translatable("command.tickpilot.explain.main_cost",
				Component.translatable(profile.dominant().translationKey()),
				format(profile.dominantMsptPerTick()), format(profile.dominantSharePercent())), false);

		sendExplainTypes(source, state, TickCategory.ENTITIES,
				"command.tickpilot.explain.top_entities");
		sendExplainTypes(source, state, TickCategory.BLOCK_ENTITIES,
				"command.tickpilot.explain.top_block_entities");
		sendExplainMods(source, state, profile.sessionTicks());

		if (!state.profiler().isConsistent()) {
			source.sendSuccess(() -> Component.translatable("command.tickpilot.top.inconsistent",
					state.profiler().droppedFrames(), state.profiler().unbalancedEnds(),
					state.profiler().abandonedFrames(), state.profiler().overrunTicks())
					.withStyle(ChatFormatting.RED), false);
		}
	}

	private static void sendExplainTypes(CommandSourceStack source, TickPilotServerState state,
			TickCategory category, String headerKey) {
		long ticks = state.profiler().sessionTicks();
		List<CostTracker.TypeCost> rows = state.costs().top(category, EXPLAIN_TOP_N);

		source.sendSuccess(() -> Component.translatable(headerKey), false);

		if (rows.isEmpty()) {
			source.sendSuccess(() -> Component.translatable("command.tickpilot.explain.list_empty")
					.withStyle(ChatFormatting.GRAY), false);
			return;
		}

		for (CostTracker.TypeCost row : rows) {
			ResourceLocation id = idOf(category, row.key());

			source.sendSuccess(() -> Component.translatable("command.tickpilot.explain.type_row",
					id == null ? "unregistered" : id.toString(),
					format(toMsptPerTick(row.totalNanos(), ticks)),
					format((double) row.invocations() / ticks)), false);
		}
	}

	/**
	 * The top mod IDs of AC-13, over both per-type categories at once.
	 *
	 * <p>Unlike the breakdown under {@code top}, this one aggregates every tracked type rather than
	 * the printed top-N: a mod whose cost is spread over twenty cheap entity types would otherwise
	 * never appear, which is the opposite of what a "top mod IDs" list is for.
	 */
	private static void sendExplainMods(CommandSourceStack source, TickPilotServerState state,
			long ticks) {
		Map<String, Long> byNamespace = new LinkedHashMap<>();
		aggregateByNamespace(TickCategory.ENTITIES,
				state.costs().top(TickCategory.ENTITIES, Integer.MAX_VALUE), byNamespace);
		aggregateByNamespace(TickCategory.BLOCK_ENTITIES,
				state.costs().top(TickCategory.BLOCK_ENTITIES, Integer.MAX_VALUE), byNamespace);

		source.sendSuccess(() -> Component.translatable("command.tickpilot.explain.top_mods"), false);

		if (byNamespace.isEmpty()) {
			source.sendSuccess(() -> Component.translatable("command.tickpilot.explain.list_empty")
					.withStyle(ChatFormatting.GRAY), false);
			return;
		}

		List<Map.Entry<String, Long>> sorted = sortedByCostDescending(byNamespace);

		for (int i = 0; i < Math.min(EXPLAIN_TOP_N, sorted.size()); i++) {
			Map.Entry<String, Long> entry = sorted.get(i);

			source.sendSuccess(() -> Component.translatable("command.tickpilot.explain.mod_row",
					entry.getKey(), format(toMsptPerTick(entry.getValue(), ticks))), false);
		}
	}

	/** Prints the one recommendation of AC-13 and its effect estimate, in that order. */
	private static void sendExplainRecommendation(CommandSourceStack source,
			ExplainAdvisor.Recommendation recommendation) {
		source.sendSuccess(() -> Component.translatable(recommendation.messageKey(),
				recommendation.args().toArray()).withStyle(ChatFormatting.AQUA), false);

		source.sendSuccess(() -> Component.translatable(recommendation.effect().translationKey(),
				recommendation.effectArgs().toArray()), false);
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
			// The registries are only reachable from here, not from the state, so the id lists are
			// re-resolved on the command path (SPEC INV-5, FR-15).
			TickPilot.refreshTypeLists(state, result.config());

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

			// SPEC INV-10 / FR-12: the mod's own cost, measured rather than asserted. Two slices
			// per tick, one around each half of the tick listener.
			OverheadMeter overhead = state.overhead();

			if (overhead.samples() > 0L) {
				boolean profiling = state.isProfiling();
				source.sendSuccess(() -> Component.translatable("command.tickpilot.status.overhead",
						format(overhead.msptPerTick(OVERHEAD_SLICES_PER_TICK)),
						format(overhead.percentOf(OVERHEAD_SLICES_PER_TICK, metrics.avgMspt5s())),
						formatMicros(overhead.peakNanos())), false);

				// Honesty about what that number does and does not cover: while a session runs,
				// the hooks' own bookkeeping sits inside the categories they measure.
				if (profiling) {
					source.sendSuccess(() -> Component.translatable(
							"command.tickpilot.status.overhead_profiling")
							.withStyle(ChatFormatting.GRAY), false);
				}
			}

			// SPEC FR-12: `status` reports the deferred task queue of FR-6.
			sendSchedulerLine(source, state);

			// SPEC FR-7/FR-8/FR-9, diagnostic half: what thinning would do, and what stops it.
			sendPolicyLines(source, state);

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
