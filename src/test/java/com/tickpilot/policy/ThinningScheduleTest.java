package com.tickpilot.policy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * The thinning grid of SPEC §13 entry #17: every object runs on one tick in {@code interval}, and
 * objects with different phases land on different ticks.
 *
 * <p>The staggering is the point. Phase 7 measured what happens without it — 666 tasks sharing a
 * deadline ran in one tick and cost 0.7 s — so these tests exist to keep that shape from coming back
 * through the throttling side.
 */
class ThinningScheduleTest {
	@Test
	void theDefaultIntervalThinsNothing() {
		// SPEC FR-15 ships min_entity_update_interval_ticks = 1 (INV-3).
		assertFalse(ThinningSchedule.thins(1));
		assertFalse(ThinningSchedule.thins(0));
		assertFalse(ThinningSchedule.thins(-4));

		for (long tick = 0; tick < 50; tick++) {
			assertTrue(ThinningSchedule.runsOnTick(tick, 7, 1));
		}
	}

	@Test
	void anObjectRunsExactlyOneTickInEvery() {
		for (int interval = 2; interval <= 8; interval++) {
			int runs = 0;

			for (long tick = 0; tick < interval * 100L; tick++) {
				if (ThinningSchedule.runsOnTick(tick, 3, interval)) {
					runs++;
				}
			}

			assertEquals(100, runs, "interval " + interval);
		}
	}

	@Test
	void theGapBetweenRunsIsNeverLongerThanTheInterval() {
		long lastRun = -1L;

		for (long tick = 0; tick < 500L; tick++) {
			if (ThinningSchedule.runsOnTick(tick, 11, 4)) {
				if (lastRun >= 0L) {
					assertEquals(4L, tick - lastRun);
				}

				lastRun = tick;
			}
		}
	}

	@Test
	void differentPhasesLandOnDifferentTicks() {
		// The whole reason the phase exists: with 400 objects and an interval of 4, each tick gets
		// a quarter of them rather than all of them once in four.
		int interval = 4;
		int[] perTick = new int[interval];

		for (int id = 0; id < 400; id++) {
			for (long tick = 0; tick < interval; tick++) {
				if (ThinningSchedule.runsOnTick(tick, id, interval)) {
					perTick[(int) tick]++;
				}
			}
		}

		for (int tick = 0; tick < interval; tick++) {
			assertEquals(100, perTick[tick], "tick " + tick + " carries its quarter, not all of it");
		}
	}

	@Test
	void aSharedPhaseIsWhatSynchronisationWouldLookLike() {
		// The failure mode this design avoids, asserted so the difference is not theoretical: with
		// one phase for everybody, every object lands on the same tick.
		int interval = 4;
		int onTickZero = 0;

		for (int object = 0; object < 400; object++) {
			if (ThinningSchedule.runsOnTick(0L, 0, interval)) {
				onTickZero++;
			}
		}

		assertEquals(400, onTickZero);
	}

	@Test
	void negativePhasesDoNotFallOffTheGrid() {
		// Math.floorMod rather than %: a negative remainder would make the condition unreachable
		// and the object would never run again.
		int runs = 0;

		for (long tick = 0; tick < 100L; tick++) {
			if (ThinningSchedule.runsOnTick(tick, -7, 4)) {
				runs++;
			}
		}

		assertEquals(25, runs);
	}

	@Test
	void theScheduleFollowsWorldTimeNotAnObjectsOwnCount() {
		// An object thinned, left alone, then thinned again resumes on the same grid; two objects
		// with the same phase always agree, whenever either of them started being thinned.
		assertEquals(ThinningSchedule.runsOnTick(1_000_000L, 5, 3),
				ThinningSchedule.runsOnTick(1_000_003L, 5, 3));
		assertEquals(ThinningSchedule.runsOnTick(42L, 5, 3), ThinningSchedule.runsOnTick(42L, 5, 3));
	}

	@Test
	void runFractionMatchesWhatTheGridActuallyDoes() {
		assertEquals(1.0, ThinningSchedule.runFraction(1));
		assertEquals(0.25, ThinningSchedule.runFraction(4));
	}
}
