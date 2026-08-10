package com.tickpilot.api;

import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;

import com.tickpilot.ServerStateHolder;
import com.tickpilot.TickPilot;
import com.tickpilot.TickPilotServerState;
import com.tickpilot.budget.LoadLevel;
import com.tickpilot.metrics.TickMetricsSnapshot;
import com.tickpilot.policy.PolicyRegistry;
import com.tickpilot.policy.PolicyRegistryHolder;
import com.tickpilot.scheduler.AdaptiveScheduler;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;

/**
 * The stable entry point other mods use to talk to TickPilot (SPEC FR-14).
 *
 * <p>Everything a consumer needs is in this package: this class, {@link TaskProfile},
 * {@link TaskPriority}, {@link SubmitResult}, {@link ThrottlePolicy}, {@link ThrottleAdvice},
 * {@link ServerLoad} and {@link TickPilotMetrics}. Nothing here exposes a TickPilot class from
 * any other package, so an integration never compiles against internals that may change
 * (SPEC AC-14).
 *
 * <h2>TickPilot is a soft dependency</h2>
 * No method here throws when TickPilot is inactive; queries return an empty {@link Optional} and
 * submissions return {@link SubmitResult#UNAVAILABLE}. That covers a disabled subsystem and a
 * server that is not running, but <em>not</em> the mod being absent from the instance: if the jar
 * is not there, this class does not exist and touching it throws {@link NoClassDefFoundError}.
 * Keep the calls in a class you only load after checking
 * {@code FabricLoader.getInstance().isModLoaded("tickpilot")}; the README has the pattern in full.
 *
 * <h2>Threading</h2>
 * Registration methods may be called from anywhere, normally from your mod initialiser.
 * {@link #submit} must be called on the server thread and returns
 * {@link SubmitResult#WRONG_THREAD} otherwise, without running or queueing anything: running the
 * work would touch the world off-thread (SPEC INV-1, INV-2), and queueing it would write into a
 * queue that is single-threaded by construction. Refusing is the only answer that cannot turn a
 * mistake in one mod into corruption in another.
 *
 * <h2>Nothing here can make the server do less</h2>
 * The API defers work its owner marked deferrable and reports what has been measured. It does not
 * throttle entities or block entities: those policies are SPEC FR-8 and FR-9 and are not
 * implemented in this version. A {@link ThrottlePolicy} registered today is stored and is not
 * consulted by anything yet, which {@link #registerPolicy} says in the log rather than leaving you
 * to conclude that your integration is broken.
 */
public final class TickPilotApi {
	/** Nanoseconds between two identical warnings about a misuse of this API (SPEC AC-16). */
	private static final long WARN_COOLDOWN_NANOS = 30L * 1_000_000_000L;

	private static final AtomicLong WRONG_THREAD_SUBMITS = new AtomicLong();
	private static final AtomicLong LAST_WRONG_THREAD_WARN = new AtomicLong(Long.MIN_VALUE);
	private static final AtomicLong MISSING_PROFILE_SUBMITS = new AtomicLong();
	private static final AtomicLong LAST_MISSING_PROFILE_WARN = new AtomicLong(Long.MIN_VALUE);
	private static final AtomicLong LAST_BAD_ARGUMENT_WARN = new AtomicLong(Long.MIN_VALUE);

	private TickPilotApi() {
	}

	/**
	 * Whether TickPilot is running and healthy on the current server.
	 *
	 * <p>Only useful for diagnostics. You do not have to call it before the other methods: they
	 * are all safe when TickPilot is inactive, and a check here would in any case be stale by the
	 * time the next line runs.
	 *
	 * @return {@code true} when a server is running and TickPilot has not disabled itself on it
	 */
	public static boolean isAvailable() {
		TickPilotServerState state = ServerStateHolder.current();
		return state != null && !state.isDisabled();
	}

