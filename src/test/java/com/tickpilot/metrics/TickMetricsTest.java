package com.tickpilot.metrics;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * Covers the tick history required by SPEC FR-1 / AC-1: averages, TPS, percentiles and the
 * boundary cases SPEC §8 asks for (empty buffer, one sample, all samples equal).
 *
 * <p>No Minecraft runtime is involved: every timestamp is fed in by the test, which also makes
 * the results deterministic rather than dependent on how fast the machine runs.
 */
class TickMetricsTest {
	private static final double EPSILON = 1.0e-9;

	private static long ms(double millis) {
		return (long) (millis * 1_000_000.0);
	}

	/**
	 * Records {@code count} ticks, each {@code durationMillis} long, starting one tick period
	 * apart, and returns the timestamp just after the last tick ends.
	 */
	private static long recordSteady(TickMetrics metrics, int count, double periodMillis, double durationMillis) {
		long now = 0L;

		for (int i = 0; i < count; i++) {
			long start = ms(periodMillis) * i;
			metrics.onTickStart(start);
			metrics.onTickEnd(start + ms(durationMillis));
			now = start + ms(periodMillis);
		}

		return now;
	}

	@Test
	void emptyBufferReportsZeroForEverything() {
		TickMetrics metrics = new TickMetrics();
		long now = ms(1000);

		assertEquals(0, metrics.sampleCount());
		assertEquals(0L, metrics.totalTicks());
		assertEquals(0.0, metrics.tps(now), EPSILON);
		assertEquals(0.0, metrics.lastMspt(), EPSILON);
		assertEquals(0.0, metrics.averageMspt5s(now), EPSILON);
		assertEquals(0.0, metrics.p95Mspt(), EPSILON);
		assertEquals(0.0, metrics.p99Mspt(), EPSILON);
		assertEquals(0.0, metrics.maxMspt(), EPSILON);
		assertTrue(metrics.snapshot(now, 0L).isEmpty());
	}

	@Test
	void averageAndMaxComeFromTheRecordedDurations() {
		TickMetrics metrics = new TickMetrics();

		// Three ticks of 10, 20 and 30 ms, one every 50 ms.
		long[] durations = {10, 20, 30};

		for (int i = 0; i < durations.length; i++) {
			long start = ms(50) * i;
			metrics.onTickStart(start);
			metrics.onTickEnd(start + ms(durations[i]));
		}

		long now = ms(150);

		assertEquals(3, metrics.sampleCount());
		assertEquals(3L, metrics.totalTicks());
		assertEquals(20.0, metrics.averageMspt5s(now), EPSILON);
		assertEquals(20.0, metrics.averageMspt1m(now), EPSILON);
		assertEquals(30.0, metrics.lastMspt(), EPSILON);
		assertEquals(30.0, metrics.maxMspt(), EPSILON);
	}

	@Test
	void averageOnlyCountsSamplesInsideTheWindow() {
		TickMetrics metrics = new TickMetrics();

		// One expensive tick 10 s ago, then ten cheap ticks in the last second.
		metrics.onTickStart(0L);
		metrics.onTickEnd(ms(500));

		for (int i = 0; i < 10; i++) {
			long start = ms(10_000) + ms(50) * i;
			metrics.onTickStart(start);
			metrics.onTickEnd(start + ms(5));
		}

		long now = ms(10_500);

		assertEquals(5.0, metrics.averageMspt5s(now), EPSILON, "the 500 ms tick is outside the 5 s window");
		assertEquals(50.0, metrics.averageMspt1m(now), EPSILON, "11 ticks: (500 + 10*5) / 11");
		assertEquals(500.0, metrics.maxMspt(), EPSILON, "max spans the whole retained history");
	}

	@Test
	void tpsIsTwentyWhenTicksArriveEveryFiftyMillis() {
		TickMetrics metrics = new TickMetrics();
		long now = recordSteady(metrics, 100, 50.0, 1.0);

		assertEquals(20.0, metrics.tps(now), 1.0e-6);
	}

