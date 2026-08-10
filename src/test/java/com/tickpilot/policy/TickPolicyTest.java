package com.tickpilot.policy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.tickpilot.budget.LoadLevel;
import com.tickpilot.config.AdaptiveMode;
import com.tickpilot.zones.ActivityZone;

import org.junit.jupiter.api.Test;

/**
 * The rules of SPEC FR-11 and INV-5, checked by walking the whole input space rather than by
 * sampling it. The decision is a pure function of seven values, two of which are enums with four
 * and three constants, so "every possible input" is 288 cases and there is no reason to guess.
 */
class TickPolicyTest {
	/** Walks all 288 combinations and hands each to {@code check}. */
	private static void forEveryInput(Case check) {
		for (ActivityZone zone : ActivityZone.values()) {
			for (AdaptiveMode mode : AdaptiveMode.values()) {
				for (LoadLevel load : LoadLevel.values()) {
					for (int flags = 0; flags < 8; flags++) {
						boolean allowlisted = (flags & 1) != 0;
						boolean denylisted = (flags & 2) != 0;
						boolean protectedObject = (flags & 4) != 0;

						check.accept(zone, mode, load, allowlisted, denylisted, protectedObject);
					}
				}
			}
		}
	}

	@FunctionalInterface
	private interface Case {
		void accept(ActivityZone zone, AdaptiveMode mode, LoadLevel load, boolean allowlisted,
				boolean denylisted, boolean protectedObject);
	}

	private static ThrottleVerdict decide(ActivityZone zone, AdaptiveMode mode, LoadLevel load,
			boolean allowlisted, boolean denylisted, boolean protectedObject) {
		return TickPolicy.decide(zone, mode, load, true, allowlisted, denylisted, protectedObject);
	}

	@Test
	void strictModeIsExhaustivelyInert() {
		// SPEC AC-11: STRICT guarantees no intervention. Not "in the cases we thought of".
		forEveryInput((zone, mode, load, allowlisted, denylisted, protectedObject) -> {
			if (mode != AdaptiveMode.STRICT) {
				return;
			}

			ThrottleVerdict verdict = decide(zone, mode, load, allowlisted, denylisted, protectedObject);

			assertSame(ThrottleVerdict.TICK_STRICT_MODE, verdict,
					"STRICT must report itself as the reason for " + zone + "/" + load);
			assertFalse(verdict.isEligible());
		});
	}

	@Test
	void adaptiveModeOffIsExhaustivelyInert() {
		forEveryInput((zone, mode, load, allowlisted, denylisted, protectedObject) -> {
			ThrottleVerdict verdict = TickPolicy.decide(zone, mode, load, false, allowlisted,
					denylisted, protectedObject);

			assertSame(ThrottleVerdict.TICK_ADAPTIVE_DISABLED, verdict);
			assertFalse(verdict.isEligible());
		});
	}

	@Test
	void nothingIsEverEligibleWithoutTheOperatorsAllowlist() {
		// SPEC INV-5 by construction: there is no path to an eligible verdict without it, whatever
		// the mode, the load, the zone or the API say.
		forEveryInput((zone, mode, load, allowlisted, denylisted, protectedObject) -> {
			if (allowlisted) {
				return;
			}

			assertFalse(decide(zone, mode, load, false, denylisted, protectedObject).isEligible(),
					"a type nobody listed was eligible at " + mode + "/" + load + "/" + zone);
		});
	}

	@Test
	void theDenylistOutranksTheAllowlist() {
		forEveryInput((zone, mode, load, allowlisted, denylisted, protectedObject) -> {
			if (!denylisted || mode == AdaptiveMode.STRICT || protectedObject) {
				return;
			}

			assertSame(ThrottleVerdict.TICK_DENYLISTED,
					decide(zone, mode, load, allowlisted, true, false));
		});
	}

	@Test
	void aProtectedObjectIsNeverEligible() {
		// Force-loaded, ridden, riding, leashed, persistent, named, always-ticking, or vetoed
		// through the API (SPEC INV-8, AC-7).
		forEveryInput((zone, mode, load, allowlisted, denylisted, protectedObject) -> {
			assertFalse(decide(zone, mode, load, allowlisted, denylisted, true).isEligible());
		});
	}

	@Test
	void theFullZoneIsNeverEligible() {
		forEveryInput((zone, mode, load, allowlisted, denylisted, protectedObject) -> {
			assertFalse(decide(ActivityZone.FULL, mode, load, allowlisted, denylisted, protectedObject)
					.isEligible());
		});
	}

	@Test
	void balancedActsOnlyAtHighAndCritical() {
		assertSame(ThrottleVerdict.TICK_LOAD_TOO_LOW, balanced(LoadLevel.NORMAL));
		assertSame(ThrottleVerdict.TICK_LOAD_TOO_LOW, balanced(LoadLevel.ELEVATED));
		assertSame(ThrottleVerdict.ELIGIBLE_REDUCED, balanced(LoadLevel.HIGH));
		assertSame(ThrottleVerdict.ELIGIBLE_REDUCED, balanced(LoadLevel.CRITICAL));
	}

