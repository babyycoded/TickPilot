package com.tickpilot.config;

import java.util.List;
import java.util.Locale;

/**
 * Renders a {@link TickPilotConfig} as the commented {@code config/tickpilot.toml} of SPEC FR-15.
 *
 * <p>Only ever used to create the file when it is missing. An existing file is never rewritten,
 * because AC-15 forbids overwriting a config the operator edited — including one that failed to
 * parse, which is exactly the file they need to look at to find their typo.
 *
 * <p>Output is pure ASCII for the same reason {@code en_us.json} is: the file is opened in
 * whatever editor and console code page the server operator happens to have, and non-ASCII
 * comments would come back as mojibake. Enforced by {@code TomlWriterTest}.
 *
 * <p>Everything written here is readable by {@link TomlParser} — the round-trip is a test.
 */
final class TomlWriter {
	private static final String NL = System.lineSeparator();

	private TomlWriter() {
	}

	/**
	 * @param config the values to write; assumed already valid
	 * @return the complete file contents, comments included
	 */
	static String write(TickPilotConfig config) {
		StringBuilder out = new StringBuilder(4096);

		comment(out, "TickPilot configuration (config/tickpilot.toml).");
		comment(out, "");
		comment(out, "This file is created with defaults only when it does not exist. It is never");
		comment(out, "rewritten: if a value is rejected the mod logs it and uses the default for that");
		comment(out, "one field, and if the whole file fails to parse the mod logs the line number and");
		comment(out, "runs on defaults. Your file is left alone either way.");
		comment(out, "");
		comment(out, "Apply changes at runtime with /tickpilot reload.");
		comment(out, "");
		comment(out, "Supported syntax is a subset of TOML: key = value, the [lists] table, numbers,");
		comment(out, "true/false, double-quoted strings and arrays of them, and # comments. Literal");
		comment(out, "'single-quoted' strings, inline tables, dotted keys and dates are not supported.");
		out.append(NL);

		section(out, "Tick budget");
		comment(out, "target_mspt    ms per tick above which the load level leaves NORMAL.");
		comment(out, "critical_mspt  ms per tick at which the load level becomes CRITICAL.");
		comment(out, "               Must be greater than target_mspt.");
		comment(out, "reserve_mspt   ms per tick the scheduler keeps free. May be 0.");
		number(out, "target_mspt", config.targetMspt());
		number(out, "critical_mspt", config.criticalMspt());
		number(out, "reserve_mspt", config.reserveMspt());
		out.append(NL);

		section(out, "Activity zones");
		comment(out, "Distance in blocks to the nearest player. Inside full_radius nothing is ever");
		comment(out, "thinned; beyond reduced_radius the most thinning is allowed. reduced_radius");
		comment(out, "must be greater than full_radius, and both must be greater than 0.");
		integer(out, "full_radius", config.fullRadius());
		integer(out, "reduced_radius", config.reducedRadius());
		integer(out, "min_entity_update_interval_ticks", config.minEntityUpdateIntervalTicks());
		integer(out, "min_block_entity_update_interval_ticks", config.minBlockEntityUpdateIntervalTicks());
		out.append(NL);

		section(out, "Scheduler");
		comment(out, "default_mode is one of " + AdaptiveMode.accepted() + ".");
		comment(out, "STRICT measures only and never intervenes.");
		bool(out, "enable_adaptive_mode", config.enableAdaptiveMode());
		string(out, "default_mode", config.defaultMode().configValue());
		integer(out, "max_deferred_tasks", config.maxDeferredTasks());
		integer(out, "max_chunk_operations_per_tick", config.maxChunkOperationsPerTick());
		out.append(NL);

		section(out, "Profiling");
		comment(out, "profile_buffer_size is the number of samples a profiling session retains.");
		comment(out, "log_slow_operations_above_ms must be greater than 0; a threshold of 0 would");
		comment(out, "mean logging every tick, which the mod does not do.");
		integer(out, "profile_buffer_size", config.profileBufferSize());
		number(out, "log_slow_operations_above_ms", config.logSlowOperationsAboveMs());
		bool(out, "sampling_enabled", config.samplingEnabled());
		out.append(NL);

		section(out, "Environment");
		comment(out, "safe_compatibility_mode = true forces STRICT regardless of default_mode.");
		bool(out, "singleplayer_enabled", config.singleplayerEnabled());
		bool(out, "client_hud_enabled", config.clientHudEnabled());
		bool(out, "integrated_server_optimizations", config.integratedServerOptimizations());
		bool(out, "safe_compatibility_mode", config.safeCompatibilityMode());
		out.append(NL);

		comment(out, "Identifiers such as \"minecraft:villager\" or, for excluded_mod_ids, bare");
		comment(out, "namespaces such as \"create\". throttle_denylist outranks throttle_allowlist.");
		out.append("[lists]").append(NL);
		array(out, "excluded_entity_ids", config.excludedEntityIds());
		array(out, "excluded_block_entity_ids", config.excludedBlockEntityIds());
		array(out, "excluded_mod_ids", config.excludedModIds());
		array(out, "throttle_allowlist", config.throttleAllowlist());
		array(out, "throttle_denylist", config.throttleDenylist());

		return out.toString();
	}

	private static void section(StringBuilder out, String title) {
		out.append("# --- ").append(title).append(" ---").append(NL);
	}

	private static void comment(StringBuilder out, String text) {
		if (text.isEmpty()) {
			out.append('#').append(NL);
			return;
		}

		out.append("# ").append(text).append(NL);
	}

	private static void number(StringBuilder out, String key, double value) {
		// Double.toString always emits a '.' or an exponent, so the value reads back as a float
		// rather than as an integer.
		out.append(key).append(" = ").append(Double.toString(value)).append(NL);
	}

	private static void integer(StringBuilder out, String key, int value) {
		out.append(key).append(" = ").append(value).append(NL);
	}

	private static void bool(StringBuilder out, String key, boolean value) {
		out.append(key).append(" = ").append(value).append(NL);
	}

	private static void string(StringBuilder out, String key, String value) {
		out.append(key).append(" = ").append(quote(value)).append(NL);
	}

	private static void array(StringBuilder out, String key, List<String> values) {
		out.append(key).append(" = [");

		for (int i = 0; i < values.size(); i++) {
			if (i > 0) {
				out.append(", ");
			}

			out.append(quote(values.get(i)));
		}

		out.append(']').append(NL);
	}

	/** Quotes and escapes exactly the characters {@link TomlParser} knows how to read back. */
	private static String quote(String value) {
		StringBuilder out = new StringBuilder(value.length() + 2);
		out.append('"');

		for (int i = 0; i < value.length(); i++) {
			char c = value.charAt(i);

			switch (c) {
				case '"' -> out.append("\\\"");
				case '\\' -> out.append("\\\\");
				case '\b' -> out.append("\\b");
				case '\t' -> out.append("\\t");
				case '\n' -> out.append("\\n");
				case '\f' -> out.append("\\f");
				case '\r' -> out.append("\\r");
				default -> {
					if (c < 0x20 || c > 0x7E) {
						out.append("\\u").append(String.format(Locale.ROOT, "%04X", (int) c));
					} else {
						out.append(c);
					}
				}
			}
		}

		return out.append('"').toString();
	}
}
