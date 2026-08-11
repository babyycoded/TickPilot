package com.tickpilot.client;

import com.tickpilot.ServerStateHolder;
import com.tickpilot.TickPilotServerState;
import com.tickpilot.config.TickPilotConfig;
import com.tickpilot.metrics.TickMetrics;
import com.tickpilot.profiler.TickCategory;
import com.tickpilot.profiler.TickProfiler;

import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;

import net.minecraft.server.MinecraftServer;

/**
 * Builds the HUD's snapshot on the integrated server's own thread (SPEC FR-20, FR-18).
 *
 * <h2>Why the sampling lives on the server side of the split</h2>
 * The phase requires the HUD's numbers to come from the integrated server's state rather than from
 * rendering, and this is what that means in practice: the read happens on the thread that owns the
 * data, and the render thread only ever picks up a finished immutable record. Registering a
 * {@code ServerTickEvents} callback from the client entrypoint is not a layering violation — the
 * event class is common, the callback only ever fires for an integrated server, because a dedicated
 * server never runs the client entrypoint that registers it.
 *
 * <h2>Cost</h2>
 * Nothing at all with {@code client_hud_enabled = false}: one field read per tick and a return
 * (SPEC INV-3, INV-10). With the HUD on, a snapshot is built {@value #SAMPLE_INTERVAL_TICKS} ticks
 * apart rather than every tick — twice a second is well past what a human reads off a HUD, and it
 * keeps nineteen out of twenty ticks free of the work.
 *
 * <p>The snapshot deliberately avoids {@code TickPilotServerState.snapshot(long)}, which is what
 * {@code /tickpilot status} uses: that computes percentiles over the whole ring buffer, which is
 * the right price for a command run a few times a session and the wrong one for something on a
 * timer. The HUD needs TPS, two averages and a few counters, all of which are cheap reads.
 */
final class HudSampler {
	/** Ticks between snapshots. 10 at 20 TPS is twice a second. */
	static final int SAMPLE_INTERVAL_TICKS = 10;

	private static int tickCounter;

	private HudSampler() {
	}

	/** Subscribes the sampler. Called once from the client entrypoint. */
	static void register() {
		ServerTickEvents.END_SERVER_TICK.register(HudSampler::onEndTick);

		// Both, deliberately. STOPPING is where the state is released, so a snapshot taken after it
		// would describe a world being torn down; STOPPED is the belt-and-braces clear that
		// guarantees nothing from world A can be on screen while world B loads (SPEC AC-19).
		ServerLifecycleEvents.SERVER_STOPPING.register(server -> HudState.clear());
		ServerLifecycleEvents.SERVER_STOPPED.register(server -> HudState.clear());
	}

	private static void onEndTick(MinecraftServer server) {
		TickPilotServerState state = ServerStateHolder.get(server);

		if (state == null || state.isDisabled()) {
			return;
		}

		TickPilotConfig config = state.config();

		if (!config.clientHudEnabled()) {
			// Cleared rather than left alone: an operator switching the HUD off with
			// /tickpilot reload expects it to go away, not to freeze on its last reading.
			if (HudState.current() != null) {
				HudState.clear();
			}

			return;
		}

		if (++tickCounter < SAMPLE_INTERVAL_TICKS) {
			return;
		}

		tickCounter = 0;

		// INV-9: the HUD is the least important thing on this server and must never be able to
		// stop its tick. A failure here costs a stale HUD, nothing more.
		try {
			HudState.publish(sample(state));
		} catch (Throwable t) {
			HudState.clear();
			state.disable("client HUD sampling failed: " + t);
		}
	}

	private static HudSnapshot sample(TickPilotServerState state) {
		long nowNanos = System.nanoTime();
		TickMetrics metrics = state.metrics();
		TickProfiler profiler = state.profiler();
		TickPilotConfig config = state.config();

		TickCategory dominant = profiler.dominantCategory();
		double share = 0.0;

		if (dominant != null) {
			long total = profiler.sessionNanos(TickCategory.TOTAL);
			share = total > 0L ? (double) profiler.sessionNanos(dominant) / total * 100.0 : 0.0;
		}

		return new HudSnapshot(
				metrics.tps(nowNanos),
				metrics.lastMspt(),
				metrics.averageMspt5s(nowNanos),
				state.loadLevel(),
				config.effectiveMode(),
				config.enableAdaptiveMode(),
				state.scheduler().stats().queued(),
				dominant,
				share,
				state.isProfiling(),
				state.isTickRateModified());
	}
}
