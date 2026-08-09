package com.tickpilot.metrics;

import java.util.Arrays;

/**
 * Owns the ring buffer of server tick durations and derives every headline number from it
 * (SPEC FR-1, AC-1).
 *
 * <h2>Why this class has no {@code net.minecraft} import</h2>
 * Everything here is arithmetic over {@code long} nanosecond values, so it can be unit-tested
 * without launching Minecraft (SPEC §8). The Minecraft-facing side lives in
 * {@code com.tickpilot.TickPilotTickListener}, which feeds timestamps in.
 *
 * <h2>Allocation contract (SPEC INV-6)</h2>
 * The hot path is {@link #onTickStart(long)} / {@link #onTickEnd(long)}: two array writes and a
 * handful of counter updates, no allocation and no scanning. Everything expensive — window
 * averages, percentiles, max — is computed on demand by the query methods, which run from the
 * command thread a few times per session, not from the tick loop. {@link #snapshot(long)} is the
 * only method that allocates, and only per call.
 *
 * <h2>Threading</h2>
 * Writes come from the server thread only. Reads may come from the command dispatcher, which
 * on a dedicated server is also the server thread. Fields are plain (non-volatile): a reader on
 * another thread may observe a slightly stale sample count, which is acceptable for a status
 * readout and keeps the hot path free of memory barriers. Nothing here touches game state, so
 * SPEC INV-1 is not in play.
 */
public final class TickMetrics {
	/** Vanilla target tick rate. TPS is capped at this value (SPEC §2). */
	public static final double TARGET_TPS = 20.0;

	/** 5 s window, in nanoseconds. */
	public static final long WINDOW_5S_NANOS = 5_000_000_000L;

	/** 1 min window, in nanoseconds. */
	public static final long WINDOW_1M_NANOS = 60_000_000_000L;

	/** 5 min window, in nanoseconds. */
	public static final long WINDOW_5M_NANOS = 300_000_000_000L;

	/** Enough samples for the longest window (5 min at 20 TPS). */
	public static final int DEFAULT_CAPACITY = 6_000;

	private static final double NANOS_PER_MILLI = 1_000_000.0;
	private static final double NANOS_PER_SECOND = 1_000_000_000.0;

	/** Tick durations in nanoseconds, oldest to newest modulo {@link #writeIndex}. */
	private final long[] durationNanos;

	/** Wall-clock end timestamp of the sample at the same index, in {@code System.nanoTime()}. */
	private final long[] endNanos;

	private final int capacity;

	private int writeIndex;
	private int sampleCount;

	private long totalTicks;
	private long lastDurationNanos;
	private long firstSampleEndNanos;

	private long openTickStartNanos;
	private boolean tickOpen;

	/** Creates metrics with {@link #DEFAULT_CAPACITY} samples of history. */
	public TickMetrics() {
		this(DEFAULT_CAPACITY);
	}

	/**
	 * @param capacity number of tick samples to retain; must be positive
	 */
	public TickMetrics(int capacity) {
		if (capacity <= 0) {
			throw new IllegalArgumentException("capacity must be positive, got " + capacity);
		}

		this.capacity = capacity;
		this.durationNanos = new long[capacity];
		this.endNanos = new long[capacity];
	}

	/**
	 * Opens a tick measurement. Hot path — called once per server tick.
	 *
	 * <p>Calling this twice without an intervening {@link #onTickEnd(long)} simply restarts the
	 * measurement; an unfinished tick is never recorded.
	 *
	 * @param nowNanos {@link System#nanoTime()} at the start of the tick
	 */
	public void onTickStart(long nowNanos) {
		this.openTickStartNanos = nowNanos;
		this.tickOpen = true;
	}

