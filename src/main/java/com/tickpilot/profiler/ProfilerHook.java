package com.tickpilot.profiler;

/**
 * The single entry point every Mixin calls into, and the only thing they know about TickPilot.
 *
 * <h2>Why a static</h2>
 * A Mixin in {@code ServerLevel} has no cheap way back to the per-server state: it would have to
 * look the server up in a map on every entity, every tick. Instead the tick listener parks the
 * active profiler here for the duration of the tick and clears it afterwards.
 *
 * <p>This does not break SPEC INV-7 for the same reason {@code ServerStateHolder}'s map does not
 * (§13 entry #5): the field is set at the start of a tick and cleared at the end of it, so nothing
 * survives a world — between two worlds it is {@code null}, which {@code ProfilerHookTest} checks.
 *
 * <h2>Cost when profiling is off</h2>
 * The field is parked only while a sampling session is actually running (SPEC FR-4). With no
 * session, every hook costs one static read and a null check — in particular <em>no</em>
 * {@code System.nanoTime()}, which is the expensive part and the reason deep profiling is not
 * always on (SPEC INV-10).
 *
 * <h2>Thread safety</h2>
 * {@link #owner} is compared on every call. On a singleplayer client the integrated server ticks
 * on its own thread while the render thread runs {@code ClientLevel.tickBlockEntities} through the
 * very same Mixins; without this check that client work would push frames onto the server's stack
 * from another thread and corrupt it. The comparison enforces SPEC INV-1 by construction, so no
 * individual hook has to remember a logical-side check.
 */
public final class ProfilerHook {
	private static volatile TickProfiler active;
	private static volatile Thread owner;

	private ProfilerHook() {
	}

	/**
	 * Parks {@code profiler} for the current tick. Called from the tick listener on the server
	 * thread, and only when a sampling session is running.
	 */
	public static void attach(TickProfiler profiler) {
		owner = Thread.currentThread();
		active = profiler;
	}

	/** Unparks whatever {@link #attach} parked. Must run even if the tick threw. */
	public static void detach() {
		active = null;
		owner = null;
	}

	/** @return {@code true} when no profiler is parked; the expected state between ticks */
	public static boolean isDetached() {
		return active == null;
	}

	/**
	 * Opens a frame if a session is running on this thread. Hot path.
	 *
	 * @param category the category this frame's self time belongs to
	 * @param key      identity for per-type accounting (SPEC FR-3), or {@code null}
	 */
	public static void begin(TickCategory category, Object key) {
		TickProfiler profiler = active;

		if (profiler == null || Thread.currentThread() != owner) {
			return;
		}

		profiler.begin(category, key, System.nanoTime());
	}

	/** Closes the innermost frame. Must be paired with every {@link #begin} that ran. Hot path. */
	public static void end() {
		TickProfiler profiler = active;

		if (profiler == null || Thread.currentThread() != owner) {
			return;
		}

		profiler.end(System.nanoTime());
	}
}