	/**
	 * Declares what one kind of task is, so that {@link #submit} knows how to treat it.
	 *
	 * <p>Call once, from your mod initialiser. The declaration is not tied to a world and survives
	 * a return to the main menu, so there is no need to register it again per world.
	 *
	 * <p>Registering the same id twice with a different profile replaces the first and logs it:
	 * that usually means two mods picked the same id, and the second one silently winning would be
	 * a genuinely hard bug to find. Use your own namespace.
	 *
	 * @param id      the task id, in your own namespace
	 * @param profile what the work is; see {@link TaskProfile}. Note that the profile normalises
	 *                contradictory combinations in its constructor, so read the accessors back if
	 *                you want to be sure of what you registered
	 */
	public static void registerTaskProfile(ResourceLocation id, TaskProfile profile) {
		if (rejectBadArgument("registerTaskProfile", id, profile)) {
			return;
		}

		if (registrations().registerTaskProfile(id, profile)) {
			TickPilot.LOGGER.warn("Task profile {} was registered twice with different settings; "
					+ "the last registration wins", id);
		}
	}

	/**
	 * Hands a piece of work to TickPilot, which either runs it now or runs it within
	 * {@link TaskProfile#maxDelayTicks()} ticks (SPEC FR-6).
	 *
	 * <p>Server thread only. What happens depends entirely on the profile registered for
	 * {@code taskId}:
	 * <ul>
	 *   <li>no profile registered → the work runs immediately and the result is
	 *       {@link SubmitResult#EXECUTED_NOW_NO_PROFILE}. TickPilot does not assume that unknown
	 *       work is safe to delay;</li>
	 *   <li>critical or not deferrable → runs immediately, {@link SubmitResult#EXECUTED_NOW};</li>
	 *   <li>deferrable → queued, {@link SubmitResult#DEFERRED}, or folded into an identical
	 *       queued submission, {@link SubmitResult#COALESCED};</li>
	 *   <li>queue full of work at least as urgent → {@link SubmitResult#REJECTED_QUEUE_FULL} and
	 *       the work is <em>not</em> run. It is yours again.</li>
	 * </ul>
	 *
	 * <p>Work still queued when the server stops is discarded unrun. Anything that must not be
	 * lost must not be deferrable.
	 *
	 * @param taskId          the id whose profile applies
	 * @param mainThreadWork  the work, which will run on the server thread. Must not throw; if it
	 *                        does, TickPilot catches it, logs it with a cooldown and carries on
	 * @return what happened; see {@link SubmitResult}. Never {@code null}
	 */
	public static SubmitResult submit(ResourceLocation taskId, Runnable mainThreadWork) {
		if (rejectBadArgument("submit", taskId, mainThreadWork)) {
			return SubmitResult.UNAVAILABLE;
		}

		MinecraftServer server = ServerStateHolder.currentServer();

		if (server == null) {
			return SubmitResult.UNAVAILABLE;
		}

		// Checked before anything is read from the scheduler, so a submission from another thread
		// never touches a single one of its fields (SPEC INV-1).
		if (!server.isSameThread()) {
			warnWrongThread(taskId);
			return SubmitResult.WRONG_THREAD;
		}

		TickPilotServerState state = ServerStateHolder.get(server);

		if (state == null || state.isDisabled()) {
			return SubmitResult.UNAVAILABLE;
		}

		AdaptiveScheduler<ResourceLocation> scheduler = state.scheduler();
		TaskProfile profile = registrations().taskProfile(taskId);

		try {
			if (profile == null) {
				warnMissingProfile(taskId);
				SubmitResult result = scheduler.submit(taskId, mainThreadWork,
						TaskProfile.immediate());
				return result == SubmitResult.EXECUTED_NOW
						? SubmitResult.EXECUTED_NOW_NO_PROFILE
						: result;
			}

			return scheduler.submit(taskId, mainThreadWork, profile);
		} catch (Throwable failure) {
			// A throw from the scheduler itself is a TickPilot bug, not a task failure - those are
			// caught inside it. INV-9: step aside rather than take the server with us.
			state.disable("adaptive scheduler failed on submit: " + failure);
			return SubmitResult.UNAVAILABLE;
		}
	}

