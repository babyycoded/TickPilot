package com.tickpilot.api;

/**
 * What another mod tells TickPilot about one kind of task (SPEC FR-14).
 *
 * <p>A profile is registered once per task id with
 * {@link TickPilotApi#registerTaskProfile(net.minecraft.resources.ResourceLocation, TaskProfile)}
 * and describes the <em>kind</em> of work, not one occurrence of it. Every call to
 * {@link TickPilotApi#submit} for that id is then handled according to this profile.
 *
 * <p>The profile is a promise made by the registering mod, and TickPilot has no way to check it.
 * Marking work {@link #deferrable()} that another system expects to have finished within the same
 * tick will produce a bug in that mod, not in TickPilot. When in doubt, do not defer.
 *
 * <h2>Contradictions are normalised, never thrown</h2>
 * An invalid combination is corrected towards the safe reading instead of raising an exception:
 * a mod's initialiser throwing would take the whole server down, which is exactly what SPEC INV-9
 * forbids TickPilot from causing. Specifically:
 * <ul>
 *   <li>{@code critical} and {@code deferrable} together become non-deferrable — "never delay
 *       this" is the conservative half of the contradiction;</li>
 *   <li>a negative {@code maxDelayTicks} becomes {@code 0}, i.e. run on the next tick;</li>
 *   <li>{@code maxDelayTicks} above {@link #MAX_DELAY_TICKS} is clamped to it, so that the
 *       starvation guarantee of SPEC AC-6 cannot be opted out of by naming a huge number;</li>
 *   <li>a {@code null} priority becomes {@link TaskPriority#NORMAL}.</li>
 * </ul>
 * Normalisation happens in the constructor, so the accessors always report what will actually
 * happen: a profile built with {@code critical(true).deferrable(true)} answers {@code false} to
 * {@link #deferrable()} straight away. Assert on the accessors if you want to be sure of what you
 * registered — there is no hidden second value.
 *
 * @param deferrable          whether the work may run later than the tick it was submitted in. A
 *                            profile that is not deferrable runs immediately inside
 *                            {@link TickPilotApi#submit}, exactly as calling the work directly
 *                            would have
 * @param maxDelayTicks       how many ticks the work may wait at most. Once that many ticks have
 *                            passed the scheduler runs it on the next tick regardless of priority
 *                            and regardless of how loaded the server is (SPEC AC-6). {@code 0}
 *                            means "the next tick at the latest"
 * @param critical            whether the work must never be delayed at all. A critical task is
 *                            executed inside {@link TickPilotApi#submit} and never enters the
 *                            queue, so it cannot be dropped when the queue overflows either
 *                            (SPEC AC-6). Use it for anything a player is blocked on
 * @param asyncComputeAllowed whether the <em>pure computation</em> part of the work could safely
 *                            run off the server thread. TickPilot does not run anything off the
 *                            server thread today; see the class documentation of
 *                            {@link TickPilotApi} for what this flag currently does and does not
 *                            mean
 * @param coalescable         whether two submissions of the same task id that are queued at the
 *                            same time may collapse into one. The newest work object wins and the
 *                            oldest queue position and deadline are kept — the semantics of
 *                            "this thing is dirty again", not of "do it twice"
 * @param priority            drain order and drop order relative to the other queued tasks
 */
