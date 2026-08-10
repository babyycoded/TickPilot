package com.tickpilot.api;

/**
 * An immutable read-only view of what TickPilot has measured, taken at one instant
 * (SPEC FR-14, AC-1).
 *
 * <p>Obtained from {@link TickPilotApi#metrics()}. It is a copy: holding on to one is harmless,
 * and the numbers in it never change afterwards. Nothing in it can be used to influence the
 * server — the public API is read-only about measurements by design.
 *
 * <p>This record duplicates a handful of fields from TickPilot's internal snapshot rather than
 * exposing it, because SPEC AC-14 requires that a consumer never has to import an internal class.
 * All MSPT values are milliseconds.
 *
 * <h2>Reading the windows honestly</h2>
 * {@link #avgMspt1m()} and {@link #avgMspt5m()} cover their nominal window only once the server
 * has been up that long; before that they cover the uptime and nothing warns you in the number
 * itself. Use {@link #covers(long)} with the constants below before presenting them, exactly as
 * {@code /tickpilot status} does when it prints {@code n/a}.
 *
 * @param tps            ticks per second over the last 5 s, capped at 20
 * @param lastMspt       duration of the most recently completed tick
 * @param avgMspt5s      mean MSPT over the last 5 s
 * @param avgMspt1m      mean MSPT over the last minute
 * @param avgMspt5m      mean MSPT over the last 5 min
 * @param p95Mspt        95th percentile MSPT over the last minute
 * @param p99Mspt        99th percentile MSPT over the last minute
 * @param maxMspt        longest tick in the retained history
 * @param maxAgeNanos    how long ago that longest tick finished
 * @param totalTicks     ticks measured since the server started
 * @param uptimeNanos    how long the server has been up, as measured by TickPilot
 * @param load           the load level currently held (SPEC FR-5)
 * @param deferredTasks  how many tasks are waiting in the adaptive scheduler right now
 *                       (SPEC FR-6). Zero on a server where nothing has ever been submitted
 * @param maxDeferredTasks the configured queue cap, {@code max_deferred_tasks} (SPEC AC-6)
 */
public record TickPilotMetrics(
		double tps,
		double lastMspt,
		double avgMspt5s,
		double avgMspt1m,
		double avgMspt5m,
		double p95Mspt,
		double p99Mspt,
		double maxMspt,
		long maxAgeNanos,
		long totalTicks,
		long uptimeNanos,
		ServerLoad load,
		int deferredTasks,
		int maxDeferredTasks) {

	/** Length of the 5 s window, in nanoseconds, for use with {@link #covers(long)}. */
	public static final long WINDOW_5S_NANOS = 5L * 1_000_000_000L;

	/** Length of the 1 min window, in nanoseconds, for use with {@link #covers(long)}. */
	public static final long WINDOW_1M_NANOS = 60L * 1_000_000_000L;

	/** Length of the 5 min window, in nanoseconds, for use with {@link #covers(long)}. */
	public static final long WINDOW_5M_NANOS = 5L * 60L * 1_000_000_000L;

	/**
	 * @param windowNanos one of the {@code WINDOW_*} constants
	 * @return {@code true} once the server has been up long enough for the matching average to
	 *         cover the window its name claims
	 */
	public boolean covers(long windowNanos) {
		return uptimeNanos >= windowNanos;
	}

	/**
	 * @return {@code true} while no tick has been measured yet, in which case every number here
	 *         is zero because nothing has happened, not because the server is idle
	 */
	public boolean isEmpty() {
		return totalTicks == 0L;
	}
}
