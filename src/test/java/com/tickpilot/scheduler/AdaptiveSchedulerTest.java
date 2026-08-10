package com.tickpilot.scheduler;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import com.tickpilot.api.SubmitResult;
import com.tickpilot.api.TaskPriority;
import com.tickpilot.api.TaskProfile;

import org.junit.jupiter.api.Test;

/**
 * Covers the four guarantees SPEC AC-6 makes about the deferred task queue: priority order, forced
 * execution once {@code maxDelayTicks} has elapsed, bounded overflow, and critical work that is
 * never deferred.
 *
 * <p>No Minecraft: the task id is a {@link String} and the clock is a field this test moves by
 * hand, so every case is deterministic and none of them waits for real time.
 */
class AdaptiveSchedulerTest {
	/** Big enough that the priority drain is never cut short in the tests that do not test it. */
	private static final long WHOLE_TICK = 50_000_000L;

	private final List<String> executed = new ArrayList<>();
	private long nanos;

	/** A clock the test moves; constant unless a case is about the time budget. */
	private long clock() {
		return nanos;
	}

	private AdaptiveScheduler<String> scheduler(int maxQueued) {
		return new AdaptiveScheduler<>(maxQueued, this::clock, AdaptiveScheduler.Events.ignore());
	}

	private Runnable record(String name) {
		return () -> executed.add(name);
	}

	private static TaskProfile deferrable(TaskPriority priority, long maxDelayTicks) {
		return TaskProfile.deferrableTask(maxDelayTicks, priority);
	}

	@Test
	void priorityDecidesTheOrderAndSubmissionOrderDecidesTheRest() {
		AdaptiveScheduler<String> scheduler = scheduler(100);

		scheduler.submit("low-1", record("low-1"), deferrable(TaskPriority.LOW, 100L));
		scheduler.submit("normal-1", record("normal-1"), deferrable(TaskPriority.NORMAL, 100L));
		scheduler.submit("high-1", record("high-1"), deferrable(TaskPriority.HIGH, 100L));
		scheduler.submit("normal-2", record("normal-2"), deferrable(TaskPriority.NORMAL, 100L));
		scheduler.submit("high-2", record("high-2"), deferrable(TaskPriority.HIGH, 100L));

		assertEquals(5, scheduler.queued());
		assertEquals(5, scheduler.runTick(WHOLE_TICK));

		// Priority first, and inside one priority the older submission first.
		assertEquals(List.of("high-1", "high-2", "normal-1", "normal-2", "low-1"), executed);
		assertEquals(0, scheduler.queued());
	}

	@Test
	void theTimeBudgetStopsTheDrainButNeverTheQueue() {
		AdaptiveScheduler<String> scheduler = scheduler(100);

		// Each task advances the clock by 3 ms and the budget is checked before a task, not during
		// one: at 9 ms the fourth still starts, and only then is the 10 ms budget spent.
		for (int i = 0; i < 6; i++) {
			String name = "task-" + i;
			scheduler.submit(name, () -> {
				executed.add(name);
				nanos += 3_000_000L;
			}, deferrable(TaskPriority.NORMAL, 1000L));
		}

		assertEquals(4, scheduler.runTick(10_000_000L));
		assertEquals(List.of("task-0", "task-1", "task-2", "task-3"), executed);
		assertEquals(2, scheduler.queued(), "the rest waits, it is not lost");
	}

	@Test
	void aZeroBudgetRunsNothingUntilADeadlineArrives() {
		AdaptiveScheduler<String> scheduler = scheduler(100);
		scheduler.submit("later", record("later"), deferrable(TaskPriority.HIGH, 3L));

		assertEquals(0, scheduler.runTick(0L));
		assertEquals(0, scheduler.runTick(0L));
		assertTrue(executed.isEmpty(), "nothing may run while the tick has no room");

		// Submitted on tick 0 with a three-tick deadline: tick 3 is when it must run anyway, and
		// a budget of zero does not excuse the server from that (SPEC AC-6).
		assertEquals(1, scheduler.runTick(0L));
		assertEquals(List.of("later"), executed);
	}

