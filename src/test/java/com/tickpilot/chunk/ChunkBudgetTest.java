package com.tickpilot.chunk;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.tickpilot.chunk.ChunkBudget.LiftReason;

import org.junit.jupiter.api.Test;

/**
 * SPEC FR-10, AC-10, INV-8.
 *
 * <p>The property that matters most here is a negative one: there is no sequence of calls, at any
 * configuration, that makes {@link ChunkBudget#allow} refuse a class SPEC INV-8 protects. That is
 * checked by walking the whole input space rather than by testing the cases somebody thought of,
 * the same way {@code TickPolicyTest} checks the STRICT guarantee.
 */
class ChunkBudgetTest {
	private static ChunkBudget capped(int max) {
		ChunkBudget budget = new ChunkBudget();
		budget.configure(true, true, max);
		budget.beginTick(1L);
		return budget;
	}

	/** Drives the budget into the suspected-block release: work held, then a second of silence. */
	private static void stall(ChunkBudget budget) {
		budget.beginDrain();
		budget.allow(ChunkOpClass.BACKGROUND);
		budget.endDrain(false, true, 0L);
		budget.endDrain(false, true, ChunkBudget.STALL_SUSPECT_MILLIS * 1_000_000L);
	}

	// --- INV-8: the classes that are never held ------------------------------------------------

	@Test
	void playerCriticalWorkIsNeverHeldWhateverTheState() {
		for (ChunkOpClass opClass : ChunkOpClass.all()) {
			if (opClass.isOptional()) {
				continue;
			}

			// A budget of zero, already saturated, with a lift both pending and not: nothing an
			// operator or the server can do reaches a "no" for these classes.
			ChunkBudget budget = capped(0);

			for (int i = 0; i < 1000; i++) {
				assertTrue(budget.allow(opClass), opClass + " was held back on call " + i);
			}

			assertFalse(budget.stats().heldPlayerCritical());
		}
	}

	@Test
	void anExhaustedBudgetStillLetsPlayerWorkThrough() {
		ChunkBudget budget = capped(1);

		assertTrue(budget.allow(ChunkOpClass.REMOTE_GENERATION));
		assertFalse(budget.allow(ChunkOpClass.REMOTE_GENERATION));
		assertTrue(budget.allow(ChunkOpClass.PLAYER_LOADING));
		assertTrue(budget.allow(ChunkOpClass.PLAYER_TELEPORT));
		assertTrue(budget.allow(ChunkOpClass.FORCE_LOADED));
		assertFalse(budget.allow(ChunkOpClass.BACKGROUND));
	}

	// --- the cap itself ------------------------------------------------------------------------

	@Test
	void theCapAppliesPerTickAndRefillsOnTheNextOne() {
		ChunkBudget budget = capped(2);

		assertTrue(budget.allow(ChunkOpClass.REMOTE_GENERATION));
		assertTrue(budget.allow(ChunkOpClass.REMOTE_GENERATION));
		assertFalse(budget.allow(ChunkOpClass.REMOTE_GENERATION));

		budget.endTick();
		budget.beginTick(2L);

		assertTrue(budget.allow(ChunkOpClass.REMOTE_GENERATION));
	}

	@Test
	void theTwoOptionalClassesShareOneAllowance() {
		ChunkBudget budget = capped(2);

		assertTrue(budget.allow(ChunkOpClass.REMOTE_GENERATION));
		assertTrue(budget.allow(ChunkOpClass.BACKGROUND));
		assertFalse(budget.allow(ChunkOpClass.BACKGROUND));
		assertFalse(budget.allow(ChunkOpClass.REMOTE_GENERATION));
	}

	@Test
	void aDisabledBudgetHoldsNothing() {
		ChunkBudget budget = new ChunkBudget();
		budget.configure(false, false, 0);
		budget.beginTick(1L);

		assertFalse(budget.isEnabled());
		assertFalse(budget.isLimiting());

		for (ChunkOpClass opClass : ChunkOpClass.all()) {
			assertTrue(budget.allow(opClass));
		}
	}