	/**
	 * Closes the tick measurement opened by {@link #onTickStart(long)} and stores the sample.
	 * Hot path — called once per server tick.
	 *
	 * <p>An end without a matching start is ignored rather than recorded as a bogus duration;
	 * this happens exactly once if the mod is loaded mid-tick.
	 *
	 * @param nowNanos {@link System#nanoTime()} at the end of the tick
	 * @return {@code true} if a sample was recorded
	 */
	public boolean onTickEnd(long nowNanos) {
		if (!tickOpen) {
			return false;
		}

		tickOpen = false;

		long duration = nowNanos - openTickStartNanos;

		if (duration < 0L) {
			// nanoTime() is monotonic per the JLS, so this is unreachable in practice. Dropping
			// the sample rather than storing a negative duration keeps percentiles sane if it
			// ever is reachable on some exotic platform.
			return false;
		}

		durationNanos[writeIndex] = duration;
		endNanos[writeIndex] = nowNanos;
		writeIndex = writeIndex + 1 == capacity ? 0 : writeIndex + 1;

		if (sampleCount == 0) {
			firstSampleEndNanos = nowNanos;
		}

		if (sampleCount < capacity) {
			sampleCount++;
		}

		lastDurationNanos = duration;
		totalTicks++;
		return true;
	}

	/** @return how many samples the ring buffer currently holds */
	public int sampleCount() {
		return sampleCount;
	}

	/** @return how many ticks have been measured since this instance was created */
	public long totalTicks() {
		return totalTicks;
	}

	/** @return duration of the most recently completed tick in milliseconds, or 0 if none */
	public double lastMspt() {
		return toMillis(lastDurationNanos);
	}

	/**
	 * Mean tick duration over the given window.
	 *
	 * @param windowNanos length of the window ending at {@code nowNanos}
	 * @param nowNanos    current {@link System#nanoTime()}
	 * @return mean MSPT, or 0 if the window holds no samples
	 */
	public double averageMspt(long windowNanos, long nowNanos) {
		long cutoff = nowNanos - windowNanos;
		long sum = 0L;
		int n = 0;

		// Walk backwards from the newest sample and stop at the first one outside the window.
		// Samples are stored in ascending time order, so nothing older can qualify.
		for (int i = 0; i < sampleCount; i++) {
			int index = indexFromNewest(i);

			if (endNanos[index] <= cutoff) {
				break;
			}

			sum += durationNanos[index];
			n++;
		}

		return n == 0 ? 0.0 : toMillis(sum) / n;
	}

	/** @return mean MSPT over the last 5 s (SPEC AC-1) */
	public double averageMspt5s(long nowNanos) {
		return averageMspt(WINDOW_5S_NANOS, nowNanos);
	}

	/** @return mean MSPT over the last minute (SPEC AC-1) */
	public double averageMspt1m(long nowNanos) {
		return averageMspt(WINDOW_1M_NANOS, nowNanos);
	}

	/** @return mean MSPT over the last 5 min (SPEC AC-1) */
	public double averageMspt5m(long nowNanos) {
		return averageMspt(WINDOW_5M_NANOS, nowNanos);
	}

	/**
	 * Current ticks per second, measured over the last 5 s of wall-clock time.
	 *
	 * <p>Derived from how many ticks actually completed in the window rather than from MSPT, so
	 * a server that is idle-waiting between short ticks reads 20 rather than 200, and a server
	 * that stalled for the whole window reads 0. The result is capped at {@link #TARGET_TPS}
	 * because vanilla never ticks faster than its target rate.
	 *
	 * <p>A reduced value is <em>not</em> by itself evidence of overload: {@code /tick rate} lowers
	 * the target rate on purpose, which is why the status output reports the tick rate manager
	 * state alongside this number (SPEC AC-1b).
	 *
	 * @param nowNanos current {@link System#nanoTime()}
	 * @return ticks per second in {@code [0, 20]}, or 0 while nothing has been measured
	 */
	public double tps(long nowNanos) {
		if (sampleCount == 0) {
			return 0.0;
		}

		long cutoff = nowNanos - WINDOW_5S_NANOS;
		int ticksInWindow = 0;

		for (int i = 0; i < sampleCount; i++) {
			if (endNanos[indexFromNewest(i)] <= cutoff) {
				break;
			}

			ticksInWindow++;
		}

		if (ticksInWindow == 0) {
			return 0.0;
		}

		// Before the first full window has elapsed, divide by the real uptime instead of the
		// nominal window, otherwise a freshly started server reads a fraction of its true rate.
		long elapsed = Math.min(WINDOW_5S_NANOS, nowNanos - firstSampleEndNanos);

		if (elapsed <= 0L) {
			return TARGET_TPS;
		}

		return Math.min(TARGET_TPS, ticksInWindow * NANOS_PER_SECOND / elapsed);
	}

