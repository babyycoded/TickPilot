package com.tickpilot.api;

/**
 * How urgent a deferred task is relative to the other tasks waiting in the queue
 * (SPEC FR-6, FR-14).
 *
 * <p>Priority decides two things and nothing else:
 * <ul>
 *   <li>the order in which the scheduler drains the queue — every {@link #HIGH} task runs before
 *       any {@link #NORMAL} task, and within one priority the oldest submission runs first;</li>
 *   <li>which task is thrown away first when the queue is full (SPEC AC-6). The lowest priority
 *       is dropped first; a task is never dropped in favour of one that is equally or less
 *       urgent.</li>
 * </ul>
 *
 * <p>Priority does <em>not</em> decide whether a task may be deferred at all. That is
 * {@link TaskProfile#critical()}, and a critical task is never queued in the first place, whatever
 * its priority says.
 *
 * <p>A deadline always beats a priority: a task whose {@link TaskProfile#maxDelayTicks()} has
 * elapsed runs on the next tick regardless of how many more urgent tasks are waiting. That is what
 * keeps {@link #LOW} from meaning "never" on a permanently busy server (SPEC AC-6, starvation).
 *
 * <p>Declaration order is from most to least urgent, and both TickPilot and consumers may rely on
 * {@link Enum#ordinal()} reflecting that.
 */
public enum TaskPriority {
	/**
	 * Work a player is waiting for the result of, which is nevertheless safe to finish a few
	 * ticks late. Drained first and dropped last.
	 */
	HIGH,

	/** The default. Ordinary background work with no particular urgency. */
	NORMAL,

	/**
	 * Housekeeping that may sit in the queue for as long as its deadline allows: cache rebuilds,
	 * statistics, cleanup. Dropped first when the queue overflows.
	 */
	LOW;

	/**
	 * @param other the priority to compare against
	 * @return {@code true} when this priority is drained before {@code other}
	 */
	public boolean isMoreUrgentThan(TaskPriority other) {
		return ordinal() < other.ordinal();
	}
}
