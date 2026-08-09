package com.tickpilot.config;

import java.util.List;

/**
 * What came back from one attempt to load {@code config/tickpilot.toml}.
 *
 * <p>There is always a usable {@link #config()} — SPEC AC-15 forbids a config problem from being
 * fatal — so the interesting part is {@link #status()} and {@link #problems()}, which
 * {@code /tickpilot reload} turns into a message for the operator and the mod logs once at
 * startup.
 *
 * @param config   the values to run on; the SPEC FR-15 defaults if the file could not be used
 * @param status   how the load went
 * @param problems one human-readable line per rejected value or per failure; empty when clean
 */
public record ConfigLoadResult(TickPilotConfig config, Status status, List<String> problems) {

	/** How a load attempt ended. */
	public enum Status {
		/** No file existed, so one was written with the defaults. Nothing was rejected. */
		CREATED,

		/** No file existed and writing one failed. Running on defaults; see {@code problems}. */
		CREATE_FAILED,

		/** The file was read and every value was accepted. */
		LOADED,

		/** The file was read, but some values were rejected and replaced by their defaults. */
		LOADED_WITH_PROBLEMS,

		/**
		 * The file could not be read or could not be parsed. Everything falls back to the
		 * defaults and the file is left exactly as it is (SPEC AC-15).
		 */
		UNREADABLE
	}

	/** Copies the problem list so a result cannot be mutated after the fact. */
	public ConfigLoadResult {
		problems = List.copyOf(problems);
	}

	/** @return {@code true} when the file was read and nothing had to be corrected */
	public boolean isClean() {
		return problems.isEmpty();
	}
}