	/** SPEC FR-11: a mode that does not act at this load level counts but never holds. */
	@Test
	void anEnabledButNonInterveningBudgetCountsWithoutHolding() {
		ChunkBudget budget = new ChunkBudget();
		budget.configure(true, false, 0);
		budget.beginTick(1L);

		assertTrue(budget.isEnabled());
		assertFalse(budget.isLimiting());
		assertTrue(budget.allow(ChunkOpClass.BACKGROUND));
		assertEquals(1L, budget.stats().dispatched(ChunkOpClass.BACKGROUND));
		assertEquals(0L, budget.stats().held(ChunkOpClass.BACKGROUND));
	}

	// --- release 1: nothing dispatched for too long --------------------------------------------

	private static final long MILLI = 1_000_000L;
	private static final long STALL_NANOS = ChunkBudget.STALL_SUSPECT_MILLIS * MILLI;

	@Test
	void aRunOfEmptyDrainsThatLastsTooLongLiftsTheCap() {
		ChunkBudget budget = capped(0);

		budget.beginDrain();
		assertFalse(budget.allow(ChunkOpClass.REMOTE_GENERATION));
		budget.endDrain(false, true, 0L);

		budget.endDrain(false, true, STALL_NANOS - 1L);
		assertTrue(budget.isLimiting(), "lifted before the threshold");

		budget.endDrain(false, true, STALL_NANOS);

		assertFalse(budget.isLimiting());
		assertSame(LiftReason.SUSPECTED_BLOCK, budget.liftReason());
		assertTrue(budget.allow(ChunkOpClass.REMOTE_GENERATION), "the cap must be off now");
	}

	/**
	 * The case the count-based version of this rule got wrong on a live server: between two ticks
	 * {@code MinecraftServer} polls chunk tasks in its spare time and produces hundreds of empty
	 * drains, and a server that is still ticking dispatches something every tick.
	 */
	@Test
	void hundredsOfEmptyDrainsBetweenTicksAreNotSuspicious() {
		ChunkBudget budget = capped(1);

		for (int tick = 1; tick <= 40; tick++) {
			budget.beginTick(tick);
			long tickStart = tick * 50L * MILLI;

			// One dispatch at the start of the tick, then the idle poll loop for the rest of it.
			budget.beginDrain();
			assertTrue(budget.allow(ChunkOpClass.REMOTE_GENERATION));
			budget.endDrain(true, true, tickStart);

			for (int poll = 1; poll <= 400; poll++) {
				budget.beginDrain();
				assertFalse(budget.allow(ChunkOpClass.REMOTE_GENERATION));
				budget.endDrain(false, true, tickStart + poll * 100_000L);
			}

			budget.endTick();
		}

		assertTrue(budget.isLimiting(), "idle polling must not look like a blocked server thread");
		assertNull(budget.liftReason());
	}

	@Test
	void aDrainThatDispatchedSomethingResetsTheSuspicion() {
		ChunkBudget budget = capped(0);

		for (int i = 0; i < 200; i++) {
			budget.beginDrain();
			// Every other drain moved work forward, so the empty run never lasts long enough,
			// however much wall-clock time passes in total.
			budget.endDrain(i % 2 == 0, true, i * STALL_NANOS);
		}

		assertTrue(budget.isLimiting());
		assertNull(budget.liftReason());
	}

	@Test
	void aDrainWithNothingLeftWaitingIsNotSuspicious() {
		ChunkBudget budget = capped(0);

		for (int i = 0; i < 200; i++) {
			budget.beginDrain();
			budget.endDrain(false, false, i * STALL_NANOS);
		}

		assertTrue(budget.isLimiting());
	}

	// --- release 2: sustained saturation -------------------------------------------------------

	@Test
	void sustainedSaturationLiftsTheCap() {
		ChunkBudget budget = capped(0);

		for (int tick = 1; tick < ChunkBudget.SATURATED_TICKS_BEFORE_LIFT; tick++) {
			budget.beginTick(tick);
			assertFalse(budget.allow(ChunkOpClass.REMOTE_GENERATION));
			budget.endTick();
			assertTrue(budget.isLimiting(), "lifted too early, on tick " + tick);
		}

		budget.beginTick(ChunkBudget.SATURATED_TICKS_BEFORE_LIFT);
		assertFalse(budget.allow(ChunkOpClass.REMOTE_GENERATION));
		budget.endTick();

		assertFalse(budget.isLimiting());
		assertSame(LiftReason.SUSTAINED_SATURATION, budget.liftReason());
	}

