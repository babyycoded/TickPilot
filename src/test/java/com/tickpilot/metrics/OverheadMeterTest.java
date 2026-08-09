package com.tickpilot.metrics;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** SPEC INV-10 and FR-12: what the mod costs, reported rather than asserted. */
class OverheadMeterTest {
	private OverheadMeter meter;

	@BeforeEach
	void setUp() {
		meter = new OverheadMeter();
	}

	@Test
	void anUnusedMeterReportsZeroRatherThanDividingByZero() {
		assertEquals(0.0, meter.meanNanos());
		assertEquals(0L, meter.peakNanos());
		assertEquals(0L, meter.samples());
		assertEquals(0.0, meter.msptPerTick(2));
		assertEquals(0.0, meter.percentOf(2, 50.0));
	}

	@Test
	void meansAndPeaksAreKeptApart() {
		meter.record(1_000L);
		meter.record(3_000L);
		meter.record(2_000L);

		assertEquals(2_000.0, meter.meanNanos());
		assertEquals(3_000L, meter.peakNanos(), "a spike must survive the averaging");
		assertEquals(3L, meter.samples());
	}

	@Test
	void perTickCostCountsBothHalvesOfTheListener() {
		// 4 us of mod work in each half of the tick listener.
		meter.record(4_000L);
		meter.record(4_000L);

		assertEquals(0.008, meter.msptPerTick(2), 1.0e-9, "two measurements of 4 us is 8 us a tick");
	}

	@Test
	void theShareOfTheTickIsWhatInv10Caps() {
		// 10 us per half against a 20 ms tick: 0.02 ms of 20 ms is 0.1 %.
		meter.record(10_000L);

		assertEquals(0.1, meter.percentOf(2, 20.0), 1.0e-9);
		assertTrue(meter.percentOf(2, 20.0) < 1.0, "INV-10 caps the default mode at about 1 %");
	}

	@Test
	void aServerWithNoMeasuredTickTimeReportsZeroShareNotInfinity() {
		meter.record(5_000L);

		assertEquals(0.0, meter.percentOf(2, 0.0));
	}

	@Test
	void aBackwardsClockIsIgnoredRatherThanRecordedAsNegativeWork() {
		meter.record(1_000L);
		meter.record(-500L);

		assertEquals(1L, meter.samples());
		assertEquals(1_000.0, meter.meanNanos());
	}

	@Test
	void resetClearsEverything() {
		meter.record(1_000L);
		meter.reset();

		assertEquals(0L, meter.samples());
		assertEquals(0.0, meter.meanNanos());
		assertEquals(0L, meter.peakNanos());
	}
}
