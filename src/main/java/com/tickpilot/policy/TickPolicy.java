package com.tickpilot.policy;

import com.tickpilot.budget.LoadLevel;
import com.tickpilot.config.AdaptiveMode;
import com.tickpilot.zones.ActivityZone;

/**
 * The single place that decides whether an object may be ticked less often (SPEC FR-7, FR-8, FR-9,
 * FR-11, INV-5).
 *
 * <p>A pure function of seven values, with no state, no clock and no {@code net.minecraft} import,
 * so every rule below is unit-tested exhaustively rather than by sampling. In particular the
 * guarantee SPEC AC-11 makes about STRICT — that it disables every intervention — is checked by
 * walking the whole input space, not by testing the cases somebody thought of.
 *
 * <h2>Order of the checks</h2>
 * The checks are ordered so that the reason reported is the most fundamental one that applies, and
 * that order is part of the contract because it decides what the diagnostic counters say:
 * <ol>
 *   <li>adaptive behaviour off, then STRICT — whole-server switches, and if either is set no other
 *       reason is worth reporting;</li>
 *   <li>the object is protected, then the type is denylisted — safety rules that hold whatever the
 *       load is (SPEC INV-8, AC-9);</li>
 *   <li>the zone, then the allowlist — "not near anything" and "not opted in" are the two answers
 *       an operator most often needs to see;</li>
 *   <li>the load level last, so that "everything agrees except that the server is healthy" is
 *       distinguishable from every other kind of no. That is the count which says what thinning
 *       <em>would</em> do if the server ever got busy.</li>
 * </ol>
 *
 * <h2>Nothing here can widen SPEC INV-5</h2>
 * {@code allowlisted} is the operator's list from the config. There is no code path that reaches
 * an {@code ELIGIBLE} verdict without it, so a type nobody listed is never thinned no matter what
 * another mod claims through the API or how loaded the server is.
 */
public final class TickPolicy {
	private TickPolicy() {
	}

	/**
	 * Decides one object. Hot path: called once per candidate object per tick, allocates nothing
	 * and branches only on primitives and enum identity (SPEC INV-6).
	 *
	 * @param zone            how far the object is from the nearest player (SPEC FR-7)
	 * @param mode            the mode actually in force, i.e. {@code effectiveMode()} including the
	 *                        {@code safe_compatibility_mode} override
	 * @param load            the current load level (SPEC FR-5)
	 * @param adaptiveEnabled the {@code enable_adaptive_mode} config flag
	 * @param allowlisted     whether the operator listed this type in {@code throttle_allowlist}
	 * @param denylisted      whether the operator listed this type in {@code throttle_denylist},
	 *                        which outranks the allowlist
	 * @param protectedObject whether this individual object may never be thinned — force-loaded,
	 *                        ridden or riding, leashed, persistent, named, always-ticking, or
	 *                        vetoed through the public API. Computed by the caller, which is the
	 *                        only side that can see the object
	 * @return the verdict and the reason for it; never {@code null}
	 */
	public static ThrottleVerdict decide(ActivityZone zone, AdaptiveMode mode, LoadLevel load,
			boolean adaptiveEnabled, boolean allowlisted, boolean denylisted,
			boolean protectedObject) {
		if (!adaptiveEnabled) {
			return ThrottleVerdict.TICK_ADAPTIVE_DISABLED;
		}

		if (mode == AdaptiveMode.STRICT) {
			return ThrottleVerdict.TICK_STRICT_MODE;
		}

		if (protectedObject) {
			return ThrottleVerdict.TICK_PROTECTED;
		}

		if (denylisted) {
			return ThrottleVerdict.TICK_DENYLISTED;
		}

		if (zone == null || !zone.permitsThinning()) {
			return ThrottleVerdict.TICK_IN_FULL_ZONE;
		}

		if (!allowlisted) {
			return ThrottleVerdict.TICK_NOT_ALLOWLISTED;
		}

		if (!intervenesAt(mode, load)) {
			return ThrottleVerdict.TICK_LOAD_TOO_LOW;
		}

		return zone == ActivityZone.FROZEN
				? ThrottleVerdict.ELIGIBLE_FROZEN
				: ThrottleVerdict.ELIGIBLE_REDUCED;
	}

	/**
	 * The load half of SPEC FR-11.
	 *
	 * <p>BALANCED acts at HIGH and CRITICAL, which is what the table says. AGGRESSIVE acts "earlier
	 * and more strongly" and so starts one level down, at ELEVATED — it never acts at NORMAL,
	 * because a server inside its target budget has nothing to gain and a mode that thins a healthy
	 * server would be a change nobody asked for.
	 *
	 * @param mode the mode in force; STRICT never reaches this method
	 * @param load the current load level
	 * @return whether this mode intervenes at this load level
	 */
	public static boolean intervenesAt(AdaptiveMode mode, LoadLevel load) {
		if (load == null) {
			return false;
		}

		return switch (mode) {
			case STRICT -> false;
			case BALANCED -> load.ordinal() >= LoadLevel.HIGH.ordinal();
			case AGGRESSIVE -> load.ordinal() >= LoadLevel.ELEVATED.ordinal();
		};
	}
}
