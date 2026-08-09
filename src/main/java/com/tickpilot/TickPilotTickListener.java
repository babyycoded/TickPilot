package com.tickpilot;

import com.tickpilot.budget.LoadLevelTransition;

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
			state.onTickStart(System.nanoTime());
		} catch (Throwable t) {
			state.disable("tick start measurement failed: " + t);
		}
	}

	private static void onEndTick(MinecraftServer server) {
		TickPilotServerState state = ServerStateHolder.get(server);

		if (state == null || state.isDisabled()) {
			return;
		}

		try {
			// SPEC AC-1b: /tick freeze and /tick rate change what a low TPS means, so the state
			// is captured every tick and shown by `status` instead of being read as overload.
			ServerTickRateManager tickRate = server.tickRateManager();
			state.onTickRateState(tickRate.isFrozen(), tickRate.runsNormally(), tickRate.tickrate());

			LoadLevelTransition transition = state.onTickEnd(System.nanoTime());

			if (transition != null) {
				// SPEC AC-5 / AC-16: one line per transition, never per tick.
				TickPilot.LOGGER.info("Load level {} -> {} (avg MSPT {})",
						transition.from(), transition.to(),
						String.format(java.util.Locale.ROOT, "%.2f", transition.avgMspt()));
			}
		} catch (Throwable t) {
			state.disable("tick end measurement failed: " + t);
		}
	}
}
