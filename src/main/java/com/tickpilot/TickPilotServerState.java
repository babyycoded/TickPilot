package com.tickpilot;

import com.tickpilot.budget.LoadLevel;
import com.tickpilot.budget.LoadLevelTransition;
import com.tickpilot.budget.TickBudget;
import com.tickpilot.metrics.TickMetrics;
import com.tickpilot.metrics.TickMetricsSnapshot;

/**
 * Owns all TickPilot state belonging to exactly one running server.
 *
 * <p>Created on {@code SERVER_STARTED} and discarded on {@code SERVER_STOPPED}
 * (SPEC FR-19, AC-19). Nothing here outlives the server it was created for, which is what
 * keeps SPEC INV-7 satisfied.
 *
 * <p>This phase adds tick metrics (FR-1) and the load level state machine (FR-5). The profiler
 * (FR-2) and the scheduler (FR-6) are added in later phases and will be owned by this class too.
 *
 * <p>Deliberately free of {@code net.minecraft} imports: the tick rate manager state arrives as
 * primitives through {@link #onTickRateState(boolean, boolean, float)}, so the whole state object
 * stays unit-testable. {@link TickPilotTickListener} is the Minecraft-facing side.
 *
 * <h2>Threading</h2>
 * Tick measurement is written from the server thread only. The status command reads from the
 * command dispatcher. The kill switch is {@code volatile} because it is the one flag both sides
 * act on; the measurement fields are plain, since a marginally stale status readout is harmless
 * and the tick path must stay barrier-free (SPEC INV-6).
 */
public final class TickPilotServerState {
	private static final long NANOS_PER_MILLI = 1_000_000L;

	private final long startedAtNanos;
	private final TickMetrics metrics = new TickMetrics();
	private final TickBudget budget;

	private volatile boolean disabled;

	private boolean tickRateFrozen;
	private boolean tickRateNormal = true;
	private float tickRate = 20.0f;

	TickPilotServerState(long startedAtNanos) {
		this.startedAtNanos = startedAtNanos;
		this.budget = new TickBudget(startedAtNanos / NANOS_PER_MILLI);
	}

	/**
	 * @param nowNanos current value of {@link System#nanoTime()}
	 * @return nanoseconds elapsed since this state was created
	 */
	public long uptimeNanos(long nowNanos) {
		return nowNanos - startedAtNanos;
	}

	/** @return the tick metrics owned by this server (SPEC FR-1) */
	public TickMetrics metrics() {
		return metrics;
	}

	/** @return the load level state machine owned by this server (SPEC FR-5) */
	public TickBudget budget() {
		return budget;
	}

	/** @return the load level currently held */
	public LoadLevel loadLevel() {
		return budget.level();
	}

	/**
	 * Opens the measurement of one server tick. Hot path.
	 *
	 * @param nowNanos {@link System#nanoTime()} at the start of the tick
	 */
	void onTickStart(long nowNanos) {
		metrics.onTickStart(nowNanos);
	}

	/**
	 * Closes the measurement of one server tick and re-evaluates the load level. Hot path.
	 *
	 * @param nowNanos {@link System#nanoTime()} at the end of the tick
	 * @return the load level transition that just happened, or {@code null} if the level held.
	 *         The caller logs it exactly once (SPEC AC-5).
	 */
	LoadLevelTransition onTickEnd(long nowNanos) {
		if (!metrics.onTickEnd(nowNanos)) {
			return null;
		}

		// The 5 s window is the smoothed input FR-5 asks for: long enough that one slow tick
		// cannot move the level, short enough to react within seconds.
		return budget.update(metrics.averageMspt5s(nowNanos), nowNanos / NANOS_PER_MILLI);
	}

	/**
	 * Records the vanilla tick rate manager state for this tick (SPEC AC-1b). Hot path — stores
	 * primitives only, never a reference to a game object (SPEC INV-1).
	 *
	 * @param frozen        whether the game is frozen by {@code /tick freeze}
	 * @param runsNormally  whether game elements are ticking at all this tick
	 * @param tickRate      the configured target tick rate, 20 unless {@code /tick rate} changed it
	 */
	void onTickRateState(boolean frozen, boolean runsNormally, float tickRate) {
		this.tickRateFrozen = frozen;
		this.tickRateNormal = runsNormally;
		this.tickRate = tickRate;
	}

	/** @return {@code true} when the game is frozen by {@code /tick freeze} (SPEC AC-1b) */
	public boolean isTickRateFrozen() {
		return tickRateFrozen;
	}

	/**
	 * @return {@code true} when the server is ticking normally, i.e. neither frozen nor stepping
	 *         a single frozen tick
	 */
	public boolean isTickRateNormal() {
		return tickRateNormal;
	}

	/** @return the configured target tick rate; 20 unless {@code /tick rate} changed it */
	public float tickRate() {
		return tickRate;
	}

	/**
	 * @return {@code true} when a reduced TPS reading is explained by the vanilla tick rate
	 *         manager rather than by load, and must not be presented as overload (SPEC AC-1b)
	 */
	public boolean isTickRateModified() {
		return tickRateFrozen || !tickRateNormal || tickRate != 20.0f;
	}

	/**
	 * Takes a consistent read-only view of every metric in SPEC AC-1.
	 *
	 * @param nowNanos current value of {@link System#nanoTime()}
	 */
	public TickMetricsSnapshot snapshot(long nowNanos) {
		return metrics.snapshot(nowNanos, uptimeNanos(nowNanos));
	}

	/**
	 * @return {@code true} once a subsystem failure has disabled TickPilot for this server
	 */
	public boolean isDisabled() {
		return disabled;
	}

	/**
	 * Marks TickPilot as disabled for this server after an internal failure. Per SPEC INV-9
	 * TickPilot never propagates its own errors into the server; it steps aside instead.
	 *
	 * @param reason human-readable cause, logged once by the caller
	 */
	public void disable(String reason) {
		this.disabled = true;
		TickPilot.LOGGER.warn("TickPilot disabled for this server: {}", reason);
	}

	/**
	 * Releases everything this state owns. Called on {@code SERVER_STOPPING}.
	 *
	 * <p>No threads, executors or queues exist yet; what there is to release is the measurement
	 * history, which is cleared so that nothing can be observed from a previous world (SPEC
	 * AC-19).
	 */
	void shutdown() {
		this.disabled = true;
		metrics.reset();
	}
}