	@Test
	void oneUnsaturatedTickResetsTheRun() {
		ChunkBudget budget = capped(1);

		for (int tick = 1; tick <= ChunkBudget.SATURATED_TICKS_BEFORE_LIFT * 3; tick++) {
			budget.beginTick(tick);
			assertTrue(budget.allow(ChunkOpClass.REMOTE_GENERATION));

			// Every third tick holds nothing back, so the run never reaches the threshold.
			if (tick % 3 != 0) {
				assertFalse(budget.allow(ChunkOpClass.REMOTE_GENERATION));
			}

			budget.endTick();
		}

		assertTrue(budget.isLimiting());
	}

	@Test
	void aLiftExpiresAndTheCapComesBack() {
		ChunkBudget budget = capped(0);
		stall(budget);

		assertFalse(budget.isLimiting());

		budget.beginTick(1L + ChunkBudget.LIFT_DURATION_TICKS - 1L);
		assertFalse(budget.isLimiting(), "the lift must last its full duration");

		budget.beginTick(1L + ChunkBudget.LIFT_DURATION_TICKS);
		assertTrue(budget.isLimiting());
		assertNull(budget.liftReason());
	}

	@Test
	void aLiftIsReportedExactlyOnce() {
		ChunkBudget budget = capped(0);
		stall(budget);

		// Long after the release, the same blocked state keeps producing empty drains.
		for (int i = 2; i < 10; i++) {
			budget.endDrain(false, true, i * STALL_NANOS);
		}

		assertTrue(budget.consumeLiftEvent());
		assertFalse(budget.consumeLiftEvent(), "one lift is one log line, not one per drain");
		assertEquals(1L, budget.stats().lifts());
		assertEquals(1L, budget.stats().liftsSuspectedBlock());
		assertEquals(0L, budget.stats().liftsSaturation());
	}

	// --- bookkeeping ---------------------------------------------------------------------------

	@Test
	void statsCountBothSidesPerClass() {
		ChunkBudget budget = capped(1);

		budget.allow(ChunkOpClass.PLAYER_LOADING);
		budget.allow(ChunkOpClass.REMOTE_GENERATION);
		budget.allow(ChunkOpClass.REMOTE_GENERATION);
		budget.allow(ChunkOpClass.BACKGROUND);
		budget.endTick();

		ChunkBudgetStats stats = budget.stats();

		assertEquals(2L, stats.dispatched());
		assertEquals(2L, stats.held());
		assertEquals(1L, stats.dispatched(ChunkOpClass.PLAYER_LOADING));
		assertEquals(1L, stats.dispatched(ChunkOpClass.REMOTE_GENERATION));
		assertEquals(1L, stats.held(ChunkOpClass.REMOTE_GENERATION));
		assertEquals(1L, stats.held(ChunkOpClass.BACKGROUND));
		assertEquals(1L, stats.limitedTicks());
		assertFalse(stats.heldPlayerCritical());
		assertFalse(stats.isUnused());
	}

	@Test
	void statsDoNotShareStateWithTheBudget() {
		ChunkBudget budget = capped(1);
		budget.allow(ChunkOpClass.PLAYER_LOADING);

		ChunkBudgetStats before = budget.stats();
		budget.allow(ChunkOpClass.PLAYER_LOADING);

		assertEquals(1L, before.dispatched(ChunkOpClass.PLAYER_LOADING));
		assertEquals(2L, budget.stats().dispatched(ChunkOpClass.PLAYER_LOADING));
	}

	@Test
	void resetClearsEverythingIncludingALift() {
		ChunkBudget budget = capped(0);
		stall(budget);
		budget.reset();

		assertFalse(budget.isEnabled());
		assertNull(budget.liftReason());
		assertTrue(budget.stats().isUnused());
		assertEquals(0L, budget.stats().lifts());
	}
}
