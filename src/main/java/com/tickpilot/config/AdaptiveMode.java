package com.tickpilot.config;

import java.util.Locale;

/**
 * How far TickPilot is allowed to go when it intervenes (SPEC FR-11).
 *
 * <p>This phase only parses and validates the value of {@code default_mode}; the behaviour behind
 * each mode is added with the policies in a later phase. Declared here because {@code default_mode}
 * is part of the SPEC FR-15 schema and has to be validated against a closed set now.
 */
public enum AdaptiveMode {
	/** Measure only. No intervention at all; the compatibility mode. */
	STRICT,

	/** Default. Intervene only for allowlisted types and only at HIGH or CRITICAL. */
	BALANCED,

	/** Earlier and stronger thinning, still confined to the allowlist. Opt-in. */
	AGGRESSIVE;

	/**
	 * @param name value as written in the config file; case is ignored
	 * @return the matching mode, or {@code null} when {@code name} is not one of the three. The
	 *         caller reports it and falls back to a default (SPEC AC-15) rather than throwing.
	 */
	public static AdaptiveMode parse(String name) {
		if (name == null) {
			return null;
		}

		for (AdaptiveMode mode : values()) {
			if (mode.name().equalsIgnoreCase(name.trim())) {
				return mode;
			}
		}

		return null;
	}

	/** @return the three accepted spellings, for use in an error message */
	public static String accepted() {
		return String.join(", ", STRICT.name(), BALANCED.name(), AGGRESSIVE.name());
	}

	/** @return the mode name as it is written into the config file */
	public String configValue() {
		return name().toUpperCase(Locale.ROOT);
	}

	/** @return the translation key used to render this mode in player-facing text */
	public String translationKey() {
		return "tickpilot.mode." + name().toLowerCase(Locale.ROOT);
	}
}
