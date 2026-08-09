package com.tickpilot.metrics;

/**
 * Immutable read-only view of {@link TickMetrics} taken at one instant (SPEC AC-1).
 *
 * <p>Created only by query paths such as {@code /tickpilot status}; never per tick, so it does
 * not violate SPEC INV-6. All MSPT values are milliseconds.
 *
 * <p>Percentiles come in two windows on purpose. The 1 min pair answers "how is the server
 * behaving now" and recovers from an outlier within a minute; the history pair answers "how bad
 * has it been lately" and keeps the outlier until it is evicted from the ring buffer. Showing
 * only the second is what makes a server that recovered a minute ago still look broken.
 *
 * @param tps                ticks per second over the last 5 s, capped at 20
 * @param lastMspt           duration of the most recently completed tick
 * @param avgMspt5s          mean MSPT over the last 5 s
 * @param avgMspt1m          mean MSPT over the last minute
 * @param avgMspt5m          mean MSPT over the last 5 min
 * @param p95Mspt1m          95th percentile MSPT over the last minute
 * @param p99Mspt1m          99th percentile MSPT over the last minute
 * @param p95MsptHistory     95th percentile MSPT over the whole retained history
 * @param p99MsptHistory     99th percentile MSPT over the whole retained history
 * @param maxMspt            longest tick in the retained history
 * @param maxAgeNanos        how long ago that longest tick finished
 * @param retainedSpanNanos  wall-clock time the retained history actually covers; label output
 *                           with this rather than assuming the nominal buffer length
 * @param totalTicks         ticks measured since the server started
 * @param sampleCount        samples currently held in the ring buffer
 * @param uptimeNanos        how long the owning server has been up
 */
public record TickMetricsSnapshot(
		double tps,
		double lastMspt,
		double avgMspt5s,
		double avgMspt1m,
		double avgMspt5m,
		double p95Mspt1m,
		double p99Mspt1m,
		double p95MsptHistory,
		double p99MsptHistory,
		double maxMspt,
		long maxAgeNanos,
		long retainedSpanNanos,
		long totalTicks,
		int sampleCount,
		long uptimeNanos) {

	/** @return {@code true} while no tick has been measured yet */
	public boolean isEmpty() {
		return sampleCount == 0;
	}

	/**
	 * How much history the 1 min percentiles actually cover, which is less than a minute on a
	 * server that has not been up that long. Callers label their output with this so a 40 s old
	 * server says "last 40s" instead of claiming a minute.
	 *
	 * @return the shorter of one minute and {@link #retainedSpanNanos()}
	 */
	public long shortPercentileSpanNanos() {
		return Math.min(TickMetrics.WINDOW_1M_NANOS, retainedSpanNanos);
	}

	/**
	 * Whether the server has been up long enough for a nominal window to mean what it says.
	 *
	 * <p>The averages carry the fixed names SPEC AC-1 gives them (5 s / 1 min / 5 min), so unlike
	 * the percentile lines they cannot be relabelled with the span they really cover. A server up
	 * for 80 s would otherwise print "avg 5m 1.07" for an average over 80 seconds. Callers show
	 * {@code n/a} instead when this returns {@code false} — the same choice SPEC AC-2 makes for a
	 * profiling category that has no data, and for the same reason: a number that quietly means
	 * something other than its label is worse than an admission that it is missing.
	 *
	 * @param windowNanos nominal window length
	 * @return {@code true} once the measured uptime covers the whole window
	 */
	public boolean covers(long windowNanos) {
		return uptimeNanos >= windowNanos;
	}
}