	@Test
	void anExpiredTaskOutranksEveryPriority() {
		AdaptiveScheduler<String> scheduler = scheduler(100);

		scheduler.submit("low-expiring", record("low-expiring"), deferrable(TaskPriority.LOW, 2L));
		scheduler.runTick(0L);

		// The HIGH task is submitted later but the LOW one is already out of time, so starvation
		// protection runs it first (SPEC AC-6).
		scheduler.submit("high-fresh", record("high-fresh"), deferrable(TaskPriority.HIGH, 100L));

		assertEquals(2, scheduler.runTick(WHOLE_TICK));
		assertEquals(List.of("low-expiring", "high-fresh"), executed);
	}

	@Test
	void aLowPriorityTaskIsNotStarvedByAConstantStreamOfUrgentWork() {
		AdaptiveScheduler<String> scheduler = scheduler(100);
		scheduler.submit("starving", record("starving"), deferrable(TaskPriority.LOW, 5L));

		// One HIGH task per tick, and only enough budget for one task per tick.
		for (int tick = 0; tick < 5; tick++) {
			String name = "urgent-" + tick;
			scheduler.submit(name, () -> {
				executed.add(name);
				nanos += 2_000_000L;
			}, deferrable(TaskPriority.HIGH, 100L));
			scheduler.runTick(1_000_000L);
		}

		// Ticks 1..4 each ran one urgent task; on tick 5 the deadline fires regardless.
		assertEquals(List.of("urgent-0", "urgent-1", "urgent-2", "urgent-3", "starving"),
				executed.subList(0, 5));
	}

	@Test
	void deadlinesAreHonouredInDeadlineOrderNotSubmissionOrder() {
		AdaptiveScheduler<String> scheduler = scheduler(100);

		// The later submission expires first, which is exactly the case a single ordering by
		// priority or by arrival cannot serve.
		scheduler.submit("patient", record("patient"), deferrable(TaskPriority.HIGH, 10L));
		scheduler.submit("urgent-deadline", record("urgent-deadline"),
				deferrable(TaskPriority.LOW, 2L));

		assertEquals(0, scheduler.runTick(0L));
		assertEquals(1, scheduler.runTick(0L), "the two-tick deadline fires on tick 2");
		assertEquals(List.of("urgent-deadline"), executed);
	}

	@Test
	void criticalWorkRunsImmediatelyAndNeverEntersTheQueue() {
		AdaptiveScheduler<String> scheduler = scheduler(100);

		SubmitResult result = scheduler.submit("critical", record("critical"),
				TaskProfile.criticalTask());

		assertEquals(SubmitResult.EXECUTED_NOW, result);
		assertTrue(result.ran());
		assertEquals(List.of("critical"), executed, "it ran inside submit, not on the next tick");
		assertEquals(0, scheduler.queued());
		assertEquals(1L, scheduler.stats().executedNow());
		assertEquals(0L, scheduler.stats().deferred());
	}

	@Test
	void criticalWorkStillRunsWhenTheQueueIsCompletelyFull() {
		AdaptiveScheduler<String> scheduler = scheduler(1);
		scheduler.submit("filler", record("filler"), deferrable(TaskPriority.HIGH, 100L));
		assertEquals(1, scheduler.queued());

		// Nothing about a full queue can touch critical work: it is never queued, so it can be
		// neither dropped nor delayed (SPEC AC-6).
		assertEquals(SubmitResult.EXECUTED_NOW,
				scheduler.submit("critical", record("critical"), TaskProfile.criticalTask()));
		assertEquals(List.of("critical"), executed);
		assertEquals(1, scheduler.queued());
		assertEquals(0L, scheduler.stats().dropped());
	}

