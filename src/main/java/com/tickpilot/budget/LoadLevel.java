package com.tickpilot.budget;

/**
 * How loaded the server currently is, derived from smoothed MSPT by {@link TickBudget}
 * (SPEC FR-5).
 *
 * <p>Not to be confused with the adaptive mode (STRICT / BALANCED / AGGRESSIVE, SPEC FR-11):
 * the mode says how far the mod is <em>allowed</em> to intervene and is chosen by a human,
 * this enum says how bad things currently <em>are</em> and is computed. Ordinals are ordered
 * from calm to worst and code may rely on that ordering.
 */
public enum LoadLevel {
	/** Tick time is within the target budget. No intervention is warranted. */
	NORMAL,

	/** Tick time exceeds the target but is far from critical. Diagnostics only. */
	ELEVATED,

	/** Tick time is closer to critical than to target. Policies may act in BALANCED mode. */
	HIGH,

	/** Tick time is at or above the critical budget. */
	CRITICAL;

	/** @return the translation key used to render this level in player-facing text */
	public String translationKey() {
		return "tickpilot.load_level." + name().toLowerCase(java.util.Locale.ROOT);
	}
}
