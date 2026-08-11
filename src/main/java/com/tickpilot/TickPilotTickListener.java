package com.tickpilot;

import java.util.Locale;

import com.tickpilot.budget.LoadLevelTransition;
import com.tickpilot.chunk.ChunkBudget;
import com.tickpilot.chunk.ChunkBudgetHook;
import com.tickpilot.chunk.ChunkBudgetTracker;
import com.tickpilot.policy.PolicyHook;
import com.tickpilot.policy.TickPolicy;
import com.tickpilot.profiler.TickCategory;
import com.tickpilot.profiler.TickProfiler;
import com.tickpilot.zones.ZoneTracker;

import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.ServerTickRateManager;

/**
 * Feeds the server tick clock into {@link TickPilotServerState} (SPEC FR-1).
 *
 * <h2>Why events and not a Mixin</h2>
 * SPEC MX-1 says to look for a Fabric event first. {@code ServerTickEvents.START_SERVER_TICK} and
 * {@code END_SERVER_TICK} exist and are enough, so TickPilot adds no Mixin for measurement.
 *
 * <p>What the pair actually spans, verified against {@code MinecraftServerMixin} in
 * fabric-lifecycle-events-v1 2.6.0 (the module shipped with fabric-api 0.116.15+1.21.1): START is
 * injected at the {@code MinecraftServer.tickChildren(BooleanSupplier)} call site inside
 * {@code tickServer(BooleanSupplier)}, END at the {@code TAIL} of {@code tickServer}. So the
 * measurement covers the body of the tick — all world, entity, block entity, chunk and network
 * work — but not the few statements of {@code tickServer} that run before {@code tickChildren},
 * and not the task draining that {@code runServer} does outside {@code tickServer}.
 *
 * <p>The result is a small, systematic underestimate against vanilla's own
 * {@code getTickTimesNanos()}. It is not corrected with a Mixin into {@code runServer}, because
 * the missing part is constant overhead that changes none of the decisions the mod makes —
 * comparing ticks to each other, percentiles, and the load level are all unaffected — while a
 * Mixin there would be a real compatibility risk against other performance mods. Recorded as
 * SPEC §13 entry #8.
 *
 * <h2>Threading</h2>
 * Both callbacks run on the server thread, which is where {@link TickPilotServerState} expects
 * its writes (SPEC INV-1). Nothing here reads world state; the only server object touched is the
 * tick rate manager, which holds plain primitives.
 */
final class TickPilotTickListener {
	private TickPilotTickListener() {
	}

	/** Subscribes both tick events. Called once from the mod entrypoint. */
	static void register() {
		ServerTickEvents.START_SERVER_TICK.register(TickPilotTickListener::onStartTick);
		ServerTickEvents.END_SERVER_TICK.register(TickPilotTickListener::onEndTick);
	}

	private static void onStartTick(MinecraftServer server) {
		TickPilotServerState state = ServerStateHolder.get(server);

		if (state == null || state.isDisabled()) {
			return;
		}

		// INV-9: measurement must never be able to take the server down.
		try {
			// The same reading opens the tick and starts the overhead measurement, so timing
			// ourselves costs one extra nanoTime() per half-tick and not two (SPEC INV-10).
			long startNanos = System.nanoTime();
			state.onTickStart(startNanos);
			attachPolicy(server, state);
			openChunkBudgetTick(server, state);
			state.recordOverhead(System.nanoTime() - startNanos);
		} catch (Throwable t) {
			state.disable("tick start measurement failed: " + t);
		}
	}

	/**
	 * Refills the activity zones for this tick and parks everything the throttling diagnostics
	 * need (SPEC FR-7, FR-8, FR-9).
	 *
	 * <p>Costs one pass over each world's player list per tick — two doubles copied per player —
	 * and is measured as TickPilot's own overhead, which it is. Everything after this point reads
	 * coordinates rather than players.
	 *
	 * <p>Nothing here changes what the game does. The verdicts are counted and discarded; the half
	 * of SPEC FR-8/FR-9 that skips ticks is separate, comes later, and starts from these numbers.
	 */
	private static void attachPolicy(MinecraftServer server, TickPilotServerState state) {
		ZoneTracker zones = state.zones();
		zones.beginTick(server);
		state.policyDiagnostics().onTick();

		PolicyHook.attach(state.policyDiagnostics(), zones, state.typeLists(),
				state.effectiveMode(), state.loadLevel(),
				state.config().enableAdaptiveMode(),
				state.config().minEntityUpdateIntervalTicks());
	}

