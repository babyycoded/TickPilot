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
	void tpsUsesRealUptimeBeforeTheFirstWindowHasElapsed() {
		TickMetrics metrics = new TickMetrics();
		// Only 400 ms of history: dividing by the nominal 5 s window would report 1.6 TPS.
		long now = recordSteady(metrics, 8, 50.0, 1.0);

		assertEquals(20.0, metrics.tps(now), 1.0e-6);
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
		assertEquals(10.0, snapshot.p95Mspt(), EPSILON);
		assertEquals(10.0, snapshot.p99Mspt(), EPSILON);
		assertEquals(10.0, snapshot.maxMspt(), EPSILON);
		assertEquals(100L, snapshot.totalTicks());
		assertEquals(100, snapshot.sampleCount());
		assertEquals(ms(90_000), snapshot.uptimeNanos());
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
