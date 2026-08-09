package com.tickpilot.client;

import net.fabricmc.api.ClientModInitializer;

/**
 * Client entrypoint. Intentionally does nothing yet.
 *
 * <p>The optional HUD is FR-20 and lands in the last phase; until then there is no client-side
 * behaviour at all, which is exactly what SPEC FR-18 wants — a dedicated server never loads
 * this class, and a client that has it gains nothing it can break on.
 *
 * <p>Note on AC-19 ("when no integrated server exists, the client side does nothing and throws
 * nothing"): there is deliberately no integrated-server check here. At client-init time the
 * integrated server never exists yet — a world has not been loaded — so a check at this point
 * would be dead code that proves nothing. The check belongs where the HUD actually reads server
 * state, and it will be written together with the HUD.
 */
public class TickPilotClient implements ClientModInitializer {
	@Override
	public void onInitializeClient() {
		// No client-side behaviour yet; see the class javadoc.
	}
}