	@Test
	void tpsHalvesWhenTicksArriveTwiceAsSlowly() {
		TickMetrics metrics = new TickMetrics();
		// 200 ticks, one every 100 ms: the 5 s window holds 50 of them.
		long now = recordSteady(metrics, 200, 100.0, 1.0);

		assertEquals(10.0, metrics.tps(now), 1.0e-6);
	}

	@Test
	void tpsIsExactBeforeTheFirstWindowHasElapsed() {
		TickMetrics metrics = new TickMetrics();
		// Only 400 ms of history: dividing by the nominal 5 s window would report 1.6 TPS.
		long now = recordSteady(metrics, 8, 50.0, 1.0);

		assertEquals(20.0, metrics.tps(now), 1.0e-6);
	}

	@Test
	void tpsIsTwentyWhenQueriedFromInsideTheRunningTick() {
		// Regression, found in manual testing: /tickpilot status runs inside tickChildren, so the
		// current tick has not recorded its end yet. Counting ends inside a fixed 5 s window then
		// finds 99 of them instead of 100 and reports a permanent 19.80 on a healthy server.
		TickMetrics metrics = new TickMetrics();

		for (int i = 0; i < 400; i++) {
			long start = ms(50) * i;
			metrics.onTickStart(start);
			metrics.onTickEnd(start + ms(0.3));
		}

		// Tick 400 is in flight; the command executes 0.3 ms into it.
		long tickStart = ms(50) * 400;
		metrics.onTickStart(tickStart);

		assertEquals(20.0, metrics.tps(tickStart + ms(0.3)), 1.0e-6);
	}

	@Test
	void tpsDoesNotDependOnWhereInTheTickItIsQueried() {
		TickMetrics metrics = new TickMetrics();
		long lastEnd = 0L;

		for (int i = 0; i < 400; i++) {
			long start = ms(50) * i;
			metrics.onTickStart(start);
			lastEnd = start + ms(0.3);
			metrics.onTickEnd(lastEnd);
		}

		// The old formula quantised the answer to multiples of 0.2 TPS and dropped a step
		// depending on where the window boundary fell. Sweep a whole tick period: every phase
		// must give the same answer.
		for (double offsetMillis = 0.0; offsetMillis < 50.0; offsetMillis += 0.5) {
			assertEquals(20.0, metrics.tps(lastEnd + ms(offsetMillis)), 1.0e-6,
					"queried " + offsetMillis + " ms after the last tick");
		}
	}

	@Test
	void tpsIsUnaffectedByHowLongTheTicksThemselvesTake() {
		// An idle server and a busy one that both keep the schedule run at the same rate: TPS
		// saturates at 20 while MSPT still has 49 ms of headroom. This is why SPEC §2 makes MSPT
		// the primary metric, and the test pins the property rather than assuming it.
		TickMetrics idle = new TickMetrics();
		TickMetrics busy = new TickMetrics();

		long idleNow = recordSteady(idle, 200, 50.0, 0.1);
		long busyNow = recordSteady(busy, 200, 50.0, 40.0);

		assertEquals(20.0, idle.tps(idleNow), 1.0e-6);
		assertEquals(20.0, busy.tps(busyNow), 1.0e-6);
		assertEquals(0.1, idle.averageMspt5s(idleNow), EPSILON);
		assertEquals(40.0, busy.averageMspt5s(busyNow), EPSILON);
	}

