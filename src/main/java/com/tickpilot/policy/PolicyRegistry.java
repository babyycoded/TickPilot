package com.tickpilot.policy;

import java.util.Collection;
import java.util.Collections;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import com.tickpilot.api.TaskProfile;
import com.tickpilot.api.ThrottlePolicy;

/**
 * Everything other mods have told TickPilot about their own content through the public API
 * (SPEC FR-14): task profiles, throttle policies and the two "this is safe" hints.
 *
 * <h2>Why this deliberately outlives a world, and why that is not INV-7</h2>
 * This table is registered once, normally from another mod's initialiser, and it stays across
 * {@code SERVER_STOPPED} → {@code SERVER_STARTED} <b>on purpose</b>. SPEC INV-7 forbids mutable
 * state <em>of a world</em> surviving that world; a declaration table is not state of a world. It
 * says what a mod believes about its own types, which is identical in every world and does not
 * become stale when one ends. Three properties keep it from turning into the thing INV-7 is about:
 * <ul>
 *   <li>every entry is keyed by a registry id, so registering again <em>replaces</em> an entry and
 *       nothing accumulates over repeated world loads;</li>
 *   <li>nothing stored here references a world, a server or any game object — only ids, immutable
 *       {@link TaskProfile} records, and policy objects owned by the mod that registered them;</li>
 *   <li>the state that really belongs to a world — the queue of deferred tasks — is not here. It
 *       lives in the per-server state and dies with it.</li>
 * </ul>
 * The same reasoning was applied to the server lookup table in SPEC §13 entry #5; this one is
 * entry #15.
 *
 * <h2>Threading</h2>
 * Registration can arrive from any thread and at any time; reads happen on the server thread.
 * Concurrent maps rather than a lock, because a registration is rare and a read must never block
 * the tick loop.
 *
 * <p>Generic over the id type so the whole class is unit-tested without Minecraft; the mod uses
 * {@code ResourceLocation}.
 *
 * @param <K> the registry id type
 */
public final class PolicyRegistry<K> {
	private final Map<K, TaskProfile> profiles = new ConcurrentHashMap<>();
	private final Map<K, ThrottlePolicy> policies = new ConcurrentHashMap<>();
	private final Map<K, Long> deferHints = new ConcurrentHashMap<>();
	private final Set<K> asyncComputeHints = ConcurrentHashMap.newKeySet();

	/**
	 * Stores what a mod said about one kind of task, replacing any earlier declaration for the
	 * same id.
	 *
	 * @param taskId  the task id
	 * @param profile the profile; ignored when {@code null}
	 * @return {@code true} when this replaced a different profile that was already registered,
	 *         which is worth a log line because it usually means two mods chose the same id
	 */
	public boolean registerTaskProfile(K taskId, TaskProfile profile) {
		if (taskId == null || profile == null) {
			return false;
		}

		TaskProfile previous = profiles.put(taskId, profile);
		return previous != null && !previous.equals(profile);
	}

	/**
	 * @param taskId the task id
	 * @return the registered profile, or {@code null} when the id was never registered. The
	 *         caller decides what an unregistered task means; it does not get a made-up default
	 *         here
	 */
	public TaskProfile taskProfile(K taskId) {
		return taskId == null ? null : profiles.get(taskId);
	}

	/** @return how many task profiles are registered */
	public int taskProfileCount() {
		return profiles.size();
	}

	/**
	 * Stores a throttle policy, replacing any earlier policy registered under the same id.
	 *
	 * @param policyId an id identifying the registering mod's policy
	 * @param policy   the policy; ignored when {@code null}
	 * @return {@code true} when the policy was stored
	 */
	public boolean registerPolicy(K policyId, ThrottlePolicy policy) {
		if (policyId == null || policy == null) {
			return false;
		}

		policies.put(policyId, policy);
		return true;
	}

	/**
	 * @return an unmodifiable view of the registered policies. Nothing consults them yet: the
	 *         entity and block entity policies of SPEC FR-8 and FR-9 are not implemented, which is
	 *         stated in the API documentation and in the log line written at registration
	 */
	public Collection<ThrottlePolicy> policies() {
		return Collections.unmodifiableCollection(policies.values());
	}

	/** @return how many throttle policies are registered */
	public int policyCount() {
		return policies.size();
	}

	/**
	 * Records that the owning mod considers a type safe to update less often than every tick, and
	 * how long it may go between updates.
	 *
	 * <p>Repeated hints for the same type keep the <b>smallest</b> delay offered. Two mods
	 * disagreeing about someone's content is not something TickPilot can adjudicate, so it takes
	 * the more cautious of the two answers.
	 *
	 * @param typeId        the entity or block entity type id
	 * @param maxDelayTicks the longest gap the mod considers safe; values below 1 are ignored
	 * @return {@code true} when a hint was stored
	 */
	public boolean markSafeToDefer(K typeId, long maxDelayTicks) {
		if (typeId == null || maxDelayTicks < 1L) {
			return false;
		}

		deferHints.merge(typeId, maxDelayTicks, Math::min);
		return true;
	}

	/**
	 * @param typeId   the type id
	 * @param fallback what to answer when no mod has said anything about this type
	 * @return the smallest delay any mod called safe for this type, or {@code fallback}
	 */
	public long deferHint(K typeId, long fallback) {
		Long hint = typeId == null ? null : deferHints.get(typeId);
		return hint == null ? fallback : hint;
	}

	/** @return how many types have a defer hint */
	public int deferHintCount() {
		return deferHints.size();
	}

	/**
	 * Records that the owning mod considers the <em>pure computation</em> part of a type's work
	 * safe to run off the server thread.
	 *
	 * <p>Nothing in TickPilot runs off the server thread, so this stores a claim and changes no
	 * behaviour. It is not a promise from TickPilot that anything will ever be moved off-thread:
	 * SPEC INV-1 rules out touching the world there at all, and separating a type's computation
	 * from its world access is not something the hint alone makes possible.
	 *
	 * @param typeId the entity or block entity type id
	 * @return {@code true} when the hint was stored and is new
	 */
	public boolean markSafeForAsyncCompute(K typeId) {
		return typeId != null && asyncComputeHints.add(typeId);
	}

	/**
	 * @param typeId the type id
	 * @return whether some mod marked this type safe for asynchronous computation
	 */
	public boolean isSafeForAsyncCompute(K typeId) {
		return typeId != null && asyncComputeHints.contains(typeId);
	}

	/** @return how many types are marked safe for asynchronous computation */
	public int asyncComputeHintCount() {
		return asyncComputeHints.size();
	}

	/**
	 * Forgets every registration. Not called during normal operation — registrations are meant to
	 * survive a world, see the class documentation — and exists so tests can start from an empty
	 * table.
	 */
	public void clear() {
		profiles.clear();
		policies.clear();
		deferHints.clear();
		asyncComputeHints.clear();
	}
}
