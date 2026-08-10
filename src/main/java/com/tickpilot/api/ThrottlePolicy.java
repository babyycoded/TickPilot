package com.tickpilot.api;

import net.minecraft.resources.ResourceLocation;

/**
 * A rule another mod registers to say which of its own types may be thinned, and which must never
 * be (SPEC FR-14, INV-5).
 *
 * <p>Register one with
 * {@link TickPilotApi#registerPolicy(ResourceLocation, ThrottlePolicy)}. A mod that only wants to
 * protect its content needs nothing else: return {@link ThrottleAdvice#NEVER_THROTTLE} for the
 * types it owns and {@link ThrottleAdvice#NO_OPINION} for everything else.
 *
 * <h2>Not consulted yet</h2>
 * TickPilot does not throttle anything as of this version, so no registered policy is asked
 * anything: entity and block entity policies are SPEC FR-8 and FR-9 and are not implemented. A
 * policy registered now is stored, is visible to TickPilot, and will be consulted as soon as
 * there is something to consult it about. This is stated here, in the log line that
 * {@code registerPolicy} writes, and in the README, so that "my policy has no effect" is not
 * mistaken for a bug in your integration.
 *
 * <h2>Contract</h2>
 * <ul>
 *   <li>Called on the server thread, never in a hot loop over instances — TickPilot asks per type,
 *       not per entity.</li>
 *   <li>Must be fast and must not block, allocate heavily, or touch the world. Answer from your
 *       own configuration.</li>
 *   <li>Must be pure: the same arguments should give the same answer for as long as the server's
 *       state has not changed. TickPilot may cache the answer within a tick.</li>
 *   <li>Must not throw. TickPilot catches, logs once with a cooldown, and treats the throwing
 *       policy as {@link ThrottleAdvice#NEVER_THROTTLE} — the safe reading of "this policy is
 *       broken" is to leave the type alone.</li>
 *   <li>Must never return {@code null}; a {@code null} is read as
 *       {@link ThrottleAdvice#NO_OPINION}.</li>
 * </ul>
 */
@FunctionalInterface
public interface ThrottlePolicy {
	/**
	 * Asks this policy about one type.
	 *
	 * @param typeId the entity type or block entity type in question, as its registry id
	 * @param load   how loaded the server is right now. A policy may allow thinning only under
	 *               {@link ServerLoad#CRITICAL}, for instance
	 * @return the advice; see {@link ThrottleAdvice} for how the three answers are combined
	 */
	ThrottleAdvice advise(ResourceLocation typeId, ServerLoad load);
}