	@Test
	void tpsDegradesWhileTheNextTickIsOverdue() {
		TickMetrics metrics = new TickMetrics();
		long lastEnd = 0L;

		for (int i = 0; i < 100; i++) {
			long start = ms(50) * i;
			metrics.onTickStart(start);
			lastEnd = start + ms(1);
			metrics.onTickEnd(lastEnd);
		}

		// Inside the grace period the server is simply mid-tick, not late.
		assertEquals(20.0, metrics.tps(lastEnd + ms(99)), 1.0e-6);

		// After a 3 s stall the 5 s window still holds 40 samples: 39 periods spanning 1950 ms,
		// plus 2900 ms of wait beyond the two-period grace.
		double stalled = metrics.tps(lastEnd + ms(3000));
		assertEquals(39.0 / 4.85, stalled, 1.0e-6);
		assertTrue(stalled > 7.0 && stalled < 9.0, "a 3 s stall must be clearly visible");
	}

	@Test
	void tpsNeedsTwoSamplesBeforeItMeansAnything() {
		TickMetrics metrics = new TickMetrics();
		metrics.onTickStart(0L);
		metrics.onTickEnd(ms(1));

		assertEquals(0.0, metrics.tps(ms(2)), EPSILON, "one tick describes no interval");

		metrics.onTickStart(ms(50));
		metrics.onTickEnd(ms(51));

		assertEquals(20.0, metrics.tps(ms(52)), 1.0e-6);
	}

	@Test
	void tpsIsZeroWhileTheServerIsStalled() {
		TickMetrics metrics = new TickMetrics();
		recordSteady(metrics, 100, 50.0, 1.0);

		// No tick completed in the last 5 s.
		assertEquals(0.0, metrics.tps(ms(60_000)), EPSILON);
	}

	@Test
	void tpsNeverExceedsTwenty() {
		TickMetrics metrics = new TickMetrics();
		// One tick every millisecond is impossible in vanilla, but the cap must hold anyway.
		long now = recordSteady(metrics, 1000, 1.0, 0.5);

		assertEquals(20.0, metrics.tps(now), EPSILON);
	}

	@Test
	void percentilesOfAKnownDistribution() {
		TickMetrics metrics = new TickMetrics();

		// Durations 1..100 ms, so the nearest-rank p95 is 95 ms and p99 is 99 ms.
		for (int i = 1; i <= 100; i++) {
			long start = ms(50) * i;
			metrics.onTickStart(start);
			metrics.onTickEnd(start + ms(i));
		}

		assertEquals(95.0, metrics.p95Mspt(), EPSILON);
		assertEquals(99.0, metrics.p99Mspt(), EPSILON);
		assertEquals(100.0, metrics.maxMspt(), EPSILON);
		assertEquals(100.0, metrics.percentileMspt(100.0), EPSILON);
		assertEquals(1.0, metrics.percentileMspt(0.5), EPSILON, "smallest rank is the fastest tick");
	}

	@Test
	void percentilesOfASingleSampleAreThatSample() {
		TickMetrics metrics = new TickMetrics();
		metrics.onTickStart(0L);
		metrics.onTickEnd(ms(42));

		assertEquals(42.0, metrics.p95Mspt(), EPSILON);
		assertEquals(42.0, metrics.p99Mspt(), EPSILON);
		assertEquals(42.0, metrics.maxMspt(), EPSILON);
		assertEquals(42.0, metrics.lastMspt(), EPSILON);
	}

	@Test
	void percentilesOfIdenticalSamplesAreThatValue() {
		TickMetrics metrics = new TickMetrics();
		recordSteady(metrics, 500, 50.0, 12.5);

		assertEquals(12.5, metrics.p95Mspt(), EPSILON);
		assertEquals(12.5, metrics.p99Mspt(), EPSILON);
		assertEquals(12.5, metrics.maxMspt(), EPSILON);
		assertEquals(12.5, metrics.averageMspt5m(ms(25_000)), EPSILON);
	}

