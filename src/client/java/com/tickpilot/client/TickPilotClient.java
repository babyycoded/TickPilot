package com.tickpilot.client;

import net.fabricmc.api.ClientModInitializer;

/**
 * Client entrypoint: the optional HUD of SPEC FR-20, and nothing else.
 *
 * <p>A dedicated server never runs this class — Fabric only invokes the {@code client} entrypoint
 * on a client — and no common class names anything in this package, which
 * {@code SideSeparationTest} checks on every build rather than leaving to review (SPEC FR-18).
 *
 * <h2>Registering here is not the same as switching the HUD on</h2>
 * Both callbacks are registered unconditionally, because {@code client_hud_enabled} lives in the
 * server config and there is no server to ask at client-init time. What the flag gates is the work:
 * with it off the sampler returns after one field read and publishes nothing, so the renderer finds
 * no snapshot and draws nothing (SPEC INV-3). The flag is therefore live — {@code /tickpilot reload}
 * turns the HUD on and off without restarting the game.
 *
 * <h2>The AC-19 check that Phase 2 deferred, and where it actually belongs</h2>
 * The last clause of AC-19 asks that the client side do nothing and throw nothing when no
 * integrated server exists. Phase 2 deliberately did not put that check in this class, because at
 * client-init time the integrated server never exists yet and a check here would have been dead
 * code proving nothing. It is now where it belongs: {@code HudRenderer} finds a {@code null}
 * snapshot on the main menu and returns, without asking a server that is not there. Nothing about
 * that path is specific to the menu — it is the same path a client connected to somebody else's
 * dedicated server takes, since that client has no integrated server either.
 */
public class TickPilotClient implements ClientModInitializer {
	@Override
	public void onInitializeClient() {
		HudSampler.register();
		HudRenderer.register();
	}
}