public record TaskProfile(
		boolean deferrable,
		long maxDelayTicks,
		boolean critical,
		boolean asyncComputeAllowed,
		boolean coalescable,
		TaskPriority priority) {

	/**
	 * The longest delay a profile may ask for: 6000 ticks, five minutes at 20 TPS.
	 *
	 * <p>SPEC AC-6 requires that every deferred task eventually runs whether the server recovers
	 * or not. A profile is allowed to name its own deadline, but not to name one so far away that
	 * the guarantee stops meaning anything, so this is the ceiling.
	 */
	public static final long MAX_DELAY_TICKS = 6000L;

	/** Default deadline for the convenience factories: 20 ticks, one second at 20 TPS. */
	public static final long DEFAULT_MAX_DELAY_TICKS = 20L;

	/** Applies the normalisation described in the class documentation. */
	public TaskProfile {
		if (priority == null) {
			priority = TaskPriority.NORMAL;
		}

		if (critical) {
			deferrable = false;
		}

		if (maxDelayTicks < 0L) {
			maxDelayTicks = 0L;
		} else if (maxDelayTicks > MAX_DELAY_TICKS) {
			maxDelayTicks = MAX_DELAY_TICKS;
		}
	}

	/**
	 * A profile for work that must run in the tick it was submitted in and must never be dropped
	 * (SPEC AC-6: "critical tasks are never deferred").
	 *
	 * @return a critical, non-deferrable profile at {@link TaskPriority#HIGH}
	 */
	public static TaskProfile criticalTask() {
		return new TaskProfile(false, 0L, true, false, false, TaskPriority.HIGH);
	}

	/**
	 * A profile for work that runs immediately but is not marked critical — the behaviour a mod
	 * gets without TickPilot at all. Useful while migrating: register this first, confirm nothing
	 * changed, then relax it.
	 *
	 * @return a non-deferrable, non-critical profile at {@link TaskPriority#NORMAL}
	 */
	public static TaskProfile immediate() {
		return new TaskProfile(false, 0L, false, false, false, TaskPriority.NORMAL);
	}

	/**
	 * A profile for ordinary deferrable work at {@link TaskPriority#NORMAL} with the default
	 * one-second deadline.
	 *
	 * @return a deferrable profile
	 */
	public static TaskProfile deferrableTask() {
		return deferrableTask(DEFAULT_MAX_DELAY_TICKS, TaskPriority.NORMAL);
	}

	/**
	 * A profile for deferrable work with an explicit deadline and priority.
	 *
	 * @param maxDelayTicks how long the work may wait; see {@link #maxDelayTicks()}
	 * @param priority      drain and drop order; see {@link TaskPriority}
	 * @return a deferrable profile
	 */
	public static TaskProfile deferrableTask(long maxDelayTicks, TaskPriority priority) {
		return new TaskProfile(true, maxDelayTicks, false, false, false, priority);
	}

	/**
	 * @return a builder starting from a deferrable {@link TaskPriority#NORMAL} profile with the
	 *         default deadline
	 */
	public static Builder builder() {
		return new Builder();
	}

	/** @return this profile with {@link #coalescable()} set to {@code true} */
	public TaskProfile coalescing() {
		return new TaskProfile(deferrable, maxDelayTicks, critical, asyncComputeAllowed, true,
				priority);
	}

	/**
	 * @return this profile with {@link #asyncComputeAllowed()} set to {@code true}. Read the
	 *         documentation of that flag before using it — it does not make anything run off the
	 *         server thread today
	 */
	public TaskProfile allowingAsyncCompute() {
		return new TaskProfile(deferrable, maxDelayTicks, critical, true, coalescable, priority);
	}

	/**
	 * Fluent builder for {@link TaskProfile}, for the cases where the six-argument constructor
	 * would be unreadable at the call site.
	 *
	 * <p>Not thread-safe; build the profile in the initialiser of the registering mod and register
	 * it once.
	 */
	public static final class Builder {
		private boolean deferrable = true;
		private long maxDelayTicks = DEFAULT_MAX_DELAY_TICKS;
		private boolean critical;
		private boolean asyncComputeAllowed;
		private boolean coalescable;
		private TaskPriority priority = TaskPriority.NORMAL;

		private Builder() {
		}

		/**
		 * @param deferrable see {@link TaskProfile#deferrable()}
		 * @return this builder
		 */
		public Builder deferrable(boolean deferrable) {
			this.deferrable = deferrable;
			return this;
		}

		/**
		 * @param maxDelayTicks see {@link TaskProfile#maxDelayTicks()}
		 * @return this builder
		 */
		public Builder maxDelayTicks(long maxDelayTicks) {
			this.maxDelayTicks = maxDelayTicks;
			return this;
		}

		/**
		 * @param critical see {@link TaskProfile#critical()}
		 * @return this builder
		 */
		public Builder critical(boolean critical) {
			this.critical = critical;
			return this;
		}

		/**
		 * @param asyncComputeAllowed see {@link TaskProfile#asyncComputeAllowed()}
		 * @return this builder
		 */
		public Builder asyncComputeAllowed(boolean asyncComputeAllowed) {
			this.asyncComputeAllowed = asyncComputeAllowed;
			return this;
		}

		/**
		 * @param coalescable see {@link TaskProfile#coalescable()}
		 * @return this builder
		 */
		public Builder coalescable(boolean coalescable) {
			this.coalescable = coalescable;
			return this;
		}

		/**
		 * @param priority see {@link TaskProfile#priority()}
		 * @return this builder
		 */
		public Builder priority(TaskPriority priority) {
			this.priority = priority;
			return this;
		}

		/** @return the profile, with the normalisation of {@link TaskProfile} applied */
		public TaskProfile build() {
			return new TaskProfile(deferrable, maxDelayTicks, critical, asyncComputeAllowed,
					coalescable, priority);
		}
	}
}
