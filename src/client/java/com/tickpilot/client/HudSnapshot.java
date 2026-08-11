package com.tickpilot.client;

import com.tickpilot.budget.LoadLevel;
import com.tickpilot.config.AdaptiveMode;
import com.tickpilot.profiler.TickCategory;

/**
 * Everything the HUD draws, frozen at one instant (SPEC FR-20).
 *
 * <h2>Why a record of primitives and not a reference to the server state</h2>
 * This is the only thing that crosses from the server thread to the render thread. Handing the HUD
 * a {@code TickPilotServerState} instead would mean the render thread walking a six-thousand-entry
 * ring buffer that the server thread is writing, every frame — a torn read of a {@code long} is
 * permitted by the memory model, and the cost would scale with frame rate rather than tick rate.
 * A record built on the server thread and published through one {@code volatile} reference has
 * neither problem, and it is also the literal reading of the phase requirement that the data comes
 * from the integrated server's state rather than from rendering.
 *
 * <p>It also keeps SPEC INV-7 and AC-19 honest: the HUD holds no reference to a server, a world or
 * a config, so there is nothing here that could outlive the world it describes.
 *
 * @param tps                 ticks per second, capped at 20
 * @param msptLast            the last completed tick, in milliseconds
 * @param msptAvg5s           the 5 s average tick cost, in milliseconds
 * @param loadLevel           the load level held at that moment
 * @param mode                the intervention mode actually in force
 * @param adaptiveEnabled     whether adaptive behaviour is switched on at all
 * @param deferredQueued      tasks waiting in the FR-6 scheduler
 * @param dominant            the costliest measured category, or {@code null} without a profiling
 *                            session — the honest answer, since FR-4 keeps deep profiling off
 *                            unless somebody asked for it
 * @param dominantSharePercent that category's share of the measured tick
 * @param profiling           whether a profiling session is running
 * @param tickRateModified    whether {@code /tick freeze} or {@code /tick rate} explains a low TPS
 */
public record HudSnapshot(
		double tps,
		double msptLast,
		double msptAvg5s,
		LoadLevel loadLevel,
		AdaptiveMode mode,
		boolean adaptiveEnabled,
		int deferredQueued,
		TickCategory dominant,
		double dominantSharePercent,
		boolean profiling,
		boolean tickRateModified) {
}
