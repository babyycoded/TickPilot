package com.tickpilot.zones;

import java.util.Locale;

/**
 * How close an object is to the nearest player, which is what SPEC FR-7 uses to decide whether it
 * may be ticked less often.
 *
 * <p>A zone is a statement about distance and nothing else. It says what <em>may</em> be
 * considered, never what happens: an object in {@link #FROZEN} is still ticked in full unless the
 * mode, the load level and the operator's allowlist all separately agree (SPEC INV-5, FR-11).
 *
 * <p>Declaration order runs from closest to farthest and may be relied upon.
 */
public enum ActivityZone {
	/**
	 * Within {@code full_radius} of a player. Never thinned, whatever else is configured — this is
	 * the zone a player can see and interact with.
	 */
	FULL,

	/**
	 * Between {@code full_radius} and {@code reduced_radius}. Thinning is permitted here for
	 * allowlisted types only.
	 */
	REDUCED,

	/**
	 * Beyond {@code reduced_radius}. The strongest thinning permitted, still for allowlisted types
	 * only.
	 *
	 * <p>The name is vanilla's word for the far zone, not a promise that anything stops: SPEC AC-7
	 * forbids freezing a world just because no player is in it, and nothing here is frozen without
	 * the operator having listed the type by hand.
	 */
	FROZEN;

	/** @return the translation key used to render this zone in player-facing text */
	public String translationKey() {
		return "tickpilot.zone." + name().toLowerCase(Locale.ROOT);
	}

	/** @return {@code true} when this zone permits an allowlisted type to be thinned at all */
	public boolean permitsThinning() {
		return this != FULL;
	}
}
