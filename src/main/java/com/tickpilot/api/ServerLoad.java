package com.tickpilot.api;

/**
 * How loaded the server is, as reported to other mods (SPEC FR-5, FR-14).
 *
 * <p>This is the public mirror of TickPilot's internal load level. It exists as its own enum
 * because SPEC AC-14 requires that a consumer never has to import an internal class: the internal
 * one belongs to the budget subsystem and is free to change with it, while the constants here are
 * part of the published surface and will not.
 *
 * <p>The two are kept in step by a test that fails the build if a constant is added, removed or
 * renamed on either side, so the mirror cannot drift silently.
 *
 * <p>Declaration order runs from calm to worst and may be relied upon. A policy that wants "at
 * least HIGH" should compare ordinals rather than list constants, so that a level added later
 * does not silently fall outside the condition.
 */
public enum ServerLoad {
	/** Tick time is within the configured target. Nothing is wrong. */
	NORMAL,

	/** Tick time is over the target but far from critical. TickPilot diagnoses, it does not act. */
	ELEVATED,

	/** Tick time is closer to critical than to target. */
	HIGH,

	/** Tick time is at or above the configured critical budget. */
	CRITICAL;

	/**
	 * @param other the level to compare against
	 * @return {@code true} when this level is the same as {@code other} or worse
	 */
	public boolean isAtLeast(ServerLoad other) {
		return ordinal() >= other.ordinal();
	}
}