	/**
	 * Registers a rule about which of your types may be thinned (SPEC FR-14, INV-5).
	 *
	 * <p>Stored and not yet consulted: TickPilot throttles nothing in this version. One log line
	 * is written per registration saying exactly that, so the absence of any effect is not read as
	 * a broken integration. The policy will be asked as soon as SPEC FR-8 and FR-9 exist.
	 *
	 * @param id     an id for your policy, in your own namespace
	 * @param policy the rule; see {@link ThrottlePolicy} for the contract it must satisfy
	 */
	public static void registerPolicy(ResourceLocation id, ThrottlePolicy policy) {
		if (rejectBadArgument("registerPolicy", id, policy)) {
			return;
		}

		if (registrations().registerPolicy(id, policy)) {
			TickPilot.LOGGER.info("Throttle policy {} registered. Nothing consults it yet: "
					+ "TickPilot does not throttle entities or block entities in this version, "
					+ "so the policy has no effect until that is implemented", id);
		}
	}

	/**
	 * States that a type of yours survives being updated less often than every tick.
	 *
	 * <p>Stored as a declaration; nothing acts on it yet, for the same reason as
	 * {@link #registerPolicy}. If several mods make a claim about the same type, the smallest
	 * delay wins — TickPilot cannot adjudicate a disagreement about someone's content, so it keeps
	 * the more cautious answer.
	 *
	 * @param typeId        the entity type or block entity type id
	 * @param maxDelayTicks the longest gap between updates you consider safe, in ticks. Values
	 *                      below 1 are ignored, since "at most zero ticks late" is not a hint
	 */
	public static void markSafeToDefer(ResourceLocation typeId, long maxDelayTicks) {
		if (rejectBadArgument("markSafeToDefer", typeId)) {
			return;
		}

		PolicyRegistry<ResourceLocation> registrations = registrations();

		if (registrations.markSafeToDefer(typeId, maxDelayTicks) && registrations.deferHintCount() == 1) {
			TickPilot.LOGGER.info("A mod marked {} safe to defer for up to {} ticks. Hints like "
					+ "this are recorded but not acted on yet: TickPilot does not change the "
					+ "update rate of anything in this version", typeId, maxDelayTicks);
		}
	}

	/**
	 * States that the pure computation part of a type's work could run off the server thread.
	 *
	 * <p>Stored as a declaration and acted on by nothing. TickPilot runs no game logic off the
	 * server thread, and this hint does not change that: SPEC INV-1 forbids touching the world
	 * from another thread at all, and separating a type's computation from its world access is not
	 * something a flag can do on its owner's behalf.
	 *
	 * @param typeId the entity type or block entity type id
	 */
	public static void markSafeForAsyncCompute(ResourceLocation typeId) {
		if (rejectBadArgument("markSafeForAsyncCompute", typeId)) {
			return;
		}

		PolicyRegistry<ResourceLocation> registrations = registrations();

		if (registrations.markSafeForAsyncCompute(typeId)
				&& registrations.asyncComputeHintCount() == 1) {
			TickPilot.LOGGER.info("A mod marked {} safe for asynchronous computation. Hints like "
					+ "this are recorded but not acted on: TickPilot runs no game logic off the "
					+ "server thread", typeId);
		}
	}

