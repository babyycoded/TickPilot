package com.tickpilot.chunk;

/**
 * A read-only view of {@link ChunkBudget} for {@code /tickpilot status} (SPEC FR-10, FR-12).
 *
 * <p>Built on the command path, never per tick. The two arrays are indexed by
 * {@link ChunkOpClass#ordinal()} and are already copies, so nothing here shares state with the
 * budget that produced it.
 *
 * @param enabled            whether the cap is switched on
 * @param limiting           whether it is actually applying right now, i.e. enabled and not lifted
 * @param maxOptionalPerTick the configured cap
 * @param ticks              ticks the budget has seen
 * @param drains             chunk generation drains it has been asked about
 * @param limitedTicks       ticks on which at least one operation was held back
 * @param dispatched         operations let through, all classes
 * @param held               operations held for a later drain, all classes
 * @param dispatchedByClass  operations let through, per {@link ChunkOpClass#ordinal()}
 * @param heldByClass        operations held back, per {@link ChunkOpClass#ordinal()}
 * @param lifts              how many times the cap has lifted itself
 * @param liftsSuspectedBlock lifts caused by a drain that kept coming back empty
 * @param liftsSaturation    lifts caused by sustained saturation
 * @param liftReason         why the cap is not applying, or {@code null} when it is
 * @param liftRemainingTicks ticks left in the current lift
 */
public record ChunkBudgetStats(
		boolean enabled,
		boolean limiting,
		int maxOptionalPerTick,
		long ticks,
		long drains,
		long limitedTicks,
		long dispatched,
		long held,
		long[] dispatchedByClass,
		long[] heldByClass,
		long lifts,
		long liftsSuspectedBlock,
		long liftsSaturation,
		ChunkBudget.LiftReason liftReason,
		long liftRemainingTicks) {

	/** @return whether the budget has never seen a chunk operation, so there is nothing to report */
	public boolean isUnused() {
		return dispatched == 0L && held == 0L;
	}

	/**
	 * @param opClass the class to count
	 * @return operations of that class let through
	 */
	public long dispatched(ChunkOpClass opClass) {
		return dispatchedByClass[opClass.ordinal()];
	}

	/**
	 * @param opClass the class to count
	 * @return operations of that class held back for a later drain
	 */
	public long held(ChunkOpClass opClass) {
		return heldByClass[opClass.ordinal()];
	}

	/**
	 * The SPEC INV-8 check, as one boolean.
	 *
	 * @return whether anything a player was waiting for has ever been held back. Must be
	 *         {@code false} on every server, always; it is the number the manual teleport scenario
	 *         in the README reads
	 */
	public boolean heldPlayerCritical() {
		for (ChunkOpClass opClass : ChunkOpClass.all()) {
			if (!opClass.isOptional() && held(opClass) > 0L) {
				return true;
			}
		}

		return false;
	}
}
