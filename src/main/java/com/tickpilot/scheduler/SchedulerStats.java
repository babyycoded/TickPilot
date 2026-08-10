package com.tickpilot.scheduler;

/**
 * An immutable count of everything {@link AdaptiveScheduler} has done since the server started
 * (SPEC FR-6, AC-6). What {@code /tickpilot status} and {@code /tickpilot explain} print.
 *
 * <p>Taken only by query paths, never per tick, so it does not violate SPEC INV-6.
 *
 * @param queued           tasks waiting in the queue right now
 * @param peakQueued       the most tasks that were ever waiting at once
 * @param maxQueued        the configured cap, {@code max_deferred_tasks}
 * @param emergency        whether the scheduler is in the overflow state of AC-6, i.e. it has had
 *                         to drop or reject work and the queue has not drained back below its
 *                         recovery mark since
 * @param submitted        submissions seen, whatever happened to them afterwards
 * @param executedNow      submissions run immediately: critical, non-deferrable, or any submission
 *                         at all while deferral is switched off
 * @param deferred         submissions that entered the queue
 * @param coalesced        submissions folded into an already queued task of the same id
 * @param executedForced   queued tasks run because their {@code maxDelayTicks} had elapsed, i.e.
 *                         the starvation protection of AC-6 firing
 * @param executedBudgeted queued tasks run in priority order within the tick's time budget
 * @param dropped          queued tasks thrown away to make room for a more urgent submission
 * @param rejected         submissions refused because the queue was full of equally or more
 *                         urgent work
 * @param discarded        queued tasks thrown away unrun because the server stopped
 * @param failed           tasks that threw. TickPilot logs those with a cooldown and carries on
 *                         (SPEC INV-9)
 * @param spentNanos       total time spent running deferred work. This is other mods' time, not
 *                         TickPilot's own overhead, and is deliberately kept out of the INV-10
 *                         figure that {@code status} reports
 * @param ticks            ticks the scheduler has run for
 */
public record SchedulerStats(
		int queued,
		int peakQueued,
		int maxQueued,
		boolean emergency,
		long submitted,
		long executedNow,
		long deferred,
		long coalesced,
		long executedForced,
		long executedBudgeted,
		long dropped,
		long rejected,
		long discarded,
		long failed,
		long spentNanos,
		long ticks) {

	/** @return queued tasks that have been run, forced and budgeted together */
	public long executedFromQueue() {
		return executedForced + executedBudgeted;
	}

	/** @return work that was accepted or refused but never run: dropped, rejected or discarded */
	public long lost() {
		return dropped + rejected + discarded;
	}

	/**
	 * @return {@code true} when no mod has ever submitted anything, so every number here is zero
	 *         because the API is unused rather than because the queue is keeping up
	 */
	public boolean isUnused() {
		return submitted == 0L;
	}

	/** @return mean milliseconds per tick spent running deferred work, or 0 before the first tick */
	public double msptPerTick() {
		return ticks <= 0L ? 0.0 : (double) spentNanos / ticks / 1_000_000.0;
	}
}
