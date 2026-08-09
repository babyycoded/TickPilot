package com.tickpilot.budget;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * Covers the load level table of SPEC FR-5 and the anti-chatter guarantees of AC-5, with the
 * clock supplied by the test so no Minecraft runtime and no real waiting are needed.
 */
class TickBudgetTest {
	private static final double TARGET = TickBudget.DEFAULT_TARGET_MSPT;
	private static final double CRITICAL = TickBudget.DEFAULT_CRITICAL_MSPT;
	private static final double HYSTERESIS = TickBudget.DEFAULT_HYSTERESIS_MSPT;
	private static final long HOLD = TickBudget.DEFAULT_MIN_HOLD_MILLIS;

	private static TickBudget budget() {
		return new TickBudget(0L);
	}

	@Test
	void thresholdsFollowTheFr5Table() {
		TickBudget budget = budget();

		assertEquals(40.0, budget.targetMspt(), 1.0e-9);
		assertEquals(45.0, budget.highMspt(), 1.0e-9, "target + (critical - target) * 0.5");
		assertEquals(50.0, budget.criticalMspt(), 1.0e-9);

		assertEquals(LoadLevel.NORMAL, budget.levelFor(0.0));
		assertEquals(LoadLevel.NORMAL, budget.levelFor(39.999));
		assertEquals(LoadLevel.ELEVATED, budget.levelFor(40.0));
		assertEquals(LoadLevel.ELEVATED, budget.levelFor(44.999));
		assertEquals(LoadLevel.HIGH, budget.levelFor(45.0));
		assertEquals(LoadLevel.HIGH, budget.levelFor(49.999));
		assertEquals(LoadLevel.CRITICAL, budget.levelFor(50.0));
		assertEquals(LoadLevel.CRITICAL, budget.levelFor(500.0));
	}

	@Test
	void highBandIsNonEmptyForAnyValidThresholdPair() {
		// The regression this guards is SPEC §13 entry #7: with "target + 25 %" the HIGH band
		// collapsed to nothing whenever critical <= target * 1.25.
		double[][] pairs = {{40.0, 50.0}, {20.0, 60.0}, {45.0, 46.0}, {10.0, 1000.0}};

		for (double[] pair : pairs) {
			TickBudget budget = new TickBudget(pair[0], pair[1], HYSTERESIS, HOLD, 0L);

			assertTrue(budget.targetMspt() < budget.highMspt(),
					"ELEVATED band must be non-empty for " + pair[0] + "/" + pair[1]);
			assertTrue(budget.highMspt() < budget.criticalMspt(),
					"HIGH band must be non-empty for " + pair[0] + "/" + pair[1]);
			assertEquals(LoadLevel.HIGH, budget.levelFor(budget.highMspt()));
		}
	}

	@Test
	void startsNormal() {
		TickBudget budget = budget();

		assertEquals(LoadLevel.NORMAL, budget.level());
		assertNull(budget.update(1.0, 100L), "staying in a level is not a transition");
	}

	@Test
	void escalationIsImmediateAndReported() {
		TickBudget budget = budget();

		LoadLevelTransition transition = budget.update(52.0, 100L);

		assertNotNull(transition);
		assertEquals(LoadLevel.NORMAL, transition.from());
		assertEquals(LoadLevel.CRITICAL, transition.to());
		assertEquals(52.0, transition.avgMspt(), 1.0e-9);
		assertEquals(100L, transition.atMillis());
		assertTrue(transition.isEscalation());
		assertEquals(LoadLevel.CRITICAL, budget.level());
	}

	@Test
	void eachTransitionIsReportedExactlyOnce() {
		TickBudget budget = budget();

		assertNotNull(budget.update(46.0, 100L), "NORMAL -> HIGH");

		for (long t = 200L; t < 5_000L; t += 100L) {
			assertNull(budget.update(46.0, t), "the same level must not be reported again");
		}

		assertEquals(LoadLevel.HIGH, budget.level());
	}

	@Test
	void doesNotChatterWhileAveragedMsptSitsOnTheThreshold() {
		TickBudget budget = budget();
		int transitions = 0;

		// Two minutes of MSPT oscillating either side of the 40 ms ELEVATED threshold.
		for (long t = 0L; t <= 120_000L; t += 50L) {
			double mspt = t / 50L % 2L == 0L ? 39.9 : 40.1;

			if (budget.update(mspt, t) != null) {
				transitions++;
			}
		}

		assertEquals(1, transitions, "entering ELEVATED once is the only legitimate transition");
		assertEquals(LoadLevel.ELEVATED, budget.level());
	}

