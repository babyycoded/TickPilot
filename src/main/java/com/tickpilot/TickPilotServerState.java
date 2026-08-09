package com.tickpilot;

/**
 * Owns all TickPilot state belonging to exactly one running server.
 *
 * <p>Created on {@code SERVER_STARTED} and discarded on {@code SERVER_STOPPED}
 * (SPEC FR-19, AC-19). Nothing here outlives the server it was created for, which is what
 * keeps SPEC INV-7 satisfied.
 *
 * <p>In this phase the state holds only its own start time and a kill switch. Metrics
 * (FR-1), the profiler (FR-2) and the scheduler (FR-6) are added in later phases and will
 * be owned by this class too.
 */
public final class TickPilotServerState {
	private final long startedAtNanos;
	private volatile boolean disabled;

	TickPilotServerState(long startedAtNanos) {
		this.startedAtNanos = startedAtNanos;
	}

	/**
	 * @param nowNanos current value of {@link System#nanoTime()}
	 * @return nanoseconds elapsed since this state was created
	 */
	public long uptimeNanos(long nowNanos) {
		return nowNanos - startedAtNanos;
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
	 * <p>There is nothing to release yet — this phase starts no threads, no executors and no
	 * queues. The method exists so that later phases have exactly one place to clean up, and
	 * so the lifecycle wiring can be verified now rather than retrofitted.
	 */
	void shutdown() {
		this.disabled = true;
	}
}