	@Test
	void nonDeferrableWorkRunsImmediatelyToo() {
		AdaptiveScheduler<String> scheduler = scheduler(100);

		assertEquals(SubmitResult.EXECUTED_NOW,
				scheduler.submit("now", record("now"), TaskProfile.immediate()));
		assertEquals(List.of("now"), executed);
		assertEquals(0, scheduler.queued());
	}

	@Test
	void aProfileThatIsBothCriticalAndDeferrableIsTreatedAsCritical() {
		AdaptiveScheduler<String> scheduler = scheduler(100);
		TaskProfile contradictory = TaskProfile.builder()
				.critical(true)
				.deferrable(true)
				.maxDelayTicks(100L)
				.build();

		assertFalse(contradictory.deferrable(), "the contradiction is resolved at construction");
		assertEquals(SubmitResult.EXECUTED_NOW,
				scheduler.submit("both", record("both"), contradictory));
		assertEquals(0, scheduler.queued());
	}

	@Test
	void theQueueNeverGrowsPastItsCap() {
		AdaptiveScheduler<String> scheduler = scheduler(3);

		for (int i = 0; i < 50; i++) {
			scheduler.submit("task-" + i, record("task-" + i),
					deferrable(TaskPriority.NORMAL, 1000L));
		}

		assertEquals(3, scheduler.queued());
		assertEquals(3, scheduler.stats().peakQueued());
	}

	@Test
	void anOverflowDropsTheLeastUrgentTaskForAMoreUrgentOne() {
		AdaptiveScheduler<String> scheduler = scheduler(2);

		scheduler.submit("low", record("low"), deferrable(TaskPriority.LOW, 1000L));
		scheduler.submit("normal", record("normal"), deferrable(TaskPriority.NORMAL, 1000L));
		assertEquals(SubmitResult.DEFERRED,
				scheduler.submit("high", record("high"), deferrable(TaskPriority.HIGH, 1000L)));

		assertEquals(2, scheduler.queued());
		assertEquals(1L, scheduler.stats().dropped());
		assertTrue(scheduler.isEmergency());

		scheduler.runTick(WHOLE_TICK);
		assertEquals(List.of("high", "normal"), executed, "the LOW task was the one dropped");
	}

	@Test
	void anOverflowRefusesWorkThatIsNotMoreUrgentThanWhatIsQueued() {
		AdaptiveScheduler<String> scheduler = scheduler(2);

		scheduler.submit("normal-1", record("normal-1"), deferrable(TaskPriority.NORMAL, 1000L));
		scheduler.submit("normal-2", record("normal-2"), deferrable(TaskPriority.NORMAL, 1000L));

		SubmitResult sameUrgency = scheduler.submit("normal-3", record("normal-3"),
				deferrable(TaskPriority.NORMAL, 1000L));
		SubmitResult lessUrgent = scheduler.submit("low", record("low"),
				deferrable(TaskPriority.LOW, 1000L));

		// Refusing rather than evicting an equal keeps submission order meaningful under pressure.
		assertEquals(SubmitResult.REJECTED_QUEUE_FULL, sameUrgency);
		assertEquals(SubmitResult.REJECTED_QUEUE_FULL, lessUrgent);
		assertTrue(sameUrgency.rejected(), "the caller is told the work is still theirs");
		assertEquals(2L, scheduler.stats().rejected());
		assertEquals(0L, scheduler.stats().dropped());

		scheduler.runTick(WHOLE_TICK);
		assertEquals(List.of("normal-1", "normal-2"), executed);
	}

	@Test
	void aRefusedTaskIsNotRunAndNotQueued() {
		AdaptiveScheduler<String> scheduler = scheduler(1);
		scheduler.submit("queued", record("queued"), deferrable(TaskPriority.HIGH, 1000L));
		scheduler.submit("refused", record("refused"), deferrable(TaskPriority.LOW, 1000L));

		scheduler.runTick(WHOLE_TICK);
		assertEquals(List.of("queued"), executed);
	}