	/**
	 * Refills the chunk classifier and opens this tick's allowance (SPEC FR-10, AC-10).
	 *
	 * <p>Note where the park is <em>not</em>: {@code ChunkBudgetHook} is attached for as long as the
	 * server runs, from {@code TickPilot.onServerStarted}, and not here. Chunk generation is drained
	 * mostly outside the span the Fabric tick events cover, so a per-tick park never sees it — that
	 * was measured on a live server, not assumed. What belongs here is the allowance, which is
	 * per tick by definition.
	 *
	 * <p>Skipped entirely when the feature is off, which is the default (SPEC INV-3): with the flag
	 * unset this costs one boolean read per tick, nothing is classified, and the Mixins find a
	 * disabled budget and return immediately (SPEC INV-10).
	 *
	 * <p>Note what the two flags separate. Once an operator switches the feature on, classification
	 * and the counters run at every load level so that {@code status} can say where chunk generation
	 * demand comes from; only the second flag, which follows the FR-11 mode table exactly as the
	 * entity policies do, decides whether anything is actually held back.
	 */
	private static void openChunkBudgetTick(MinecraftServer server, TickPilotServerState state) {
		ChunkBudget budget = state.chunkBudget();

		if (!state.chunkBudgetEnabled()) {
			budget.configure(false, false, state.config().maxChunkOperationsPerTick());
			return;
		}

		ChunkBudgetTracker tracker = state.chunkTracker();
		tracker.beginTick(server);

		budget.configure(true,
				TickPolicy.intervenesAt(state.effectiveMode(), state.loadLevel()),
				state.config().maxChunkOperationsPerTick());
		budget.beginTick(tracker.tick());
	}

	/**
	 * Writes the SPEC AC-4 end-of-session report to the server log: one block of lines, once,
	 * which is well inside what AC-16 allows.
	 *
	 * <p>Only the category totals. The per-type top-N stays behind {@code /tickpilot top entities}
	 * so that a finished session does not dump fifty lines into the console unasked.
	 */
	private static void logProfilingReport(TickPilotServerState state) {
		TickProfiler profiler = state.profiler();
		long ticks = profiler.sessionTicks();

		if (ticks == 0L) {
			TickPilot.LOGGER.info("Profiling session finished with no ticks measured");
			return;
		}

		double total = msptPerTick(profiler.sessionNanos(TickCategory.TOTAL), ticks);
		TickPilot.LOGGER.info("Profiling session finished: {} ticks, {} ms/tick total", ticks,
				String.format(Locale.ROOT, "%.2f", total));

		for (TickCategory category : TickCategory.all()) {
			if (category == TickCategory.TOTAL) {
				continue;
			}

			if (category != TickCategory.OTHER && !profiler.isAvailable(category)) {
				TickPilot.LOGGER.info("  {}: n/a", category);
				continue;
			}

			double mspt = msptPerTick(profiler.sessionNanos(category), ticks);
			TickPilot.LOGGER.info("  {}: {} ms/tick ({}%)", category,
					String.format(Locale.ROOT, "%.2f", mspt),
					String.format(Locale.ROOT, "%.1f", total > 0.0 ? mspt / total * 100.0 : 0.0));
		}

		if (!profiler.isConsistent()) {
			TickPilot.LOGGER.warn("  these numbers are not trustworthy: dropped={} unbalanced={} "
					+ "abandoned={} overrun={}", profiler.droppedFrames(), profiler.unbalancedEnds(),
					profiler.abandonedFrames(), profiler.overrunTicks());
		}

		TickPilot.LOGGER.info("  run /tickpilot top entities or top blockentities for the breakdown");
	}

	private static double msptPerTick(long nanos, long ticks) {
		return ticks <= 0L ? 0.0 : (double) nanos / ticks / 1_000_000.0;
	}

