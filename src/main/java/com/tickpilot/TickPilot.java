package com.tickpilot;

import java.nio.file.Path;

import com.tickpilot.command.TickPilotCommand;
import com.tickpilot.config.ConfigLoadResult;
import com.tickpilot.config.ConfigLoader;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.loader.api.FabricLoader;

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
			ConfigLoadResult config = ConfigLoader.load(configFile());
			logConfigResult(config);

			ServerStateHolder.create(server, config.config());
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
	 * @return the path of {@code config/tickpilot.toml} for this game instance (SPEC FR-15).
	 *         Verified against fabric-loader 0.19.3: {@code FabricLoader.getConfigDir()} returns
	 *         {@code <gamedir>/config} as a {@link Path}.
	 */
	public static Path configFile() {
		return FabricLoader.getInstance().getConfigDir().resolve(ConfigLoader.FILE_NAME);
	}

	/**
	 * Writes one summary line for a config load, plus one line per problem (SPEC AC-16: no
	 * per-tick logging, but a config problem is worth saying out loud exactly once).
	 */
	public static void logConfigResult(ConfigLoadResult result) {
		switch (result.status()) {
			case CREATED -> LOGGER.info("Created {} with default settings", ConfigLoader.FILE_NAME);
			case CREATE_FAILED -> LOGGER.warn("Could not create {}; running on defaults",
					ConfigLoader.FILE_NAME);
			case LOADED -> LOGGER.info("Loaded {}", ConfigLoader.FILE_NAME);
			case LOADED_WITH_PROBLEMS -> LOGGER.warn(
					"Loaded {} with {} rejected value(s); the default is used for each of them",
					ConfigLoader.FILE_NAME, result.problems().size());
			case UNREADABLE -> LOGGER.error(
					"Running on default settings; {} was left unchanged so you can fix it",
					ConfigLoader.FILE_NAME);
		}

		for (String problem : result.problems()) {
			LOGGER.warn("  {}", problem);
		}
	}

	/**
	 * @return a {@link ResourceLocation} in the {@code tickpilot} namespace
	 */
	public static ResourceLocation id(String path) {
		return ResourceLocation.fromNamespaceAndPath(MOD_ID, path);
	}
}