	@Test
	void theEmergencyStateEndsWhenTheQueueDrainsBackToItsRecoveryMark() {
		AdaptiveScheduler<String> scheduler = scheduler(4);

		for (int i = 0; i < 8; i++) {
			scheduler.submit("task-" + i, record("task-" + i),
					deferrable(TaskPriority.NORMAL, 1000L));
		}

		assertTrue(scheduler.isEmergency());

		// Half the cap is the recovery mark, so one task has to leave before it clears.
		scheduler.runTick(0L);
		assertTrue(scheduler.isEmergency(), "a tick that ran nothing cannot be a recovery");

		nanos = 0L;
		scheduler.runTick(WHOLE_TICK);
		assertEquals(0, scheduler.queued());
		assertFalse(scheduler.isEmergency());
	}

	@Test
	void anOverflowIsReportedOncePerCooldown() {
		CountingEvents events = new CountingEvents();
		AdaptiveScheduler<String> scheduler = new AdaptiveScheduler<>(1, this::clock, events);
		scheduler.submit("keeper", record("keeper"), deferrable(TaskPriority.HIGH, 5000L));

		for (int i = 0; i < 100; i++) {
			scheduler.submit("refused-" + i, record("refused-" + i),
					deferrable(TaskPriority.LOW, 5000L));
		}

		assertEquals(1, events.overflows, "a full queue must not write 100 log lines");
		assertEquals(100L, scheduler.stats().rejected(), "but every refusal is still counted");

		// Past the cooldown the next one is reported again, so a problem that persists is not
		// silently forgotten either.
		for (long tick = 0; tick < AdaptiveScheduler.REPORT_COOLDOWN_TICKS; tick++) {
			scheduler.runTick(0L);
		}

		scheduler.submit("refused-again", record("refused-again"),
				deferrable(TaskPriority.LOW, 5000L));
		assertEquals(2, events.overflows);
	}

	@Test
	void aTaskThatThrowsIsCaughtCountedAndDoesNotStopTheQueue() {
		CountingEvents events = new CountingEvents();
		AdaptiveScheduler<String> scheduler = new AdaptiveScheduler<>(10, this::clock, events);

		scheduler.submit("boom", () -> {
			throw new IllegalStateException("a bug in someone else's mod");
		}, deferrable(TaskPriority.HIGH, 100L));
		scheduler.submit("after", record("after"), deferrable(TaskPriority.NORMAL, 100L));

		assertEquals(2, scheduler.runTick(WHOLE_TICK));
		assertEquals(List.of("after"), executed, "the queue kept going (SPEC INV-9)");
		assertEquals(1L, scheduler.stats().failed());
		assertEquals(1, events.failures);
		assertNotNull(events.lastFailure);
		assertEquals("boom", events.lastFailedTask);
	}

	@Test
	void aCoalescableTaskCollapsesIntoTheOneAlreadyQueued() {
		AdaptiveScheduler<String> scheduler = scheduler(10);
		TaskProfile profile = deferrable(TaskPriority.NORMAL, 100L).coalescing();

		assertEquals(SubmitResult.DEFERRED, scheduler.submit("dirty", record("first"), profile));
		assertEquals(SubmitResult.COALESCED, scheduler.submit("dirty", record("second"), profile));
		assertEquals(SubmitResult.COALESCED, scheduler.submit("dirty", record("third"), profile));

		assertEquals(1, scheduler.queued());
		scheduler.runTick(WHOLE_TICK);

		// Newest work, run exactly once: the semantics of "this is dirty again".
		assertEquals(List.of("third"), executed);
		assertEquals(2L, scheduler.stats().coalesced());
	}

	@Test
	void aCoalescableTaskCanBeQueuedAgainOnceItHasRun() {
		AdaptiveScheduler<String> scheduler = scheduler(10);
		TaskProfile profile = deferrable(TaskPriority.NORMAL, 100L).coalescing();

		scheduler.submit("dirty", record("first"), profile);
		scheduler.runTick(WHOLE_TICK);
		assertEquals(SubmitResult.DEFERRED, scheduler.submit("dirty", record("second"), profile));

		scheduler.runTick(WHOLE_TICK);
		assertEquals(List.of("first", "second"), executed);
	}