	/**
	 * A read-only copy of what TickPilot has measured on the current server (SPEC AC-1, FR-14).
	 *
	 * <p>Safe to call from any thread and cheap enough to call from a HUD, but not free: it copies
	 * a dozen numbers and walks nothing. Do not call it per entity.
	 *
	 * @return the snapshot, or an empty {@link Optional} when no server is running, TickPilot has
	 *         disabled itself, or no tick has been measured yet
	 */
	public static Optional<TickPilotMetrics> metrics() {
		TickPilotServerState state = ServerStateHolder.current();

		if (state == null || state.isDisabled()) {
			return Optional.empty();
		}

		try {
			TickMetricsSnapshot metrics = state.snapshot(System.nanoTime());

			if (metrics.isEmpty()) {
				return Optional.empty();
			}

			return Optional.of(new TickPilotMetrics(
					metrics.tps(),
					metrics.lastMspt(),
					metrics.avgMspt5s(),
					metrics.avgMspt1m(),
					metrics.avgMspt5m(),
					metrics.p95Mspt1m(),
					metrics.p99Mspt1m(),
					metrics.maxMspt(),
					metrics.maxAgeNanos(),
					metrics.totalTicks(),
					metrics.uptimeNanos(),
					toServerLoad(state.loadLevel()),
					state.scheduler().queued(),
					state.scheduler().maxQueued()));
		} catch (Throwable failure) {
			TickPilot.LOGGER.warn("TickPilotApi.metrics() failed", failure);
			return Optional.empty();
		}
	}

	/**
	 * Maps the internal load level onto the published one.
	 *
	 * <p>An exhaustive switch on purpose: adding a level internally then breaks this file at
	 * compile time instead of silently reporting the wrong one to other mods.
	 */
	private static ServerLoad toServerLoad(LoadLevel level) {
		return switch (level) {
			case NORMAL -> ServerLoad.NORMAL;
			case ELEVATED -> ServerLoad.ELEVATED;
			case HIGH -> ServerLoad.HIGH;
			case CRITICAL -> ServerLoad.CRITICAL;
		};
	}

	private static PolicyRegistry<ResourceLocation> registrations() {
		return PolicyRegistryHolder.get();
	}

	/**
	 * @return {@code true} when the call must be abandoned. A {@code null} argument is a bug in
	 *         the calling mod, and the answer to it is a log line rather than an exception: this
	 *         API is often called from a tick, and throwing there would take the server down over
	 *         someone else's mistake (SPEC INV-9)
	 */
	private static boolean rejectBadArgument(String method, Object id, Object value) {
		return rejectBadArgument(method, id == null || value == null ? null : id);
	}

	/** @see #rejectBadArgument(String, Object, Object) */
	private static boolean rejectBadArgument(String method, Object id) {
		if (id != null) {
			return false;
		}

		if (shouldWarn(LAST_BAD_ARGUMENT_WARN)) {
			TickPilot.LOGGER.warn("TickPilotApi.{} was called with a null argument and ignored; "
					+ "this is a bug in the calling mod", method);
		}

		return true;
	}

	private static void warnWrongThread(ResourceLocation taskId) {
		long total = WRONG_THREAD_SUBMITS.incrementAndGet();

		if (shouldWarn(LAST_WRONG_THREAD_WARN)) {
			TickPilot.LOGGER.warn("TickPilotApi.submit({}) was called off the server thread and "
					+ "refused; the work did not run. Submit from the server thread. "
					+ "{} such calls so far", taskId, total);
		}
	}

	private static void warnMissingProfile(ResourceLocation taskId) {
		long total = MISSING_PROFILE_SUBMITS.incrementAndGet();

		if (shouldWarn(LAST_MISSING_PROFILE_WARN)) {
			TickPilot.LOGGER.warn("TickPilotApi.submit({}) has no registered TaskProfile, so the "
					+ "work ran immediately instead of being deferred. Call registerTaskProfile "
					+ "in your initialiser. {} such calls so far", taskId, total);
		}
	}

	/**
	 * @param lastWarnNanos holder of the timestamp of the last warning of this kind
	 * @return {@code true} when the cooldown has elapsed and this caller won the race to report.
	 *         Compare-and-set rather than a plain write because the wrong-thread warning is, by
	 *         definition, reached from threads TickPilot does not control
	 */
	private static boolean shouldWarn(AtomicLong lastWarnNanos) {
		long now = System.nanoTime();
		long last = lastWarnNanos.get();

		if (last != Long.MIN_VALUE && now - last < WARN_COOLDOWN_NANOS) {
			return false;
		}

		return lastWarnNanos.compareAndSet(last, now);
	}
}