	@Test
	void windowedPercentilesIgnoreSamplesOutsideTheWindow() {
		TickMetrics metrics = new TickMetrics();

		// Two minutes of ticks: the first minute slow, the second fast.
		for (int i = 0; i < 2400; i++) {
			long start = ms(50) * i;
			metrics.onTickStart(start);
			metrics.onTickEnd(start + ms(i < 1200 ? 30 : 2));
		}

		long now = ms(50) * 2400;

		assertEquals(2.0, metrics.p95Mspt1m(now), EPSILON, "the slow minute is out of the window");
		assertEquals(2.0, metrics.p99Mspt1m(now), EPSILON);
		assertEquals(30.0, metrics.p95Mspt(), EPSILON, "but it is still in the history");
		assertEquals(30.0, metrics.p99Mspt(), EPSILON);
	}

	@Test
	void startupSpikeStopsSkewingPercentilesOnceItLeavesTheWindow() {
		// Regression, found by cross-checking against vanilla /tick query: a server that has just
		// generated its spawn chunks carries ~200 ms ticks in the buffer, and whole-history
		// percentiles kept reporting them as if the server were still struggling. Vanilla read
		// p95 0.3 ms over its 100-tick window while TickPilot read 3.47 ms over five minutes.
		TickMetrics metrics = new TickMetrics();

		// 40 slow startup ticks, the worst of them 208 ms.
		for (int i = 0; i < 40; i++) {
			long start = ms(50) * i;
			metrics.onTickStart(start);
			metrics.onTickEnd(start + ms(i == 12 ? 208 : 117));
		}

		// Then 90 s of a healthy, idle server.
		for (int i = 40; i < 40 + 1800; i++) {
			long start = ms(50) * i;
			metrics.onTickStart(start);
			metrics.onTickEnd(start + ms(0.2));
		}

		long now = ms(50) * (40 + 1800);

		assertEquals(0.2, metrics.p95Mspt1m(now), EPSILON, "the last minute is quiet and must say so");
		assertEquals(0.2, metrics.p99Mspt1m(now), EPSILON);

		// The outlier is still in the history, which is what makes it explainable rather than lost.
		assertEquals(208.0, metrics.maxMspt(), EPSILON);
		assertEquals(ms(91_192), metrics.maxSampleAgeNanos(now),
				"the max must come with a date, not float free");

		// 40 slow ticks out of 1840 are 2.2 % of the history, so even the history p95 has washed
		// them out; only p99 still sees the burst. Both are true statements about a different
		// question than the 1 min pair answers.
		assertEquals(0.2, metrics.p95Mspt(), EPSILON);
		assertEquals(117.0, metrics.p99Mspt(), EPSILON);
	}

	@Test
	void retainedSpanReportsWhatTheBufferActuallyCovers() {
		TickMetrics metrics = new TickMetrics();

		assertEquals(0L, metrics.retainedSpanNanos(ms(1000)), "an empty buffer covers nothing");

		// 40 s of ticks: the span must say 40 s, not the nominal 5 min of the buffer.
		long now = recordSteady(metrics, 800, 50.0, 1.0);

		assertEquals(ms(50) * 800 - ms(1), metrics.retainedSpanNanos(now));
		assertTrue(metrics.retainedSpanNanos(now) < TickMetrics.WINDOW_1M_NANOS,
				"40 s of history must not be presented as a full minute");
	}

	@Test
	void retainedSpanStopsGrowingOnceTheBufferIsFull() {
		TickMetrics metrics = new TickMetrics(100);
		long now = recordSteady(metrics, 500, 50.0, 1.0);

		// 100 samples 50 ms apart cover 99 periods plus the trailing gap to now.
		assertEquals(ms(50) * 100 - ms(1), metrics.retainedSpanNanos(now));
	}

