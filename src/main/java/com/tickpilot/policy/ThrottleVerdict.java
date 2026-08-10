package com.tickpilot.policy;

/**
 * What {@link TickPolicy} decided about one object, and why (SPEC FR-7, FR-8, FR-9, FR-11).
 *
 * <p>An enum rather than a record because this is produced once per object per tick: enum
 * constants are singletons, so carrying the reason alongside the decision costs no allocation
 * (SPEC INV-6). The reason is what makes the diagnostic output of this phase worth reading — "no
 * objects were thinned" is not information, "4 812 were in range and allowlisted but the server was
 * never above ELEVATED" is.
 *
 * <p>Only the two {@code ELIGIBLE_*} constants mean anything may be skipped, and even they are a
 * statement about permission rather than about what happened.
 */
public enum ThrottleVerdict {
	/** Adaptive behaviour is switched off entirely ({@code enable_adaptive_mode = false}). */
	TICK_ADAPTIVE_DISABLED,

	/** STRICT mode. The compatibility mode intervenes in nothing at all (SPEC AC-11). */
	TICK_STRICT_MODE,

	/**
	 * The object is protected from thinning regardless of everything else: a force-loaded chunk, a
	 * vehicle or its passenger, something on a lead, a persistent or named entity, an entity
	 * vanilla itself marks as always ticking, or a type the operator excluded by id or by mod
	 * namespace (SPEC AC-7, INV-8).
	 *
	 * <p>A veto from another mod's {@code ThrottlePolicy} will land here too, and does not yet:
	 * asking every registered policy per object per tick needs a cache to be affordable, and it
	 * changes nothing while nothing is thinned. It is wired in the half of this phase that actually
	 * skips ticks, where a veto is the difference between skipping and not.
	 */
	TICK_PROTECTED,

	/** The type is on the operator's denylist, which outranks the allowlist (SPEC AC-9). */
	TICK_DENYLISTED,

	/** The object is close enough to a player to be in {@link com.tickpilot.zones.ActivityZone#FULL}. */
	TICK_IN_FULL_ZONE,

	/**
	 * The type is not on the operator's throttle allowlist. The default state for every type in
	 * the game, and the reason SPEC INV-5 is satisfied by construction rather than by care.
	 */
	TICK_NOT_ALLOWLISTED,

	/** The server is not loaded enough for this mode to intervene (SPEC FR-11). */
	TICK_LOAD_TOO_LOW,

	/** Everything agrees, and the object is in the REDUCED zone. */
	ELIGIBLE_REDUCED,

	/** Everything agrees, and the object is in the FROZEN zone. */
	ELIGIBLE_FROZEN;

	/** Cached because {@code values()} clones its array on every call. */
	private static final ThrottleVerdict[] ALL = values();

	/** @return every verdict, without the defensive copy {@code values()} makes */
	public static ThrottleVerdict[] all() {
		return ALL;
	}

	/**
	 * @return {@code true} when the policy permits this object to be thinned. In the diagnostic
	 *         half of SPEC FR-8/FR-9 nothing acts on this — it is counted and reported
	 */
	public boolean isEligible() {
		return this == ELIGIBLE_REDUCED || this == ELIGIBLE_FROZEN;
	}

	/** @return the translation key used to render this verdict in player-facing text */
	public String translationKey() {
		return "tickpilot.verdict." + name().toLowerCase(java.util.Locale.ROOT);
	}
}
