package com.tickpilot.chunk;

import java.util.Arrays;

/**
 * The per-tick cap on optional chunk operations, and the two emergency releases that make it safe
 * (SPEC FR-10, AC-10, INV-8).
 *
 * <p>Pure state machine: no clock, no {@code net.minecraft} import, driven entirely by the tick
 * number and the calls the caller makes. Every rule below is unit-tested directly rather than
 * observed through a running server, which matters more here than anywhere else in the mod — the
 * failure this class must not have is a server thread that never comes back.
 *
 * <h2>Two ways the limit lifts itself</h2>
 * <ol>
 *   <li><b>Suspected block.</b> {@code ServerChunkCache.getChunk} blocks the server thread in
 *       {@code MainThreadExecutor.managedBlock} until the chunk it wants is ready, and that spin
 *       re-enters the chunk generation drain about every 100 &micro;s. So a run of drains that
 *       dispatch <em>nothing</em> while work is still pending is the signature of a thread waiting
 *       on something this class is holding.
 *       <p>What separates that from an idle server is time, not the number of drains. Between two
 *       ticks {@code MinecraftServer} polls chunk tasks in its spare time and produces hundreds of
 *       identical empty drains, all of them harmless; the difference is that a server which is
 *       still ticking refills the allowance and dispatches something within one tick period, while
 *       a blocked one never starts another tick. So the trigger is
 *       {@value #STALL_SUSPECT_MILLIS} ms without a single dispatch, which no server ticking faster
 *       than 1 TPS can reach — and a server slower than that has bigger problems than chunk
 *       generation and should not be capped either.
 *       <p>This is the backstop, not the main protection. The chunk a blocked thread is waiting for
 *       carries a {@code TicketType.UNKNOWN} ticket, which is classified as the highest priority
 *       there is and is never held back in the first place.</li>
 *   <li><b>Sustained saturation.</b> If the cap has held work back on {@value #SATURATED_TICKS_BEFORE_LIFT}
 *       consecutive ticks it is lifted for {@value #LIFT_DURATION_TICKS} ticks. At 20 TPS that is
 *       5 s of saturation buying 30 s of free running. The 5 s is the window
 *       {@code TickMetrics.averageMspt5s()} feeds the load level from: if the cap has been the
 *       binding constraint for as long as the window that decides the load level, then what is
 *       shaping the server's behaviour is the cap and not the load, and the cap has to step aside.
 *       The 30 s is the SPEC AC-16 log cooldown, so one lift produces at most one line.</li>
 * </ol>
 *
 * <h2>What it costs</h2>
 * One int compare and one array increment per operation, no allocation, no clock (SPEC INV-6).
 */
public final class ChunkBudget {
	/**
	 * How long chunk work may sit with nothing at all being dispatched before a block is suspected.
	 *
	 * <p>Not a config key on purpose: far too low and the cap never applies, far too high and the
	 * hang it exists to prevent comes back. Same reasoning as the {@code TickBudget} hysteresis and
	 * hold-time constants, which SPEC FR-15 also leaves out of the schema.
	 *
	 * <p>One second is twenty tick periods at 20 TPS, and a server that is still ticking dispatches
	 * something every tick, so this cannot fire on a healthy server however hard it is polling
	 * between ticks.
	 */
	public static final long STALL_SUSPECT_MILLIS = 1000L;

	/** Consecutive ticks with work held back before the cap lifts itself. 5 s at 20 TPS. */
	public static final int SATURATED_TICKS_BEFORE_LIFT = 100;

	/** How long a lift lasts. 30 s at 20 TPS, matching the SPEC AC-16 log cooldown. */
	public static final int LIFT_DURATION_TICKS = 600;

	/** Why the cap stopped applying. */
	public enum LiftReason {
		/** A drain kept coming back empty: something is probably waiting on a held operation. */
		SUSPECTED_BLOCK("tickpilot.chunklift.suspected_block"),
		/** The cap has been the binding constraint for longer than the load-level window. */
		SUSTAINED_SATURATION("tickpilot.chunklift.sustained_saturation");

		private final String translationKey;

		LiftReason(String translationKey) {
			this.translationKey = translationKey;
		}