	@Test
	void windowedPercentileHandlesTheSameEdgeCasesAsTheHistoryOne() {
		TickMetrics metrics = new TickMetrics();

		assertEquals(0.0, metrics.p95Mspt1m(ms(1000)), EPSILON, "empty buffer");

		metrics.onTickStart(0L);
		metrics.onTickEnd(ms(7));

		assertEquals(7.0, metrics.p95Mspt1m(ms(10)), EPSILON, "one sample");
		assertEquals(7.0, metrics.p99Mspt1m(ms(10)), EPSILON);
		assertEquals(0.0, metrics.p95Mspt1m(ms(120_000)), EPSILON, "sample older than the window");

		TickMetrics identical = new TickMetrics();
		long now = recordSteady(identical, 600, 50.0, 3.5);

		assertEquals(3.5, identical.p95Mspt1m(now), EPSILON, "identical samples");
		assertEquals(3.5, identical.p99Mspt1m(now), EPSILON);
	}

	@Test
	void percentileRejectsValuesOutsideItsRange() {
		TickMetrics metrics = new TickMetrics();
		metrics.onTickStart(0L);
		metrics.onTickEnd(ms(1));

		assertThrows(IllegalArgumentException.class, () -> metrics.percentileMspt(0.0));
		assertThrows(IllegalArgumentException.class, () -> metrics.percentileMspt(100.1));
	}

	@Test
	void ringBufferKeepsOnlyTheMostRecentSamples() {
		TickMetrics metrics = new TickMetrics(3);

		for (int i = 1; i <= 5; i++) {
			long start = ms(50) * i;
			metrics.onTickStart(start);
			metrics.onTickEnd(start + ms(i));
		}

		assertEquals(3, metrics.sampleCount(), "capacity caps the history");
		assertEquals(5L, metrics.totalTicks(), "but every tick is still counted");
		assertEquals(5.0, metrics.maxMspt(), EPSILON, "the 1 ms and 2 ms ticks were evicted");
		assertEquals(4.0, metrics.averageMspt5m(ms(300)), EPSILON, "(3 + 4 + 5) / 3");
	}

	@Test
	void windowScanStaysCorrectAfterTheBufferWraps() {
		TickMetrics metrics = new TickMetrics(4);

		// Six ticks 50 ms apart, so the write cursor wraps. Only the last four are retained,
		// and the newest-first scan must still walk them in the right order.
		for (int i = 0; i < 6; i++) {
			long start = ms(50) * i;
			metrics.onTickStart(start);
			metrics.onTickEnd(start + ms(i + 1));
		}

		long now = ms(300);
		assertEquals(4.5, metrics.averageMspt5s(now), EPSILON, "(3 + 4 + 5 + 6) / 4");
		assertEquals(6.0, metrics.lastMspt(), EPSILON);
		assertEquals(6.0, metrics.maxMspt(), EPSILON);
	}

	@Test
	void unpairedEndIsIgnored() {
		TickMetrics metrics = new TickMetrics();

		assertFalse(metrics.onTickEnd(ms(10)), "an end without a start records nothing");
		assertEquals(0, metrics.sampleCount());

		metrics.onTickStart(0L);
		assertTrue(metrics.onTickEnd(ms(10)));
		assertFalse(metrics.onTickEnd(ms(20)), "the same tick cannot be recorded twice");
		assertEquals(1, metrics.sampleCount());
		assertEquals(10.0, metrics.lastMspt(), EPSILON);
	}

	@Test
	void restartedMeasurementDiscardsTheUnfinishedTick() {
		TickMetrics metrics = new TickMetrics();

		metrics.onTickStart(0L);
		metrics.onTickStart(ms(100));
		metrics.onTickEnd(ms(105));

		assertEquals(1, metrics.sampleCount());
		assertEquals(5.0, metrics.lastMspt(), EPSILON, "measured from the second start, not the first");
	}

