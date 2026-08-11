package com.tickpilot.profiler;

import java.util.Arrays;

/**
 * Splits one server tick into the SPEC FR-2 categories without counting any nanosecond twice.
 *
 * <h2>The problem this exists to solve</h2>
 * The regions worth measuring are nested inside one another, verified against the 1.21.1 sources:
 *
 * <pre>
 * ServerChunkCache.tick            -> CHUNK_OPS
 *   ServerLevel.tickChunk          -> RANDOM_TICKS
 * ServerLevel.tickNonPassenger     -> ENTITIES
 *   ServerLevel.tickPassenger      -> ENTITIES   (recursive, for nested passengers)
 * Level.tickBlockEntities          -> BLOCK_ENTITIES
 *   BoundTickingBlockEntity.tick   -> BLOCK_ENTITIES
 * </pre>
 *
 * Timing each region with a plain start/stop pair and adding the results up would count a
 * passenger's time once inside its vehicle and once on its own, and would count every random tick
 * both as RANDOM_TICKS and as CHUNK_OPS. The sum would exceed the real tick time, which AC-2
 * forbids outright.
 *
 * <h2>How it is solved</h2>
 * A frame stack. Every frame remembers when it started and how much time its direct children have
 * used. On close, <em>self time</em> is {@code elapsed - childNanos}: that is what goes to the
 * frame's category and to the {@link CostSink}. The full {@code elapsed} is then added to the
 * parent's child total, so the parent excludes it in turn.
 *
 * <p>Two useful properties fall out of this for free. The sum of all self times equals the elapsed
 * time of the outermost frame, so categories can never overrun the region they came from. And a
 * passenger's cost lands on the passenger's own type rather than on the boat it is sitting in,
 * which is what FR-3 needs.
 *
 * <h2>Allocation</h2>
 * The four stack arrays are allocated once, in the constructor, and reused for the life of the
 * server (SPEC INV-6). {@link #begin} writes four array slots and increments an int;
 * {@link #end} reads them back. Nothing in the hot path allocates, boxes or grows.
 *
 * <h2>Threading</h2>
 * Everything except {@link #setEnabled} is server-thread only. {@code setEnabled} may be called
 * from a command, so the flag it sets is {@code volatile} and is only <em>applied</em> at a tick
 * boundary — flipping it mid-tick would leave a {@link #begin} without its {@link #end}.
 *
 * <p>No {@code net.minecraft} import, so the whole mechanism is unit-tested with a fake clock and
 * no game running (SPEC §8).
 */
public final class TickProfiler {
	/**
	 * Frame stack capacity. Entity passenger nesting is the only unbounded contributor — vanilla's
	 * {@code Entity.startRiding} guards against cycles but sets no depth limit — so the stack is
	 * bounded here and overflow is counted rather than allowed to grow or to throw.
	 */
	public static final int MAX_DEPTH = 16;

	private static final int NO_FRAME = -1;

	private final long[] frameStartNanos = new long[MAX_DEPTH];
	private final long[] frameChildNanos = new long[MAX_DEPTH];
	private final TickCategory[] frameCategory = new TickCategory[MAX_DEPTH];
	private final Object[] frameKey = new Object[MAX_DEPTH];

	private final long[] tickNanos = new long[TickCategory.COUNT];
	private final long[] sessionNanos = new long[TickCategory.COUNT];
	private final boolean[] available = new boolean[TickCategory.COUNT];

	private int depth = NO_FRAME;
	private int overflowDepth;

	private long sessionTicks;
	private long droppedFrames;
	private long unbalancedEnds;
	private long overrunTicks;
	private long abandonedFrames;

	private volatile boolean requestedEnabled;
	private boolean enabled;

	private CostSink costSink;

	/**
	 * Declares that a category has a working injection point. A category never marked available is
	 * reported as {@code n/a} instead of as zero (SPEC AC-2).
	 */
	public void markAvailable(TickCategory category) {
		available[category.ordinal()] = true;
	}

	/**
	 * Withdraws a category after its hook failed. Per SPEC INV-9 a broken subsystem steps aside;
	 * reporting {@code n/a} is honest, reporting the partial numbers it collected would not be.
	 */
	public void markUnavailable(TickCategory category) {
		available[category.ordinal()] = false;
	}