		/** @return the {@code en_us.json} key explaining this release in command output */
		public String translationKey() {
			return translationKey;
		}
	}

	private final long[] dispatched = new long[ChunkOpClass.all().length];
	private final long[] held = new long[ChunkOpClass.all().length];

	private boolean enabled;
	private boolean intervening;
	private int maxOptionalPerTick = Integer.MAX_VALUE;

	private long tick;
	private int optionalDispatchedThisTick;
	private int heldThisTick;
	private long emptySinceNanos;
	private boolean emptyRunOpen;

	private long liftedUntilTick = Long.MIN_VALUE;
	private LiftReason liftReason;
	private long lifts;
	private long liftsSuspectedBlock;
	private long liftsSaturation;
	private boolean liftJustHappened;

	private int saturatedTicks;
	private long ticks;
	private long limitedTicks;
	private long drains;

	/**
	 * Applies the operator's settings. Called once per tick from the tick listener, because the
	 * mode and the load level are part of the answer and both change while the server runs.
	 *
	 * <h2>Why two flags and not one</h2>
	 * Classifying chunk work costs something, and SPEC INV-3 has the feature off by default, so with
	 * {@code enabled} false nothing is classified and nothing is counted — the whole subsystem is a
	 * static read. Once an operator switches it on, classification and the counters run at every
	 * load level, including NORMAL, so {@code status} can answer "where is chunk generation demand
	 * coming from" before the cap has ever held anything back. {@code intervening} is the narrower
	 * question of whether this mode acts at this load level (SPEC FR-11), and only it gates the
	 * holding back.
	 *
	 * @param enabled            {@code enable_chunk_budget} and {@code enable_adaptive_mode} both
	 *                           set, and the mode not STRICT
	 * @param intervening        additionally, the load level is one this mode intervenes at
	 * @param maxOptionalPerTick {@code max_chunk_operations_per_tick}
	 */
	public void configure(boolean enabled, boolean intervening, int maxOptionalPerTick) {
		this.enabled = enabled;
		this.intervening = intervening;
		this.maxOptionalPerTick = Math.max(0, maxOptionalPerTick);
	}

	/**
	 * @return whether chunk work is classified and counted at all. When {@code false} the caller
	 *         must not even ask for a classification (SPEC INV-10)
	 */
	public boolean isEnabled() {
		return enabled;
	}

	/**
	 * Opens a tick. Resets the per-tick allowance and retires an expired lift.
	 *
	 * @param gameTime the world's game time, used only as a monotonic tick counter
	 */
	public void beginTick(long gameTime) {
		this.tick = gameTime;
		this.optionalDispatchedThisTick = 0;
		this.heldThisTick = 0;
		this.liftJustHappened = false;
		this.ticks++;

		if (liftReason != null && gameTime >= liftedUntilTick) {
			liftReason = null;
		}
	}

	/**
	 * Closes a tick and applies the sustained-saturation rule.
	 *
	 * <p>Called after the last drain of the tick. A tick that held nothing back clears the run,
	 * because the rule is about the cap being <em>continuously</em> binding, not about it having
	 * been binding often.
	 */
	public void endTick() {
		if (heldThisTick > 0) {
			limitedTicks++;
			saturatedTicks++;

			if (saturatedTicks >= SATURATED_TICKS_BEFORE_LIFT) {
				lift(LiftReason.SUSTAINED_SATURATION);
				saturatedTicks = 0;
			}
		} else {
			saturatedTicks = 0;
		}
	}

	/** Opens one drain of the pending chunk generation tasks. */
	public void beginDrain() {
		drains++;
	}

	/**
	 * Closes one drain and applies the suspected-block rule.
	 *
	 * <p>The caller supplies the clock rather than this class reading one, for the same reason
	 * {@code TickBudget} takes its time as an argument: the rule is then exercised by a test that
	 * states the elapsed time instead of sleeping for it.
	 *
	 * @param dispatchedAny whether this drain let at least one operation through
	 * @param workPending   whether anything was held back and is still waiting
	 * @param nowNanos      {@link System#nanoTime()}; only read by the caller when something is
	 *                      actually being held, so a server that never holds anything never asks
	 *                      for the time at all (SPEC INV-6)
	 */
	public void endDrain(boolean dispatchedAny, boolean workPending, long nowNanos) {
		if (!workPending || dispatchedAny) {
			emptyRunOpen = false;
			return;
		}

		if (!emptyRunOpen) {
			emptyRunOpen = true;
			emptySinceNanos = nowNanos;
			return;
		}

		if (nowNanos - emptySinceNanos >= STALL_SUSPECT_MILLIS * 1_000_000L && isLimiting()) {
			lift(LiftReason.SUSPECTED_BLOCK);
		}
	}

