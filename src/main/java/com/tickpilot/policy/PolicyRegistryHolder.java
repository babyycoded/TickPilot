package com.tickpilot.policy;

import net.minecraft.resources.ResourceLocation;

/**
 * Holds the one {@link PolicyRegistry} that {@code TickPilotApi} writes into and that the
 * policies of SPEC FR-8 and FR-9 will read from.
 *
 * <p>Separate from the public API class on purpose: the registry is an internal type, and SPEC
 * AC-14 requires that nothing a consumer has to touch exposes one. Other mods go through
 * {@code TickPilotApi}; TickPilot's own subsystems come here.
 *
 * <p>Why a static holder is allowed to outlive a world is argued in {@link PolicyRegistry} and in
 * SPEC §13 entry #15: this is a table of declarations made by mods, not state belonging to a
 * world.
 */
public final class PolicyRegistryHolder {
	private static final PolicyRegistry<ResourceLocation> REGISTRY = new PolicyRegistry<>();

	private PolicyRegistryHolder() {
	}

	/** @return the registry every mod's declarations end up in */
	public static PolicyRegistry<ResourceLocation> get() {
		return REGISTRY;
	}
}