	@Test
	void aggressiveStartsOneLevelEarlierAndStillNotAtNormal() {
		assertSame(ThrottleVerdict.TICK_LOAD_TOO_LOW, aggressive(LoadLevel.NORMAL),
				"a server inside its budget has nothing to gain");
		assertSame(ThrottleVerdict.ELIGIBLE_REDUCED, aggressive(LoadLevel.ELEVATED));
		assertSame(ThrottleVerdict.ELIGIBLE_REDUCED, aggressive(LoadLevel.HIGH));
		assertSame(ThrottleVerdict.ELIGIBLE_REDUCED, aggressive(LoadLevel.CRITICAL));
	}

	@Test
	void theZoneIsReportedInTheEligibleVerdict() {
		assertSame(ThrottleVerdict.ELIGIBLE_REDUCED, TickPolicy.decide(ActivityZone.REDUCED,
				AdaptiveMode.BALANCED, LoadLevel.HIGH, true, true, false, false));
		assertSame(ThrottleVerdict.ELIGIBLE_FROZEN, TickPolicy.decide(ActivityZone.FROZEN,
				AdaptiveMode.BALANCED, LoadLevel.HIGH, true, true, false, false));
	}

	@Test
	void theReasonIsTheMostFundamentalOneThatApplies() {
		// Everything wrong at once: the whole-server switch is what gets reported, because if it is
		// set no other reason is worth an operator's attention.
		assertSame(ThrottleVerdict.TICK_ADAPTIVE_DISABLED, TickPolicy.decide(ActivityZone.FULL,
				AdaptiveMode.STRICT, LoadLevel.NORMAL, false, false, true, true));
		assertSame(ThrottleVerdict.TICK_STRICT_MODE, TickPolicy.decide(ActivityZone.FULL,
				AdaptiveMode.STRICT, LoadLevel.NORMAL, true, false, true, true));
		assertSame(ThrottleVerdict.TICK_PROTECTED, TickPolicy.decide(ActivityZone.FULL,
				AdaptiveMode.BALANCED, LoadLevel.NORMAL, true, false, true, true));
		assertSame(ThrottleVerdict.TICK_DENYLISTED, TickPolicy.decide(ActivityZone.FULL,
				AdaptiveMode.BALANCED, LoadLevel.NORMAL, true, false, true, false));
		assertSame(ThrottleVerdict.TICK_IN_FULL_ZONE, TickPolicy.decide(ActivityZone.FULL,
				AdaptiveMode.BALANCED, LoadLevel.NORMAL, true, false, false, false));
		assertSame(ThrottleVerdict.TICK_NOT_ALLOWLISTED, TickPolicy.decide(ActivityZone.FROZEN,
				AdaptiveMode.BALANCED, LoadLevel.NORMAL, true, false, false, false));

		// And the one an operator most wants to see: everything agrees except the server's health.
		assertSame(ThrottleVerdict.TICK_LOAD_TOO_LOW, TickPolicy.decide(ActivityZone.FROZEN,
				AdaptiveMode.BALANCED, LoadLevel.NORMAL, true, true, false, false));
	}

	@Test
	void aMissingZoneOrLoadLevelIsTreatedAsTheSafeAnswer() {
		assertSame(ThrottleVerdict.TICK_IN_FULL_ZONE, TickPolicy.decide(null, AdaptiveMode.BALANCED,
				LoadLevel.CRITICAL, true, true, false, false));
		assertSame(ThrottleVerdict.TICK_LOAD_TOO_LOW, TickPolicy.decide(ActivityZone.FROZEN,
				AdaptiveMode.BALANCED, null, true, true, false, false));
	}

	@Test
	void intervenesAtMatchesTheModeTable() {
		for (LoadLevel load : LoadLevel.values()) {
			assertFalse(TickPolicy.intervenesAt(AdaptiveMode.STRICT, load));
		}

		assertTrue(TickPolicy.intervenesAt(AdaptiveMode.AGGRESSIVE, LoadLevel.ELEVATED));
		assertFalse(TickPolicy.intervenesAt(AdaptiveMode.BALANCED, LoadLevel.ELEVATED));
	}

	@Test
	void everyVerdictHasATranslationKeyAndOnlyTwoAreEligible() {
		int eligible = 0;

		for (ThrottleVerdict verdict : ThrottleVerdict.all()) {
			assertTrue(verdict.translationKey().startsWith("tickpilot.verdict."));

			if (verdict.isEligible()) {
				eligible++;
			}
		}

		assertEquals(2, eligible);
	}

	private static ThrottleVerdict balanced(LoadLevel load) {
		return TickPolicy.decide(ActivityZone.REDUCED, AdaptiveMode.BALANCED, load, true, true,
				false, false);
	}

	private static ThrottleVerdict aggressive(LoadLevel load) {
		return TickPolicy.decide(ActivityZone.REDUCED, AdaptiveMode.AGGRESSIVE, load, true, true,
				false, false);
	}
}