	@Test
	void switchingDeferralOffRunsEverythingImmediately() {
		AdaptiveScheduler<String> scheduler = scheduler(10);
		scheduler.setDeferralEnabled(false);

		// STRICT mode intervenes in nothing, and holding another mod's work back is the most
		// visible intervention there is (SPEC FR-11).
		assertEquals(SubmitResult.EXECUTED_NOW,
				scheduler.submit("task", record("task"), deferrable(TaskPriority.LOW, 100L)));
		assertEquals(List.of("task"), executed);
		assertEquals(0, scheduler.queued());
	}

	@Test
	void workQueuedBeforeDeferralWasSwitchedOffStillDrains() {
		AdaptiveScheduler<String> scheduler = scheduler(10);
		scheduler.submit("queued", record("queued"), deferrable(TaskPriority.NORMAL, 100L));
		scheduler.setDeferralEnabled(false);

		scheduler.runTick(WHOLE_TICK);
		assertEquals(List.of("queued"), executed, "a mode change must not strand queued work");
	}

	@Test
	void aTaskThatResubmitsItselfWaitsForTheNextTick() {
		AdaptiveScheduler<String> scheduler = scheduler(10);
		TaskProfile profile = deferrable(TaskPriority.HIGH, 0L);

		// maxDelayTicks 0 means "expired the moment it is queued", so without the per-tick
		// allowance this would spin inside one tick forever.
		Runnable[] resubmit = new Runnable[1];
		resubmit[0] = () -> {
			executed.add("run-" + executed.size());
			scheduler.submit("loop", resubmit[0], profile);
		};

		scheduler.submit("loop", resubmit[0], profile);
		assertEquals(1, scheduler.runTick(WHOLE_TICK));
		assertEquals(1, scheduler.runTick(WHOLE_TICK));
		assertEquals(List.of("run-0", "run-1"), executed);
	}

	@Test
	void shutdownDiscardsQueuedWorkWithoutRunningIt() {
		AdaptiveScheduler<String> scheduler = scheduler(10);
		scheduler.submit("a", record("a"), deferrable(TaskPriority.HIGH, 100L));
		scheduler.submit("b", record("b"), deferrable(TaskPriority.LOW, 100L));

		assertEquals(2, scheduler.discardQueued());
		assertEquals(0, scheduler.queued());
		assertTrue(executed.isEmpty(), "a world being torn down is no place to run other mods' work");
		assertEquals(2L, scheduler.stats().discarded());

		// And the emptied queue is still usable, rather than left in a half-torn state.
		scheduler.submit("c", record("c"), deferrable(TaskPriority.NORMAL, 100L));
		scheduler.runTick(WHOLE_TICK);
		assertEquals(List.of("c"), executed);
	}

	@Test
	void loweringTheCapAppliesImmediatelyAndDropsTheLeastUrgentWork() {
		AdaptiveScheduler<String> scheduler = scheduler(10);
		scheduler.submit("high", record("high"), deferrable(TaskPriority.HIGH, 100L));
		scheduler.submit("normal", record("normal"), deferrable(TaskPriority.NORMAL, 100L));
		scheduler.submit("low", record("low"), deferrable(TaskPriority.LOW, 100L));

		assertEquals(2, scheduler.setMaxQueued(1));
		assertEquals(1, scheduler.queued());

		scheduler.runTick(WHOLE_TICK);
		assertEquals(List.of("high"), executed);
	}

