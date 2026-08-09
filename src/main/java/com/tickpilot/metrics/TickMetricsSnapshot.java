package com.tickpilot.metrics;

/**
 * Immutable read-only view of {@link TickMetrics} taken at one instant (SPEC AC-1).
 *
 * <p>Created only by query paths such as {@code /tickpilot status}; never per tick, so it does
 * not violate SPEC INV-6. All MSPT values are milliseconds.
 *
 * @param tps         ticks per second over the last 5 s, capped at 20
 * @param lastMspt    duration of the most recently completed tick
 * @param avgMspt5s   mean MSPT over the last 5 s
 * @param avgMspt1m   mean MSPT over the last minute
 * @param avgMspt5m   mean MSPT over the last 5 min
 * @param p95Mspt     95th percentile MSPT over the retained history
 * @param p99Mspt     99th percentile MSPT over the retained history
 * @param maxMspt     longest tick in the retained history
 * @param totalTicks  ticks measured since the server started
 * @param sampleCount samples currently held in the ring buffer
 * @param uptimeNanos how long the owning server has been up
 */
public record TickMetricsSnapshot(
		double tps,
		double lastMspt,
		double avgMspt5s,
		double avgMspt1m,
		double avgMspt5m,
		double p95Mspt,
		double p99Mspt,
		double maxMspt,
		long totalTicks,
		int sampleCount,
		long uptimeNanos) {

	/** @return {@code true} while no tick has been measured yet */
	public boolean isEmpty() {
		return sampleCount == 0;
	}
}
