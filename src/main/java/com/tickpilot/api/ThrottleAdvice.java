package com.tickpilot.api;

/**
 * What a {@link ThrottlePolicy} says about one type at one moment (SPEC FR-14).
 *
 * <p>The three answers are not symmetric, and that asymmetry is the point: a veto is absolute,
 * permission is not. {@link #NEVER_THROTTLE} from any registered policy settles the question for
 * that type. {@link #SAFE_TO_THROTTLE} is only ever an input to a decision the operator's config
 * still has to agree with, because SPEC INV-5 forbids TickPilot from thinning anything that is not
 * on the explicit allowlist, whoever asked for it.
 */
public enum ThrottleAdvice {
	/**
	 * This type must keep ticking at its normal rate. Binding: one veto outweighs any number of
	 * permissions, and it cannot be overridden from the config either.
	 *
	 * <p>Use it for anything whose correctness depends on being ticked every tick — timing
	 * circuits, transport, anything a player rides or is pulled by.
	 */
	NEVER_THROTTLE,

	/**
	 * The policy has nothing to say about this type. Equivalent to not being registered at all
	 * for that type, and the right answer for every type a policy does not own.
	 */
	NO_OPINION,

	/**
	 * The owning mod believes this type survives being ticked less often. A permission, not an
	 * instruction: TickPilot still requires the type to be on the operator's throttle allowlist
	 * before anything is thinned (SPEC INV-5), and still never touches it in STRICT mode.
	 */
	SAFE_TO_THROTTLE
}