	@Test
	void levelIsHeldForTheMinimumTimeBeforeItCanDrop() {
		TickBudget budget = budget();
		budget.update(60.0, 1_000L);
		assertEquals(LoadLevel.CRITICAL, budget.level());

		// Load disappears completely, but the level is pinned for the hold period.
		for (long t = 1_050L; t < 1_000L + HOLD; t += 50L) {
			assertNull(budget.update(0.5, t));
			assertEquals(LoadLevel.CRITICAL, budget.level());
		}

		LoadLevelTransition transition = budget.update(0.5, 1_000L + HOLD);

		assertNotNull(transition);
		assertEquals(LoadLevel.CRITICAL, transition.from());
		assertEquals(LoadLevel.NORMAL, transition.to());
		assertFalse(transition.isEscalation());
	}

	@Test
	void hysteresisKeepsTheLevelWhenMsptDipsJustBelowTheThreshold() {
		TickBudget budget = budget();
		budget.update(41.0, 0L);
		assertEquals(LoadLevel.ELEVATED, budget.level());

		// Well past the hold time, but still inside the hysteresis band below 40 ms.
		assertNull(budget.update(TARGET - HYSTERESIS + 0.1, 60_000L));
		assertEquals(LoadLevel.ELEVATED, budget.level());

		// One tenth of a millisecond lower and it is a real recovery.
		assertNotNull(budget.update(TARGET - HYSTERESIS - 0.1, 61_000L));
		assertEquals(LoadLevel.NORMAL, budget.level());
	}

	@Test
	void recoveryDropsOneLevelAtATime() {
		TickBudget budget = budget();
		budget.update(80.0, 0L);
		assertEquals(LoadLevel.CRITICAL, budget.level());

		// 44.5 raw is ELEVATED, but it is still inside the hysteresis band of HIGH, so the
		// budget must not skip a level on the way down.
		LoadLevelTransition first = budget.update(44.5, HOLD);
		assertNotNull(first);
		assertEquals(LoadLevel.HIGH, first.to());

		// And the next step down needs both another hold period and a value clear of HIGH.
		assertNull(budget.update(44.5, HOLD + 100L));
		assertEquals(LoadLevel.HIGH, budget.level());

		LoadLevelTransition second = budget.update(30.0, 2 * HOLD);
		assertNotNull(second);
		assertEquals(LoadLevel.NORMAL, second.to());
	}

	@Test
	void escalationIsNotDelayedByTheHoldTime() {
		TickBudget budget = budget();
		budget.update(41.0, 0L);
		assertEquals(LoadLevel.ELEVATED, budget.level());

		LoadLevelTransition transition = budget.update(CRITICAL + 10.0, 50L);

		assertNotNull(transition, "a degrading server must not wait out the hold period");
		assertEquals(LoadLevel.CRITICAL, transition.to());
	}

	@Test
	void heldForReportsHowLongTheCurrentLevelHasBeenHeld() {
		TickBudget budget = budget();
		budget.update(60.0, 1_000L);

		assertEquals(4_000L, budget.heldForMillis(5_000L));
	}

	@Test
	void invalidThresholdsAreRejected() {
		assertThrows(IllegalArgumentException.class, () -> new TickBudget(0.0, 50.0, 2.0, 0L, 0L));
		assertThrows(IllegalArgumentException.class, () -> new TickBudget(-1.0, 50.0, 2.0, 0L, 0L));
		assertThrows(IllegalArgumentException.class, () -> new TickBudget(50.0, 50.0, 2.0, 0L, 0L),
				"critical must be strictly above target");
		assertThrows(IllegalArgumentException.class, () -> new TickBudget(50.0, 40.0, 2.0, 0L, 0L));
		assertThrows(IllegalArgumentException.class, () -> new TickBudget(40.0, 50.0, -0.1, 0L, 0L));
		assertThrows(IllegalArgumentException.class, () -> new TickBudget(40.0, 50.0, 2.0, -1L, 0L));
	}

	@Test
	void everyLevelHasATranslationKey() {
		for (LoadLevel level : LoadLevel.values()) {
			assertEquals("tickpilot.load_level." + level.name().toLowerCase(java.util.Locale.ROOT),
					level.translationKey());
		}
	}
}
