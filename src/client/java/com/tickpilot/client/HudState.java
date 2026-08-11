package com.tickpilot.client;

/**
 * The one place the server thread hands a {@link HudSnapshot} to the render thread (SPEC FR-20).
 *
 * <h2>Why this class exists at all</h2>
 * It is two lines of state, and it is the whole thread-safety argument of the HUD. The field is
 * {@code volatile}, so a snapshot the server thread finished building is fully visible to the
 * render thread that reads the reference — the record is immutable, so there is nothing further to
 * synchronise. No lock is taken on either side, and the render thread never touches a mutable
 * structure the server is writing.
 *
 * <h2>Why it is cleared and not merely stale</h2>
 * {@code null} means "there is no integrated server to describe", and the HUD draws nothing at all
 * in that state. That is what makes the last clause of SPEC AC-19 true rather than approximately
 * true: after leaving a world the numbers do not linger on the main menu, and entering a second
 * world starts from nothing instead of from world A's figures.
 *
 * <h2>Static, and why that is not the state SPEC INV-7 forbids</h2>
 * Same reasoning as {@code ServerStateHolder}, SPEC §13 entry #5. What is held is a record of
 * primitives, never a server, a world or a game object, and it is set to {@code null} on
 * {@code SERVER_STOPPED}. Between two worlds this class holds nothing.
 */
final class HudState {
	private static volatile HudSnapshot current;

	private HudState() {
	}

	/**
	 * Publishes a fresh snapshot. Called from the server thread of the integrated server.
	 *
	 * @param snapshot the snapshot to show; never {@code null}
	 */
	static void publish(HudSnapshot snapshot) {
		current = snapshot;
	}

	/**
	 * @return the most recent snapshot, or {@code null} when no integrated server is running. The
	 *         render thread's only entry point
	 */
	static HudSnapshot current() {
		return current;
	}

	/** Forgets the current snapshot. Called when the integrated server stops (SPEC AC-19). */
	static void clear() {
		current = null;
	}
}
