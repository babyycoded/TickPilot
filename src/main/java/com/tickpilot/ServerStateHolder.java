package com.tickpilot;

import com.tickpilot.config.TickPilotConfig;

import net.minecraft.server.MinecraftServer;

/**
 * Owns the lookup from a running {@link MinecraftServer} to its {@link TickPilotServerState}.
 *
 * <h2>Why a static map does not violate SPEC INV-7</h2>
 * INV-7 forbids mutable state that <em>survives a world</em>, not every static field. The map
 * here is a lookup table, not state: an entry is created on {@code SERVER_STARTED} and removed
 * on {@code SERVER_STOPPED}, so between two singleplayer worlds the map is empty and world B
 * starts from zero (SPEC AC-19). The alternative — a field injected into {@code MinecraftServer}
 * through a Mixin accessor — would carry a Mixin for no safety gain, and Mixins are out of
 * scope for this phase.
 *
 * <p>{@link ServerStateRegistry} holds the actual map and is Minecraft-free so the lifecycle
 * contract can be unit-tested.
 */
public final class ServerStateHolder {
	private static final ServerStateRegistry<MinecraftServer, TickPilotServerState> REGISTRY = new ServerStateRegistry<>();

	/**
	 * The server that is running right now, or {@code null} between worlds.
	 *
	 * <p>Needed because the public API of SPEC FR-14 takes no server argument — a mod calling
	 * {@code TickPilotApi.submit} has one server to mean, and asking it to name it would be
	 * ceremony. At most one server exists at a time in both environments TickPilot supports: a
	 * dedicated server is the process, and a singleplayer world has exactly one integrated server.
	 *
	 * <p>{@code volatile} because it is written on the lifecycle thread and read from the API,
	 * which a mod may call from anywhere. Cleared when the server it names goes away, so it can
	 * never hand out a stopped server (SPEC INV-7, AC-19).
	 */
	private static volatile MinecraftServer currentServer;

	private ServerStateHolder() {
	}

	/**
	 * Creates and stores fresh state for {@code server}, replacing anything previously stored
	 * under the same instance.
	 *
	 * @param config the config snapshot this server runs on (SPEC FR-15)
	 * @return the newly created state
	 */
	public static TickPilotServerState create(MinecraftServer server, TickPilotConfig config) {
		TickPilotServerState state = REGISTRY.create(server,
				key -> new TickPilotServerState(System.nanoTime(), config));
		currentServer = server;
		return state;
	}

	/**
	 * @return the server that is running right now, or {@code null} when none is. Used by the
	 *         public API, whose methods take no server argument (SPEC FR-14)
	 */
	public static MinecraftServer currentServer() {
		return currentServer;
	}

	/**
	 * @return the state of the running server, or {@code null} when no server is running or
	 *         TickPilot holds no state for it
	 */
	public static TickPilotServerState current() {
		MinecraftServer server = currentServer;
		return server == null ? null : REGISTRY.get(server);
	}

	/**
	 * @return the state for {@code server}, or {@code null} when TickPilot holds no state for it
	 *         — which is the normal situation before {@code SERVER_STARTED} and after
	 *         {@code SERVER_STOPPED}
	 */
	public static TickPilotServerState get(MinecraftServer server) {
		return REGISTRY.get(server);
	}

	/**
	 * Removes the state for {@code server} without shutting it down. Use {@link #shutdown} for
	 * the normal stop path.
	 */
	public static TickPilotServerState remove(MinecraftServer server) {
		clearCurrent(server);
		return REGISTRY.remove(server);
	}

	/**
	 * Shuts down and removes the state for {@code server}. Safe to call when no state exists.
	 */
	public static void shutdown(MinecraftServer server) {
		clearCurrent(server);
		TickPilotServerState state = REGISTRY.remove(server);

		if (state != null) {
			state.shutdown();
		}
	}

	/**
	 * Forgets the running server, but only if it is the one being stopped: a state removed for
	 * some other reason must not blank out a server that is still ticking.
	 */
	private static void clearCurrent(MinecraftServer server) {
		if (currentServer == server) {
			currentServer = null;
		}
	}

	/**
	 * @return {@code true} when TickPilot holds no per-server state at all. Expected to be true
	 *         whenever no server is running; used to verify INV-7 by hand and in tests.
	 */
	public static boolean isEmpty() {
		return REGISTRY.isEmpty();
	}
}
