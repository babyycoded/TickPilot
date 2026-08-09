package com.tickpilot.budget;

/**
 * One accepted load level change, returned by {@link TickBudget#update(double, long)}.
 *
 * <p>{@code TickBudget} does not log: it is a plain Java class so it can be unit-tested without
 * Minecraft, and returning the transition lets the caller log it exactly once, which is what
 * SPEC AC-5 asks for. A transition object is allocated only when the level actually changes —
 * a handful of times per session, not per tick (SPEC INV-6).
 *
 * @param from     level held before the change
 * @param to       level held after the change
 * @param avgMspt  smoothed MSPT that caused it
 * @param atMillis wall-clock timestamp passed to {@code update}
 */
public record LoadLevelTransition(LoadLevel from, LoadLevel to, double avgMspt, long atMillis) {

	/** @return {@code true} when the server got worse rather than better */
	public boolean isEscalation() {
		return to.ordinal() > from.ordinal();
	}
}
