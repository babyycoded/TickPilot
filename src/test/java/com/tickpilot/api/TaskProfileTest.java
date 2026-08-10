package com.tickpilot.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * The normalisation rules of {@link TaskProfile}: a contradictory profile from another mod is
 * corrected towards the safe reading and never throws, because an exception in a mod initialiser
 * takes the server down and SPEC INV-9 forbids TickPilot from being the cause of that.
 */
class TaskProfileTest {
	@Test
	void criticalWinsOverDeferrable() {
		TaskProfile profile = TaskProfile.builder().critical(true).deferrable(true).build();

		assertTrue(profile.critical());
		assertFalse(profile.deferrable(), "the conservative half of the contradiction survives");
	}

	@Test
	void aNegativeDeadlineBecomesTheNextTick() {
		assertEquals(0L, TaskProfile.deferrableTask(-50L, TaskPriority.NORMAL).maxDelayTicks());
	}

	@Test
	void aDeadlineBeyondTheCeilingIsClamped() {
		// AC-6 guarantees that a deferred task eventually runs. A profile may choose its own
		// deadline but not name one so distant that the guarantee stops meaning anything.
		TaskProfile profile = TaskProfile.deferrableTask(Long.MAX_VALUE, TaskPriority.LOW);

		assertEquals(TaskProfile.MAX_DELAY_TICKS, profile.maxDelayTicks());
	}

	@Test
	void aMissingPriorityBecomesNormal() {
		assertEquals(TaskPriority.NORMAL,
				new TaskProfile(true, 10L, false, false, false, null).priority());
	}

	@Test
	void theFactoriesSayWhatTheyMean() {
		TaskProfile critical = TaskProfile.criticalTask();
		assertTrue(critical.critical());
		assertFalse(critical.deferrable());

		TaskProfile immediate = TaskProfile.immediate();
		assertFalse(immediate.critical(), "immediate is not the same claim as critical");
		assertFalse(immediate.deferrable());

		TaskProfile deferrable = TaskProfile.deferrableTask();
		assertTrue(deferrable.deferrable());
		assertFalse(deferrable.critical());
		assertEquals(TaskProfile.DEFAULT_MAX_DELAY_TICKS, deferrable.maxDelayTicks());
		assertEquals(TaskPriority.NORMAL, deferrable.priority());
	}

	@Test
	void theModifiersChangeOneFlagAndNothingElse() {
		TaskProfile base = TaskProfile.deferrableTask(7L, TaskPriority.HIGH);

		assertEquals(new TaskProfile(true, 7L, false, false, true, TaskPriority.HIGH),
				base.coalescing());
		assertEquals(new TaskProfile(true, 7L, false, true, false, TaskPriority.HIGH),
				base.allowingAsyncCompute());
	}

	@Test
	void theBuilderDefaultsToOrdinaryDeferrableWork() {
		assertEquals(TaskProfile.deferrableTask(), TaskProfile.builder().build());
	}

	@Test
	void priorityOrderRunsFromMostToLeastUrgent() {
		assertTrue(TaskPriority.HIGH.isMoreUrgentThan(TaskPriority.NORMAL));
		assertTrue(TaskPriority.NORMAL.isMoreUrgentThan(TaskPriority.LOW));
		assertFalse(TaskPriority.NORMAL.isMoreUrgentThan(TaskPriority.NORMAL));
		assertFalse(TaskPriority.LOW.isMoreUrgentThan(TaskPriority.HIGH));
	}

	@Test
	void everySubmitResultAnswersExactlyOneOfTheThreeQuestions() {
		// ran / queued / rejected partition the outcomes: a caller that checks all three cannot
		// find a result that belongs to none of them, or to two.
		for (SubmitResult result : SubmitResult.values()) {
			int answers = (result.ran() ? 1 : 0) + (result.queued() ? 1 : 0)
					+ (result.rejected() ? 1 : 0);

			assertEquals(1, answers, result + " must be exactly one of ran, queued or rejected");
		}
	}
}
