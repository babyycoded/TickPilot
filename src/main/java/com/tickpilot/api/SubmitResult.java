package com.tickpilot.api;

/**
 * What {@link TickPilotApi#submit} actually did with a piece of work (SPEC FR-6, FR-14).
 *
 * <p>{@code submit} never throws and never fails silently: whatever happens, the caller is told
 * which of these it was, and can decide for itself whether that is acceptable. A mod that must
 * know its work ran can check {@link #ran()}.
 */
public enum SubmitResult {
	/**
	 * The work has already run, on the calling thread, before {@code submit} returned. This is
	 * what a {@link TaskProfile#critical()} or non-{@link TaskProfile#deferrable()} profile means,
	 * and also what happens while TickPilot is in STRICT mode, where it intervenes in nothing
	 * (SPEC FR-11).
	 */
	EXECUTED_NOW,

	/**
	 * The work has already run, as {@link #EXECUTED_NOW}, but no profile was ever registered for
	 * this task id. TickPilot treated it as immediate rather than guessing that it was safe to
	 * delay. Register a {@link TaskProfile} to get the deferral you presumably wanted; this
	 * constant exists so the omission is detectable in code and not only in the log.
	 */
	EXECUTED_NOW_NO_PROFILE,

	/** The work is queued and will run within {@link TaskProfile#maxDelayTicks()} ticks. */
	DEFERRED,

	/**
	 * The work replaced an already queued submission of the same task id, because the profile is
	 * {@link TaskProfile#coalescable()}. The queued position and deadline of the earlier
	 * submission are kept; only the newest work object survives, and it runs exactly once.
	 */
	COALESCED,

	/**
	 * The queue is full and every task in it is at least as urgent as this one, so nothing was
	 * queued and nothing ran (SPEC AC-6, the overflow case). The work was <em>not</em> executed —
	 * a mod that cannot lose it should run it itself when it sees this.
	 */
	REJECTED_QUEUE_FULL,

	/**
	 * {@code submit} was called from a thread other than the server thread. Nothing ran and
	 * nothing was queued.
	 *
	 * <p>TickPilot refuses rather than helps here on purpose. Running the work on the calling
	 * thread would touch the world off the server thread (SPEC INV-1, INV-2), and queueing it
	 * would mean writing into a queue that is single-threaded by construction. Both would turn a
	 * mistake in another mod into memory corruption in this one. Submit from the server thread —
	 * that is where the work is going to run in any case.
	 */
	WRONG_THREAD,

	/**
	 * TickPilot could not take the work: no server is running, TickPilot has disabled itself on
	 * this server after an internal failure (SPEC INV-9), or the arguments were unusable — a
	 * {@code null} task id or {@code null} work is reported in the log rather than thrown, because
	 * an exception raised inside a tick would take the server down over another mod's mistake.
	 *
	 * <p>Nothing ran and nothing was queued; do the work yourself.
	 */
	UNAVAILABLE;

	/**
	 * @return {@code true} when the work has already been executed by the time {@code submit}
	 *         returned
	 */
	public boolean ran() {
		return this == EXECUTED_NOW || this == EXECUTED_NOW_NO_PROFILE;
	}

	/**
	 * @return {@code true} when the work is now TickPilot's responsibility and will run later,
	 *         either as itself or folded into an earlier submission
	 */
	public boolean queued() {
		return this == DEFERRED || this == COALESCED;
	}

	/**
	 * @return {@code true} when TickPilot neither ran nor accepted the work, so the caller still
	 *         owns it
	 */
	public boolean rejected() {
		return !ran() && !queued();
	}
}
