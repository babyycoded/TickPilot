package com.tickpilot.config;

/**
 * Thrown by {@link TomlParser} when the config file is not readable as the supported TOML subset.
 *
 * <p>Checked on purpose: {@link ConfigLoader} is the only caller and SPEC AC-15 requires it to
 * handle this case rather than let it escape, so the compiler should insist.
 */
final class TomlSyntaxException extends Exception {
	private static final long serialVersionUID = 1L;

	private final int line;

	TomlSyntaxException(int line, String message) {
		super("line " + line + ": " + message);
		this.line = line;
	}

	/** @return the 1-based line the parser gave up on */
	int line() {
		return line;
	}
}