	/**
	 * Closes the chunk budget's tick and logs an emergency release if one happened (SPEC FR-10,
	 * AC-16).
	 *
	 * <p>One line per release, never per tick: the budget reports the event exactly once and then
	 * runs uncapped for thirty seconds, which is the same cooldown AC-16 asks for. Its own
	 * try/catch, because this runs before the measurement block and a failure here must not be
	 * reported as a measurement failure (SPEC INV-9).
	 */
	private static void closeChunkBudgetTick(TickPilotServerState state) {
		try {
			ChunkBudget budget = state.chunkBudget();

			if (!budget.isEnabled()) {
				return;
			}

			budget.endTick();

			if (!budget.consumeLiftEvent()) {
				return;
			}

			if (budget.liftReason() == ChunkBudget.LiftReason.SUSPECTED_BLOCK) {
				TickPilot.LOGGER.warn("Chunk budget lifted: nothing was dispatched for {} ms while "
						+ "chunk work waited, which is what a server thread blocked on a held chunk "
						+ "looks like. The cap is off for the next {} ticks. If this repeats, raise "
						+ "max_chunk_operations_per_tick or set enable_chunk_budget = false",
						ChunkBudget.STALL_SUSPECT_MILLIS, ChunkBudget.LIFT_DURATION_TICKS);
			} else {
				TickPilot.LOGGER.warn("Chunk budget lifted: the cap of {} held chunk work back on {} "
						+ "consecutive ticks, so it is the cap and not the load that is shaping this "
						+ "server. Off for the next {} ticks. Raise max_chunk_operations_per_tick if "
						+ "this repeats", state.config().maxChunkOperationsPerTick(),
						ChunkBudget.SATURATED_TICKS_BEFORE_LIFT, ChunkBudget.LIFT_DURATION_TICKS);
			}
		} catch (Throwable t) {
			state.disable("chunk budget failed: " + t);
		}
	}

	private static void onEndTick(MinecraftServer server) {
		TickPilotServerState state = ServerStateHolder.get(server);

		if (state == null || state.isDisabled()) {
			return;
		}

		// Unparked first and unconditionally: no Mixin may see a live tally outside the tick, and
		// nothing may stay parked across a world (SPEC INV-7).
		PolicyHook.detach();
		closeChunkBudgetTick(state);

		try {
			// SPEC AC-1b: /tick freeze and /tick rate change what a low TPS means, so the state
			// is captured every tick and shown by `status` instead of being read as overload.
			// Taken first so it is both the tick's end timestamp and the start of the overhead
			// measurement: everything below is TickPilot's own work, and none of it belongs in the
			// tick duration (SPEC INV-10).
			long nowNanos = System.nanoTime();

			ServerTickRateManager tickRate = server.tickRateManager();
			state.onTickRateState(tickRate.isFrozen(), tickRate.runsNormally(), tickRate.tickrate());

			LoadLevelTransition transition = state.onTickEnd(nowNanos);

			// SPEC AC-4: a timed session prints its report when it runs out. To the log, because
			// the player who started it may well have logged off by now, and because the console
			// is where an operator profiling a dedicated server is looking anyway.
			if (state.profilingJustExpired(nowNanos)) {
				logProfilingReport(state);
			}

			if (transition != null) {
				// SPEC AC-5 / AC-16: one line per transition, never per tick.
				TickPilot.LOGGER.info("Load level {} -> {} (avg MSPT {})",
						transition.from(), transition.to(),
						String.format(Locale.ROOT, "%.2f", transition.avgMspt()));
			}

			// Last of TickPilot's own work, so the measurement covers everything this half of the
			// listener did, the end-of-session report included (SPEC INV-10).
			state.recordOverhead(System.nanoTime() - nowNanos);

		} catch (Throwable t) {
			state.disable("tick end measurement failed: " + t);
			return;
		}

		// Deliberately outside the overhead measurement: what runs here is other mods' work,
		// submitted through the API, and charging it to TickPilot's overhead would misreport both
		// (SPEC FR-6, INV-10). The scheduler times itself; `status` prints that separately.
		//
		// Its own try/catch as well: a task that throws is already caught inside the scheduler, so
		// anything arriving here is a failure of the scheduler itself and must not be reported as
		// a measurement failure (SPEC INV-9).
		try {
			state.runScheduledWork();
		} catch (Throwable t) {
			state.disable("adaptive scheduler failed: " + t);
		}
	}
}
