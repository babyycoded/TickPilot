package com.tickpilot.metrics;

/**
 * Measures what TickPilot itself costs the server (SPEC INV-10, FR-12).
 *
 * <h2>What is measured, and why that is the right thing</h2>
 * INV-10 caps the mod's overhead <em>in default mode</em>, and in default mode there is no
 * profiling session: every Mixin hook is a static read and a null check, and the only work that
 * actually happens is the tick listener's own body — the ring buffer write, the load level update
 * and the bookkeeping around them. That is exactly what this meter times.
 *
 * <p>Two extra {@link System#nanoTime()} calls per tick pay for it, which against a 50 ms budget
 * is not a number anyone can see. Timing the per-entity hooks instead would need two extra calls
 * per entity, and the measurement would then cost more than the thing being measured.
 *
 * <h2>What is deliberately not measured</h2>
 * The cost of a running profiling session. It is not lost, though: a hook reads its timestamp
 * before doing its bookkeeping, so the bookkeeping lands inside the frame it belongs to and shows
 * up in that category. During a session the categories therefore include the profiler's own cost —
 * which is one honest reason not to leave deep profiling on.
 *
 * <h2>Statistics</h2>
 * A cumulative mean rather than a window: overhead is meant to be flat, so a number that wanders
 * would itself be the finding. {@link #peakNanos()} keeps the worst single tick, because a mean
 * hides a spike and a spike is what would break INV-10.
 *
 * <p>No {@code net.minecraft} import; the clock is supplied by the caller.
 */
public final class OverheadMeter {
	private long totalNanos;
	private long samples;
	private long peakNanos;

	/**
	 * Adds one measurement. Hot path: two field additions and a compare, no allocation (INV-6).
	 *
	 * @param nanos time spent inside TickPilot's own code; negative values are ignored
	 */
	public void record(long nanos) {
		if (nanos < 0L) {
			return;
		}

		totalNanos += nanos;
		samples++;

		if (nanos > peakNanos) {
			peakNanos = nanos;
		}
	}

	/** @return mean nanoseconds of TickPilot's own work per measurement, or 0 with no samples */
	public double meanNanos() {
		return samples == 0L ? 0.0 : (double) totalNanos / samples;
	}

	/** @return the worst single measurement in nanoseconds */
	public long peakNanos() {
		return peakNanos;
	}

	/** @return how many measurements have been folded in */
	public long samples() {
		return samples;
	}

	/**
	 * The number FR-12 asks {@code status} to print.
	 *
	 * <p>Both halves of the tick listener are measured, so the per-tick cost is the mean times the
	 * number of measurements per tick.
	 *
	 * @param measurementsPerTick how many {@link #record} calls happen in one tick
	 * @return TickPilot's own cost in milliseconds per tick
	 */
	public double msptPerTick(int measurementsPerTick) {
		return meanNanos() * measurementsPerTick / 1_000_000.0;
	}

	/**
	 * @param measurementsPerTick how many {@link #record} calls happen in one tick
	 * @param tickMspt            the server's average MSPT
	 * @return TickPilot's cost as a percentage of the tick, or 0 when the server has no measured
	 *         tick time to compare against
	 */
	public double percentOf(int measurementsPerTick, double tickMspt) {
		if (!(tickMspt > 0.0)) {
			return 0.0;
		}

		return msptPerTick(measurementsPerTick) / tickMspt * 100.0;
	}

	/** Clears every counter. Used when the server stops (SPEC AC-19). */
	public void reset() {
		totalNanos = 0L;
		samples = 0L;
		peakNanos = 0L;
	}
}
