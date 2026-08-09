package com.tickpilot;

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

	private ServerStateHolder() {
	}

	/**
	 * Creates and stores fresh state for {@code server}, replacing anything previously stored
	 * under the same instance.
	 *
	 * @return the newly created state
	 */
	public static TickPilotServerState create(MinecraftServer server) {
		return REGISTRY.create(server, key -> new TickPilotServerState(System.nanoTime()));
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
		return REGISTRY.remove(server);
	}

	/**
	 * Shuts down and removes the state for {@code server}. Safe to call when no state exists.
	 */
	public static void shutdown(MinecraftServer server) {
		TickPilotServerState state = REGISTRY.remove(server);

		if (state != null) {
			state.shutdown();
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