	/** @return whether {@code category} has a working injection point (SPEC AC-2) */
	public boolean isAvailable(TickCategory category) {
		return available[category.ordinal()];
	}

	/**
	 * Sets where per-type self times go (SPEC FR-3). {@code null} disables per-type accounting
	 * while leaving the category totals working.
	 */
	public void setCostSink(CostSink costSink) {
		this.costSink = costSink;
	}

	/**
	 * Requests that deep profiling start or stop. Takes effect at the next tick boundary, never
	 * mid-tick. Safe to call from the command thread.
	 */
	public void setEnabled(boolean enabled) {
		this.requestedEnabled = enabled;
	}

	/** @return whether deep profiling is running right now */
	public boolean isEnabled() {
		return enabled;
	}

	/**
	 * Opens a tick. Applies a pending {@link #setEnabled} and clears the per-tick accumulators.
	 *
	 * @param nowNanos {@link System#nanoTime()} at the start of the tick
	 */
	public void beginTick(long nowNanos) {
		// Applying the flag here, and only here, is what guarantees begin/end stay balanced: a
		// tick either profiles from its first frame to its last, or not at all.
		enabled = requestedEnabled;

		if (depth != NO_FRAME || overflowDepth != 0) {
			// A previous tick threw between begin and end. Reset rather than carry a corrupt stack.
			abandonedFrames += depth + 1L + overflowDepth;
			depth = NO_FRAME;
			overflowDepth = 0;
		}

		Arrays.fill(tickNanos, 0L);
	}

	/**
	 * Closes a tick and folds it into the session totals.
	 *
	 * @param totalNanos the whole tick as measured by {@code TickMetrics}, the TOTAL of AC-2
	 */
	public void endTick(long totalNanos) {
		if (!enabled) {
			return;
		}

		if (depth != NO_FRAME || overflowDepth != 0) {
			abandonedFrames += depth + 1L + overflowDepth;
			depth = NO_FRAME;
			overflowDepth = 0;
		}

		long measured = 0L;

		for (TickCategory category : TickCategory.all()) {
			if (category != TickCategory.TOTAL && category != TickCategory.OTHER) {
				measured += tickNanos[category.ordinal()];
				sessionNanos[category.ordinal()] += tickNanos[category.ordinal()];
			}
		}

		if (measured > totalNanos) {
			// Should be impossible: every measured region is inside the tick. Counted rather than
			// hidden, because a non-zero value here means a hook is wrong and the report is a lie.
			overrunTicks++;
		}

		sessionNanos[TickCategory.TOTAL.ordinal()] += totalNanos;
		sessionNanos[TickCategory.OTHER.ordinal()] += Math.max(0L, totalNanos - measured);
		sessionTicks++;
	}

	/**
	 * Opens a frame. Hot path.
	 *
	 * @param category the category this frame's self time belongs to
	 * @param key      identity for per-type accounting (SPEC FR-3), or {@code null} for none
	 * @param nowNanos {@link System#nanoTime()}
	 */
	public void begin(TickCategory category, Object key, long nowNanos) {
		if (!enabled) {
			return;
		}

		if (depth + 1 >= MAX_DEPTH) {
			// Out of stack. The frame is still counted as open so that its end() is matched, and
			// its time simply stays inside its parent's self time instead of being lost.
			overflowDepth++;
			droppedFrames++;
			return;
		}

		depth++;
		frameStartNanos[depth] = nowNanos;
		frameChildNanos[depth] = 0L;
		frameCategory[depth] = category;
		frameKey[depth] = key;
	}