	/** @return longest tick in the retained history, in milliseconds, or 0 if there are none */
	public double maxMspt() {
		long max = 0L;

		for (int i = 0; i < sampleCount; i++) {
			max = Math.max(max, durationNanos[i]);
		}

		return toMillis(max);
	}

	/**
	 * Nearest-rank percentile over the whole retained history.
	 *
	 * <p>Nearest rank (rather than an interpolating definition) is used because it always returns
	 * a duration that a real tick actually had, which is what an operator reading
	 * {@code /tickpilot status} expects.
	 *
	 * @param percentile in {@code (0, 100]}
	 * @return the percentile in milliseconds, or 0 when no samples are held
	 */
	public double percentileMspt(double percentile) {
		if (percentile <= 0.0 || percentile > 100.0) {
			throw new IllegalArgumentException("percentile must be in (0, 100], got " + percentile);
		}

		if (sampleCount == 0) {
			return 0.0;
		}

		// Allocates: query path only, never the tick loop (SPEC INV-6).
		long[] sorted = Arrays.copyOf(durationNanos, sampleCount);
		Arrays.sort(sorted);

		int rank = (int) Math.ceil(percentile / 100.0 * sampleCount);
		int index = Math.min(sampleCount - 1, Math.max(0, rank - 1));
		return toMillis(sorted[index]);
	}

	/** @return 95th percentile MSPT over the retained history (SPEC AC-1) */
	public double p95Mspt() {
		return percentileMspt(95.0);
	}

	/** @return 99th percentile MSPT over the retained history (SPEC AC-1) */
	public double p99Mspt() {
		return percentileMspt(99.0);
	}

	/**
	 * Builds an immutable read-only view of every value in SPEC AC-1 in one pass of query calls,
	 * so a caller cannot print numbers taken from two different moments.
	 *
	 * @param nowNanos    current {@link System#nanoTime()}
	 * @param uptimeNanos how long the owning server has been up
	 */
	public TickMetricsSnapshot snapshot(long nowNanos, long uptimeNanos) {
		return new TickMetricsSnapshot(
				tps(nowNanos),
				lastMspt(),
				averageMspt5s(nowNanos),
				averageMspt1m(nowNanos),
				averageMspt5m(nowNanos),
				p95Mspt(),
				p99Mspt(),
				maxMspt(),
				totalTicks,
				sampleCount,
				uptimeNanos);
	}

	/** Drops all history. Used when a server stops, so nothing leaks into the next world (INV-7). */
	public void reset() {
		Arrays.fill(durationNanos, 0L);
		Arrays.fill(endNanos, 0L);
		writeIndex = 0;
		sampleCount = 0;
		totalTicks = 0L;
		lastDurationNanos = 0L;
		firstSampleEndNanos = 0L;
		openTickStartNanos = 0L;
		tickOpen = false;
	}

	/**
	 * @param offset 0 for the newest sample, 1 for the one before it, ...
	 * @return the backing array index of that sample
	 */
	private int indexFromNewest(int offset) {
		int index = writeIndex - 1 - offset;
		return index < 0 ? index + capacity : index;
	}

	private static double toMillis(long nanos) {
		return nanos / NANOS_PER_MILLI;
	}
}