	@Test
	void snapshotReportsEveryValueRequiredByAc1() {
		TickMetrics metrics = new TickMetrics();
		long now = recordSteady(metrics, 100, 50.0, 10.0);

		TickMetricsSnapshot snapshot = metrics.snapshot(now, ms(90_000));

		assertFalse(snapshot.isEmpty());
		assertEquals(20.0, snapshot.tps(), 1.0e-6);
		assertEquals(10.0, snapshot.lastMspt(), EPSILON);
		assertEquals(10.0, snapshot.avgMspt5s(), EPSILON);
		assertEquals(10.0, snapshot.avgMspt1m(), EPSILON);
		assertEquals(10.0, snapshot.avgMspt5m(), EPSILON);
		assertEquals(10.0, snapshot.p95Mspt1m(), EPSILON);
		assertEquals(10.0, snapshot.p99Mspt1m(), EPSILON);
		assertEquals(10.0, snapshot.p95MsptHistory(), EPSILON);
		assertEquals(10.0, snapshot.p99MsptHistory(), EPSILON);
		assertEquals(10.0, snapshot.maxMspt(), EPSILON);
		assertEquals(100L, snapshot.totalTicks());
		assertEquals(100, snapshot.sampleCount());
		assertEquals(ms(90_000), snapshot.uptimeNanos());

		// 100 ticks 50 ms apart cover just under 5 s, so the label must say 5 s and not a minute.
		assertEquals(ms(4_990), snapshot.retainedSpanNanos());
		assertEquals(ms(4_990), snapshot.shortPercentileSpanNanos(),
				"a server younger than the window is labelled with what it actually has");
	}

	@Test
	void nominalAverageWindowsAreOnlyCoveredOnceTheServerHasLivedThem() {
		TickMetrics metrics = new TickMetrics();
		long now = recordSteady(metrics, 1600, 50.0, 1.0);

		// 80 s of uptime: the 5 s and 1 min averages are real, the 5 min one would be an average
		// over 80 s wearing a "5 min" label, so callers show it as n/a instead (SPEC AC-2's rule).
		TickMetricsSnapshot young = metrics.snapshot(now, ms(80_000));

		assertTrue(young.covers(TickMetrics.WINDOW_5S_NANOS));
		assertTrue(young.covers(TickMetrics.WINDOW_1M_NANOS));
		assertFalse(young.covers(TickMetrics.WINDOW_5M_NANOS));

		TickMetricsSnapshot fresh = metrics.snapshot(now, ms(3_000));

		assertFalse(fresh.covers(TickMetrics.WINDOW_5S_NANOS), "3 s in, even the 5 s window is short");
		assertFalse(fresh.covers(TickMetrics.WINDOW_1M_NANOS));

		TickMetricsSnapshot grown = metrics.snapshot(now, TickMetrics.WINDOW_5M_NANOS);

		assertTrue(grown.covers(TickMetrics.WINDOW_5M_NANOS), "exactly one window counts as covered");
	}

	@Test
	void shortPercentileSpanSaturatesAtOneMinute() {
		TickMetrics metrics = new TickMetrics();
		long now = recordSteady(metrics, 4000, 50.0, 1.0);

		TickMetricsSnapshot snapshot = metrics.snapshot(now, ms(200_000));

		assertEquals(TickMetrics.WINDOW_1M_NANOS, snapshot.shortPercentileSpanNanos());
		assertTrue(snapshot.retainedSpanNanos() > TickMetrics.WINDOW_1M_NANOS,
				"the history line still reports the full retained span");
	}

	@Test
	void resetClearsHistorySoNothingLeaksIntoTheNextWorld() {
		TickMetrics metrics = new TickMetrics();
		long now = recordSteady(metrics, 100, 50.0, 10.0);

		metrics.reset();

		assertEquals(0, metrics.sampleCount());
		assertEquals(0L, metrics.totalTicks());
		assertEquals(0.0, metrics.maxMspt(), EPSILON);
		assertEquals(0.0, metrics.tps(now), EPSILON);
	}

	@Test
	void capacityMustBePositive() {
		assertThrows(IllegalArgumentException.class, () -> new TickMetrics(0));
		assertThrows(IllegalArgumentException.class, () -> new TickMetrics(-1));
	}
}