	/**
	 * Closes the innermost frame, charging its self time to that frame's category. Hot path.
	 *
	 * @param nowNanos {@link System#nanoTime()}
	 */
	public void end(long nowNanos) {
		if (!enabled) {
			return;
		}

		if (overflowDepth > 0) {
			overflowDepth--;
			return;
		}

		if (depth == NO_FRAME) {
			unbalancedEnds++;
			return;
		}

		long elapsed = nowNanos - frameStartNanos[depth];

		if (elapsed < 0L) {
			elapsed = 0L;
		}

		long self = elapsed - frameChildNanos[depth];

		if (self < 0L) {
			self = 0L;
		}

		TickCategory category = frameCategory[depth];
		tickNanos[category.ordinal()] += self;

		Object key = frameKey[depth];

		if (key != null && costSink != null) {
			costSink.record(category, key, self);
		}

		// Release the reference so a dead entity cannot be held alive by the stack (INV-7).
		frameKey[depth] = null;
		depth--;

		if (depth >= 0) {
			frameChildNanos[depth] += elapsed;
		}
	}

	/** @return nanoseconds charged to {@code category} in the tick currently being measured */
	public long tickNanos(TickCategory category) {
		return tickNanos[category.ordinal()];
	}

	/** @return nanoseconds charged to {@code category} since the last {@link #resetSession} */
	public long sessionNanos(TickCategory category) {
		return sessionNanos[category.ordinal()];
	}

	/** @return how many ticks the current session has folded in */
	public long sessionTicks() {
		return sessionTicks;
	}

	/**
	 * The costliest category measured this session — what {@code /tickpilot explain} calls the main
	 * cost and what the client HUD calls the main load source (SPEC AC-13, FR-20).
	 *
	 * <p>Two categories are excluded, for opposite reasons. {@link TickCategory#TOTAL} is the whole
	 * tick rather than a part of it, so it would always win and say nothing. A category with no
	 * working hook behind it is excluded so that it cannot win by being zero — SPEC AC-2 says an
	 * unmeasured category is {@code n/a}, not free. {@link TickCategory#OTHER} is kept even though
	 * it has no hook, because it is derived from TOTAL minus everything measured and is therefore
	 * always meaningful; "most of the tick is in something TickPilot cannot see" is a real answer.
	 *
	 * <p>Lives here rather than in the command so that the HUD and {@code explain} cannot drift into
	 * two copies of the rule.
	 *
	 * @return the costliest category, or {@code null} when no session has folded in a tick yet
	 */
	public TickCategory dominantCategory() {
		if (sessionTicks == 0L) {
			return null;
		}

		TickCategory dominant = null;
		long dominantNanos = -1L;

		for (TickCategory category : TickCategory.all()) {
			if (category == TickCategory.TOTAL) {
				continue;
			}

			if (category != TickCategory.OTHER && !isAvailable(category)) {
				continue;
			}

			long nanos = sessionNanos(category);

			if (nanos > dominantNanos) {
				dominantNanos = nanos;
				dominant = category;
			}
		}

		return dominant;
	}

	/** @return frames dropped because the stack was full; non-zero means MAX_DEPTH is too small */
	public long droppedFrames() {
		return droppedFrames;
	}

	/** @return {@code end} calls with no open frame; non-zero means a hook is unbalanced */
	public long unbalancedEnds() {
		return unbalancedEnds;
	}

	/** @return frames left open by a throwing tick; non-zero means a hook leaks on an exception */
	public long abandonedFrames() {
		return abandonedFrames;
	}

	/** @return ticks whose measured categories exceeded TOTAL; must stay zero (SPEC AC-2) */
	public long overrunTicks() {
		return overrunTicks;
	}

	/** @return {@code true} while every self-check counter is still zero */
	public boolean isConsistent() {
		return droppedFrames == 0L && unbalancedEnds == 0L && abandonedFrames == 0L
				&& overrunTicks == 0L;
	}

	/** @return the current depth of the frame stack; 0 when no frame is open */
	public int depth() {
		return depth + 1 + overflowDepth;
	}

	/** Clears the session totals and the self-check counters. Keeps availability. */
	public void resetSession() {
		Arrays.fill(sessionNanos, 0L);
		Arrays.fill(tickNanos, 0L);
		Arrays.fill(frameKey, null);
		sessionTicks = 0L;
		droppedFrames = 0L;
		unbalancedEnds = 0L;
		overrunTicks = 0L;
		abandonedFrames = 0L;
		depth = NO_FRAME;
		overflowDepth = 0;
	}
}