	@Test
	void raisingTheCapMakesRoomWithoutDisturbingTheQueue() {
		AdaptiveScheduler<String> scheduler = scheduler(1);
		scheduler.submit("first", record("first"), deferrable(TaskPriority.NORMAL, 100L));
		assertEquals(SubmitResult.REJECTED_QUEUE_FULL,
				scheduler.submit("second", record("second"), deferrable(TaskPriority.NORMAL, 100L)));

		assertEquals(0, scheduler.setMaxQueued(50));
		assertEquals(SubmitResult.DEFERRED,
				scheduler.submit("second", record("second"), deferrable(TaskPriority.NORMAL, 100L)));

		scheduler.runTick(WHOLE_TICK);
		assertEquals(List.of("first", "second"), executed);
	}

	@Test
	void aNullArgumentIsRefusedRatherThanThrown() {
		AdaptiveScheduler<String> scheduler = scheduler(10);

		assertEquals(SubmitResult.UNAVAILABLE,
				scheduler.submit(null, record("x"), TaskProfile.deferrableTask()));
		assertEquals(SubmitResult.UNAVAILABLE, scheduler.submit("id", null,
				TaskProfile.deferrableTask()));
		assertEquals(SubmitResult.UNAVAILABLE, scheduler.submit("id", record("x"), null));
		assertEquals(0, scheduler.queued());
	}

	@Test
	void statsCountEveryOutcomeExactlyOnce() {
		AdaptiveScheduler<String> scheduler = scheduler(2);

		scheduler.submit("now", record("now"), TaskProfile.immediate());
		scheduler.submit("high", record("high"), deferrable(TaskPriority.HIGH, 1L));
		scheduler.submit("normal", record("normal"), deferrable(TaskPriority.NORMAL, 100L));
		scheduler.submit("refused", record("refused"), deferrable(TaskPriority.LOW, 100L));
		scheduler.runTick(0L);
		scheduler.runTick(0L);

		SchedulerStats stats = scheduler.stats();
		assertFalse(stats.isUnused());
		assertEquals(4L, stats.submitted());
		assertEquals(1L, stats.executedNow());
		assertEquals(2L, stats.deferred());
		assertEquals(1L, stats.rejected());
		assertEquals(1L, stats.executedForced(), "only the one-tick deadline fired");
		assertEquals(0L, stats.executedBudgeted());
		assertEquals(1, stats.queued());
		assertEquals(2L, stats.ticks());
		assertEquals(1L, stats.lost());
	}

	@Test
	void aFreshSchedulerReportsItselfAsUnused() {
		assertTrue(scheduler(10).stats().isUnused());
	}

	@Test
	void theQueueSurvivesAThousandTasksOfMixedPrioritiesAndDeadlines() {
		AdaptiveScheduler<String> scheduler = scheduler(1000);
		TaskPriority[] priorities = TaskPriority.values();

		// Deadlines deliberately out of step with submission order, which is what the second index
		// exists for: entry 0 waits 100 ticks, entry 999 waits 1.
		for (int i = 0; i < 1000; i++) {
			scheduler.submit("task-" + i, record("task-" + i),
					deferrable(priorities[i % priorities.length], 100L - (i % 100L)));
		}

		assertEquals(1000, scheduler.queued());

		int ran = 0;

		for (int tick = 0; tick < 200 && scheduler.queued() > 0; tick++) {
			ran += scheduler.runTick(WHOLE_TICK);
		}

		assertEquals(1000, ran, "every task ran exactly once");
		assertEquals(1000, executed.size());
		assertEquals(1000, Set.copyOf(executed).size(), "and none of them ran twice");
	}

	/** An {@link AdaptiveScheduler.Events} that counts instead of logging. */
	private static final class CountingEvents implements AdaptiveScheduler.Events<String> {
		private int failures;
		private int overflows;
		private int recoveries;
		private String lastFailedTask;
		private Throwable lastFailure;

		@Override
		public void taskFailed(String taskId, Throwable failure, long totalFailures) {
			failures++;
			lastFailedTask = taskId;
			lastFailure = failure;
		}

		@Override
		public void overflow(int queued, int maxQueued, long totalDropped, long totalRejected) {
			overflows++;
		}

		@Override
		public void recovered(int queued, long totalDropped, long totalRejected) {
			recoveries++;
		}
	}
}
