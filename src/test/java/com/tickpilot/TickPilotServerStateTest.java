package com.tickpilot;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.tickpilot.budget.TickBudget;
import com.tickpilot.config.ConfigLoader;
import com.tickpilot.config.TickPilotConfig;

import org.junit.jupiter.api.Test;

/**
 * The config side of the per-server state: what {@code /tickpilot reload} does to a running
 * server (SPEC FR-15, AC-15).
 *
 * <p>Testable without Minecraft because the state object takes its clock as a parameter and holds
 * no game objects.
 */
class TickPilotServerStateTest {
	private static final long START_NANOS = 1_000_000_000L;

	private static TickPilotConfig config(String toml) {
		return ConfigLoader.read(toml).config();
	}

	@Test
	void thresholdsComeFromTheConfig() {
		TickPilotServerState state = new TickPilotServerState(START_NANOS,
				config("target_mspt = 25.0\ncritical_mspt = 45.0\n"));

		assertEquals(25.0, state.budget().targetMspt());
		assertEquals(45.0, state.budget().criticalMspt());
		// mid = target + (critical - target) * 0.5, per SPEC FR-5.
		assertEquals(35.0, state.budget().highMspt());
	}

	@Test
	void reloadingUnchangedThresholdsKeepsTheBudgetAndItsLevel() {
		TickPilotConfig original = config("target_mspt = 25.0\ncritical_mspt = 45.0\n");
		TickPilotServerState state = new TickPilotServerState(START_NANOS, original);
		TickBudget before = state.budget();

		// Same thresholds, different unrelated setting.
		TickPilotConfig reloaded = config("target_mspt = 25.0\ncritical_mspt = 45.0\nfull_radius = 8\n");
		boolean changed = state.reconfigure(reloaded, START_NANOS + 30_000_000_000L);

		assertFalse(changed);
		assertSame(before, state.budget(), "an unrelated edit must not reset the load level");
		assertEquals(8, state.config().fullRadius(), "the new config must still be applied");
	}

	@Test
	void reloadingChangedThresholdsRebuildsTheBudget() {
		TickPilotServerState state = new TickPilotServerState(START_NANOS,
				config("target_mspt = 25.0\ncritical_mspt = 45.0\n"));

		boolean changed = state.reconfigure(config("target_mspt = 30.0\ncritical_mspt = 60.0\n"),
				START_NANOS + 30_000_000_000L);

		assertTrue(changed);
		assertEquals(30.0, state.budget().targetMspt());
		assertEquals(60.0, state.budget().criticalMspt());
	}

	@Test
	void aRejectedConfigStillProducesAUsableBudget() {
		// AC-15: an inverted pair is repaired by the loader, so TickBudget never sees values its
		// constructor would refuse.
		TickPilotServerState state = new TickPilotServerState(START_NANOS,
				config("target_mspt = 80.0\ncritical_mspt = 10.0\n"));

		assertTrue(state.budget().criticalMspt() > state.budget().targetMspt());
	}
}
