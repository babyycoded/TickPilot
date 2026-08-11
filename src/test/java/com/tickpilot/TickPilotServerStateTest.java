package com.tickpilot;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.tickpilot.TickPilotServerState.ModeChange;
import com.tickpilot.budget.TickBudget;
import com.tickpilot.config.AdaptiveMode;
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
	private static final long NANOS_PER_MILLI = 1_000_000L;

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
	void theRebuiltBudgetIsOnTheSameClockAsTheTickLoop() {
		// TickBudget counts hold time in milliseconds, while every clock value this class is given
		// is System.nanoTime(). Handing the budget raw nanos would put its hold clock roughly a
		// million times ahead of the values the tick loop feeds update(), and a level would then
		// never be allowed to drop again. Cheap to get wrong, invisible until a server is running.
		long reloadNanos = START_NANOS + 30_000_000_000L;
		TickPilotServerState state = new TickPilotServerState(START_NANOS,
				config("target_mspt = 25.0\ncritical_mspt = 45.0\n"));

		state.reconfigure(config("target_mspt = 30.0\ncritical_mspt = 60.0\n"), reloadNanos);

		assertEquals(0L, state.budget().heldForMillis(reloadNanos / NANOS_PER_MILLI));
		assertEquals(5_000L, state.budget().heldForMillis(reloadNanos / NANOS_PER_MILLI + 5_000L));
	}

	@Test
	void theInitialBudgetIsOnTheSameClockToo() {
		TickPilotServerState state = new TickPilotServerState(START_NANOS, TickPilotConfig.defaults());

		assertEquals(0L, state.budget().heldForMillis(START_NANOS / NANOS_PER_MILLI));
	}

	@Test
	void aFreshServerWarmsUp() {
		TickPilotServerState state = new TickPilotServerState(START_NANOS, TickPilotConfig.defaults());

		assertTrue(state.budget().isWarmingUp(START_NANOS / NANOS_PER_MILLI));
	}

	@Test
	void aReloadDoesNotStartAnotherWarmUp() {
		// A server being reloaded has been ticking for a while. Warming up again would suppress a
		// genuine CRITICAL for ten seconds right after the operator changed the thresholds.
		long reloadNanos = START_NANOS + 300_000_000_000L;
		TickPilotServerState state = new TickPilotServerState(START_NANOS,
				config("target_mspt = 25.0\ncritical_mspt = 45.0\n"));

		state.reconfigure(config("target_mspt = 30.0\ncritical_mspt = 60.0\n"), reloadNanos);

		assertFalse(state.budget().isWarmingUp(reloadNanos / NANOS_PER_MILLI));
	}

	@Test
	void aRejectedConfigStillProducesAUsableBudget() {
		// AC-15: an inverted pair is repaired by the loader, so TickBudget never sees values its
		// constructor would refuse.
		TickPilotServerState state = new TickPilotServerState(START_NANOS,
				config("target_mspt = 80.0\ncritical_mspt = 10.0\n"));

		assertTrue(state.budget().criticalMspt() > state.budget().targetMspt());
	}

	// --- /tickpilot mode (SPEC FR-12, AC-11) ---------------------------------------------------

	@Test
	void theModeStartsAtWhateverTheConfigSays() {
		TickPilotServerState state = new TickPilotServerState(START_NANOS,
				config("default_mode = \"aggressive\"\n"));

		assertSame(AdaptiveMode.AGGRESSIVE, state.effectiveMode());
		assertFalse(state.isModeOverridden());
	}

	@Test
	void theCommandChangesTheModeAndSaysSo() {
		TickPilotServerState state = new TickPilotServerState(START_NANOS, config(""));

		assertSame(ModeChange.APPLIED, state.setMode(AdaptiveMode.AGGRESSIVE));
		assertSame(AdaptiveMode.AGGRESSIVE, state.effectiveMode());
		assertTrue(state.isModeOverridden());
	}

	/** AC-11: the change applies without a restart, and STRICT stops every intervention at once. */
	@Test
	void switchingToStrictStopsDeferralImmediately() {
		TickPilotServerState state = new TickPilotServerState(START_NANOS, config(""));

		assertTrue(state.scheduler().isDeferralEnabled());

		state.setMode(AdaptiveMode.STRICT);

		assertFalse(state.scheduler().isDeferralEnabled());

		state.setMode(AdaptiveMode.BALANCED);

		assertTrue(state.scheduler().isDeferralEnabled());
	}

	@Test
	void settingTheModeItIsAlreadyInChangesNothing() {
		TickPilotServerState state = new TickPilotServerState(START_NANOS,
				config("default_mode = \"balanced\"\n"));

		assertSame(ModeChange.UNCHANGED, state.setMode(AdaptiveMode.BALANCED));
		assertFalse(state.isModeOverridden());
	}

	/** Going back to what the file asks for leaves a clean state, not one that merely agrees. */
	@Test
	void settingTheModeBackToTheConfiguredOneClearsTheOverride() {
		TickPilotServerState state = new TickPilotServerState(START_NANOS,
				config("default_mode = \"balanced\"\n"));

		state.setMode(AdaptiveMode.AGGRESSIVE);
		assertTrue(state.isModeOverridden());

		assertSame(ModeChange.APPLIED, state.setMode(AdaptiveMode.BALANCED));
		assertFalse(state.isModeOverridden(), "back to the config value is not an override");
		assertSame(AdaptiveMode.BALANCED, state.effectiveMode());
	}

	/**
	 * {@code safe_compatibility_mode} is the operator saying "this server runs no experiments". A
	 * chat command that could defeat it would make the config file a lie.
	 */
	@Test
	void safeCompatibilityModeCannotBeOverriddenByCommand() {
		TickPilotServerState state = new TickPilotServerState(START_NANOS,
				config("safe_compatibility_mode = true\ndefault_mode = \"aggressive\"\n"));

		assertSame(AdaptiveMode.STRICT, state.effectiveMode());

		for (AdaptiveMode mode : AdaptiveMode.values()) {
			assertSame(ModeChange.FORCED_STRICT, state.setMode(mode), "refused for " + mode);
			assertSame(AdaptiveMode.STRICT, state.effectiveMode());
			assertFalse(state.isModeOverridden());
		}
	}

	@Test
	void reloadingTheConfigPutsTheFileBackInCharge() {
		TickPilotServerState state = new TickPilotServerState(START_NANOS,
				config("default_mode = \"balanced\"\n"));

		state.setMode(AdaptiveMode.STRICT);
		assertSame(AdaptiveMode.STRICT, state.effectiveMode());

		state.reconfigure(config("default_mode = \"aggressive\"\n"), START_NANOS);

		assertFalse(state.isModeOverridden(), "reload must drop a mode set by command");
		assertSame(AdaptiveMode.AGGRESSIVE, state.effectiveMode());
	}
}
