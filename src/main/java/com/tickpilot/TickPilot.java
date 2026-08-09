package com.tickpilot;

import com.tickpilot.command.TickPilotCommand;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Main (server + common) entrypoint. Owns the wiring only: it installs the lifecycle hooks
 * that create and destroy per-server state (SPEC FR-19) and registers commands.
 *
 * <p>This entrypoint runs on both a dedicated server and the integrated server of a
 * singleplayer world (SPEC FR-17), and must never reference client-only classes (SPEC FR-18).
 */
public class TickPilot implements ModInitializer {
	public static final String MOD_ID = "tickpilot";

	/** SLF4J logger named after the mod id. Never used per tick (SPEC AC-16). */
	public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

	@Override
	public void onInitialize() {
		ServerLifecycleEvents.SERVER_STARTED.register(TickPilot::onServerStarted);
		ServerLifecycleEvents.SERVER_STOPPING.register(TickPilot::onServerStopping);
		ServerLifecycleEvents.SERVER_STOPPED.register(TickPilot::onServerStopped);

		TickPilotTickListener.register();
		TickPilotCommand.register();
	}

	private static void onServerStarted(MinecraftServer server) {
		// INV-9: a failure inside TickPilot must never take the server down with it.
		try {
			ServerStateHolder.create(server);
			LOGGER.info("TickPilot active (dedicated={})", server.isDedicatedServer());
		} catch (Throwable t) {
			LOGGER.error("TickPilot failed to initialise server state; continuing without it", t);
		}
	}

	private static void onServerStopping(MinecraftServer server) {
		try {
			ServerStateHolder.shutdown(server);
		} catch (Throwable t) {
			LOGGER.error("TickPilot failed to shut down cleanly", t);
		}
	}

	private static void onServerStopped(MinecraftServer server) {
		try {
			// SERVER_STOPPING already released the state. This is the belt-and-braces removal
			// that guarantees nothing survives into the next world (INV-7, AC-19).
			ServerStateHolder.remove(server);
		} catch (Throwable t) {
			LOGGER.error("TickPilot failed to release server state", t);
		}
	}

	/**
	 * @return a {@link ResourceLocation} in the {@code tickpilot} namespace
	 */
	public static ResourceLocation id(String path) {
		return ResourceLocation.fromNamespaceAndPath(MOD_ID, path);
	}
}