	/**
	 * Decides one chunk operation and counts it. Hot path.
	 *
	 * @param opClass what the operation is for
	 * @return {@code true} to run it now, {@code false} to leave it for a later drain. Never
	 *         {@code false} for a class SPEC INV-8 protects, whatever the state of this object
	 */
	public boolean allow(ChunkOpClass opClass) {
		if (!opClass.isOptional() || !isLimiting()) {
			dispatched[opClass.ordinal()]++;
			return true;
		}

		if (optionalDispatchedThisTick < maxOptionalPerTick) {
			optionalDispatchedThisTick++;
			dispatched[opClass.ordinal()]++;
			return true;
		}

		held[opClass.ordinal()]++;
		heldThisTick++;
		return false;
	}

	/**
	 * @return whether the cap is currently in force: switched on, at a load level this mode acts
	 *         at, and not lifted by either emergency release
	 */
	public boolean isLimiting() {
		return enabled && intervening && liftReason == null;
	}

	/** @return why the cap is currently not applying, or {@code null} when it is */
	public LiftReason liftReason() {
		return liftReason;
	}

	/**
	 * @return {@code true} exactly once per lift, on the tick it happened. The caller turns that
	 *         into the single log line SPEC FR-10 asks for
	 */
	public boolean consumeLiftEvent() {
		boolean happened = liftJustHappened;
		liftJustHappened = false;
		return happened;
	}

	/** @return ticks left in the current lift, or 0 when the cap is applying */
	public long liftRemainingTicks() {
		return liftReason == null ? 0L : Math.max(0L, liftedUntilTick - tick);
	}

	private void lift(LiftReason reason) {
		// A suspected block during an existing lift is not a new event: the cap is already off, and
		// a second line in the log would describe the same situation twice.
		if (liftReason == null) {
			liftJustHappened = true;
			lifts++;

			if (reason == LiftReason.SUSPECTED_BLOCK) {
				liftsSuspectedBlock++;
			} else {
				liftsSaturation++;
			}
		}

		liftReason = reason;
		liftedUntilTick = tick + LIFT_DURATION_TICKS;
	}

	/** @return a consistent read-only view for {@code /tickpilot status} */
	public ChunkBudgetStats stats() {
		long[] dispatchedCopy = dispatched.clone();
		long[] heldCopy = held.clone();
		long totalDispatched = 0L;
		long totalHeld = 0L;

		for (int i = 0; i < dispatchedCopy.length; i++) {
			totalDispatched += dispatchedCopy[i];
			totalHeld += heldCopy[i];
		}

		return new ChunkBudgetStats(enabled, isLimiting(), maxOptionalPerTick, ticks, drains,
				limitedTicks, totalDispatched, totalHeld, dispatchedCopy, heldCopy, lifts,
				liftsSuspectedBlock, liftsSaturation, liftReason, liftRemainingTicks());
	}

	/** Clears every counter and every lift. Called when the server stops (SPEC AC-19). */
	public void reset() {
		Arrays.fill(dispatched, 0L);
		Arrays.fill(held, 0L);
		enabled = false;
		intervening = false;
		maxOptionalPerTick = Integer.MAX_VALUE;
		tick = 0L;
		optionalDispatchedThisTick = 0;
		heldThisTick = 0;
		emptySinceNanos = 0L;
		emptyRunOpen = false;
		liftedUntilTick = Long.MIN_VALUE;
		liftReason = null;
		lifts = 0L;
		liftsSuspectedBlock = 0L;
		liftsSaturation = 0L;
		liftJustHappened = false;
		saturatedTicks = 0;
		ticks = 0L;
		limitedTicks = 0L;
		drains = 0L;
	}
}
