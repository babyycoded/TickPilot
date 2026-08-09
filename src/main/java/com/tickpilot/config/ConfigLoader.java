package com.tickpilot.config;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.tickpilot.config.TomlParser.TomlEntry;

/**
 * Reads, validates and — only when it is missing — creates {@code config/tickpilot.toml}
 * (SPEC FR-15, AC-15).
 *
 * <h2>The three failure modes AC-15 names, and what each does</h2>
 * <ul>
 *   <li><b>File missing.</b> Written once with the defaults and the schema comments.</li>
 *   <li><b>File unreadable or unparseable.</b> Everything falls back to the defaults and the file
 *       is <em>not</em> touched. Overwriting it would destroy the very text the operator has to
 *       look at to find their mistake.</li>
 *   <li><b>A single value invalid.</b> That one field falls back to its default and is reported;
 *       every other value from the file is kept. A bad radius does not cost you your thresholds.</li>
 * </ul>
 * Nothing here throws at the caller: a config problem must never take a server down (SPEC INV-9).
 *
 * <p>Takes a {@link Path} rather than asking Fabric Loader where the config directory is, so the
 * whole thing is unit-tested against a temporary directory with no game running (SPEC §8). The
 * entrypoint resolves the real path.
 */
public final class ConfigLoader {
	/** Name of the config file inside the Fabric config directory. */
	public static final String FILE_NAME = "tickpilot.toml";

	private ConfigLoader() {
	}

	/**
	 * Loads the config, creating it with defaults if it does not exist yet.
	 *
	 * @param file path to {@code tickpilot.toml}
	 * @return the values to run on plus everything that went wrong; never {@code null}, and the
	 *         config inside is always usable
	 */
	public static ConfigLoadResult load(Path file) {
		String text;

		try {
			text = Files.readString(file, StandardCharsets.UTF_8);
		} catch (NoSuchFileException e) {
			return create(file);
		} catch (IOException | RuntimeException e) {
			// Covers a directory in the file's place, a permission problem and bytes that are not
			// UTF-8 (MalformedInputException).
			return fallback(ConfigLoadResult.Status.UNREADABLE,
					"could not read " + FILE_NAME + ": " + describe(e));
		}

		return read(text);
	}

	/**
	 * Parses and validates config text without touching a filesystem. Split out from
	 * {@link #load(Path)} so the validation rules can be exercised directly.
	 *
	 * @param text the file contents
	 * @return the values to run on plus everything that went wrong; never {@code null}
	 */
	public static ConfigLoadResult read(String text) {
		Map<String, TomlEntry> entries;

		try {
			entries = TomlParser.parse(text);
		} catch (TomlSyntaxException e) {
			return fallback(ConfigLoadResult.Status.UNREADABLE,
					"could not parse " + FILE_NAME + ", " + e.getMessage());
		}

		Values values = new Values(entries);
		TickPilotConfig config = validate(values);

		return new ConfigLoadResult(config,
				values.problems.isEmpty()
						? ConfigLoadResult.Status.LOADED
						: ConfigLoadResult.Status.LOADED_WITH_PROBLEMS,
				values.problems);
	}

	private static TickPilotConfig validate(Values v) {
		double target = v.positiveNumber("target_mspt", TickPilotConfig.DEFAULT_TARGET_MSPT);
		double critical = v.positiveNumber("critical_mspt", TickPilotConfig.DEFAULT_CRITICAL_MSPT);
		double reserve = v.nonNegativeNumber("reserve_mspt", TickPilotConfig.DEFAULT_RESERVE_MSPT);

		// An empty or inverted band is the SPEC §13 entry #7 failure all over again: with
		// critical <= target the ELEVATED and HIGH levels do not exist and TickBudget refuses the
		// values outright, so the pair has to be repaired here before it gets that far.
		if (!(critical > target)) {
			if (TickPilotConfig.DEFAULT_CRITICAL_MSPT > target) {
				v.problems.add("critical_mspt (" + num(critical) + ") must be greater than target_mspt ("
						+ num(target) + "); using the default " + num(TickPilotConfig.DEFAULT_CRITICAL_MSPT));
				critical = TickPilotConfig.DEFAULT_CRITICAL_MSPT;
			} else {
				v.problems.add("critical_mspt (" + num(critical) + ") must be greater than target_mspt ("
						+ num(target) + "); using the defaults " + num(TickPilotConfig.DEFAULT_TARGET_MSPT)
						+ " and " + num(TickPilotConfig.DEFAULT_CRITICAL_MSPT) + " for both");
				target = TickPilotConfig.DEFAULT_TARGET_MSPT;
				critical = TickPilotConfig.DEFAULT_CRITICAL_MSPT;
			}
		}

		int fullRadius = v.intAtLeast("full_radius", 1, TickPilotConfig.DEFAULT_FULL_RADIUS);
		int reducedRadius = v.intAtLeast("reduced_radius", 1, TickPilotConfig.DEFAULT_REDUCED_RADIUS);

		// Same reasoning as the MSPT pair: full >= reduced leaves the REDUCED zone of FR-7 empty.
		if (reducedRadius <= fullRadius) {
			if (TickPilotConfig.DEFAULT_REDUCED_RADIUS > fullRadius) {
				v.problems.add("reduced_radius (" + reducedRadius + ") must be greater than full_radius ("
						+ fullRadius + "); using the default " + TickPilotConfig.DEFAULT_REDUCED_RADIUS);
				reducedRadius = TickPilotConfig.DEFAULT_REDUCED_RADIUS;
			} else {
				v.problems.add("reduced_radius (" + reducedRadius + ") must be greater than full_radius ("
						+ fullRadius + "); using the defaults " + TickPilotConfig.DEFAULT_FULL_RADIUS
						+ " and " + TickPilotConfig.DEFAULT_REDUCED_RADIUS + " for both");
				fullRadius = TickPilotConfig.DEFAULT_FULL_RADIUS;
				reducedRadius = TickPilotConfig.DEFAULT_REDUCED_RADIUS;
			}
		}

		TickPilotConfig config = new TickPilotConfig(
				target,
				critical,
				reserve,
				fullRadius,
				reducedRadius,
				v.intAtLeast("min_entity_update_interval_ticks", 1,
						TickPilotConfig.DEFAULT_MIN_ENTITY_UPDATE_INTERVAL_TICKS),
				v.intAtLeast("min_block_entity_update_interval_ticks", 1,
						TickPilotConfig.DEFAULT_MIN_BLOCK_ENTITY_UPDATE_INTERVAL_TICKS),
				v.bool("enable_adaptive_mode", TickPilotConfig.DEFAULT_ENABLE_ADAPTIVE_MODE),
				v.mode("default_mode", TickPilotConfig.DEFAULT_MODE),
				v.intAtLeast("max_deferred_tasks", 1, TickPilotConfig.DEFAULT_MAX_DEFERRED_TASKS),
				v.intAtLeast("max_chunk_operations_per_tick", 1,
						TickPilotConfig.DEFAULT_MAX_CHUNK_OPERATIONS_PER_TICK),
				v.intAtLeast("profile_buffer_size", 1, TickPilotConfig.DEFAULT_PROFILE_BUFFER_SIZE),
				v.positiveNumber("log_slow_operations_above_ms",
						TickPilotConfig.DEFAULT_LOG_SLOW_OPERATIONS_ABOVE_MS),
				v.bool("sampling_enabled", TickPilotConfig.DEFAULT_SAMPLING_ENABLED),
				v.bool("singleplayer_enabled", TickPilotConfig.DEFAULT_SINGLEPLAYER_ENABLED),
				v.bool("client_hud_enabled", TickPilotConfig.DEFAULT_CLIENT_HUD_ENABLED),
				v.bool("integrated_server_optimizations",
						TickPilotConfig.DEFAULT_INTEGRATED_SERVER_OPTIMIZATIONS),
				v.bool("safe_compatibility_mode", TickPilotConfig.DEFAULT_SAFE_COMPATIBILITY_MODE),
				v.strings("lists.excluded_entity_ids"),
				v.strings("lists.excluded_block_entity_ids"),
				v.strings("lists.excluded_mod_ids"),
				v.strings("lists.throttle_allowlist"),
				v.strings("lists.throttle_denylist"));

		// Last, so a typo is reported after the values it sits between.
		v.reportUnknownKeys();
		return config;
	}

	/** Writes the default file. Never called when the file already exists. */
	private static ConfigLoadResult create(Path file) {
		TickPilotConfig defaults = TickPilotConfig.defaults();

		try {
			Path parent = file.getParent();

			if (parent != null) {
				Files.createDirectories(parent);
			}

			// CREATE_NEW, not CREATE: if the file turned up between the read above and this write,
			// it belongs to the operator and AC-15 says we do not overwrite it.
			Files.writeString(file, TomlWriter.write(defaults), StandardCharsets.UTF_8,
					StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);

			return new ConfigLoadResult(defaults, ConfigLoadResult.Status.CREATED, List.of());
		} catch (IOException | RuntimeException e) {
			return new ConfigLoadResult(defaults, ConfigLoadResult.Status.CREATE_FAILED,
					List.of("could not create " + FILE_NAME + ": " + describe(e)));
		}
	}

	private static ConfigLoadResult fallback(ConfigLoadResult.Status status, String problem) {
		return new ConfigLoadResult(TickPilotConfig.defaults(), status, List.of(problem));
	}

	private static String describe(Throwable t) {
		String message = t.getMessage();
		return message == null || message.isBlank()
				? t.getClass().getSimpleName()
				: t.getClass().getSimpleName() + ": " + message;
	}

	private static String num(double value) {
		return Double.toString(value);
	}

	/**
	 * Pulls typed values out of the parsed entries, recording a problem instead of failing when
	 * one is of the wrong type or out of range.
	 *
	 * <p>Every accepted key is marked as consumed, so whatever is left over at the end is an
	 * unknown key. Absent keys are silently defaulted and never reported: a config written by an
	 * older version of the mod is missing keys by definition, and nagging about that would train
	 * operators to ignore the warnings that matter.
	 */
	private static final class Values {
		private final Map<String, TomlEntry> entries;
		private final Set<String> consumed = new LinkedHashSet<>();
		private final List<String> problems = new ArrayList<>();

		Values(Map<String, TomlEntry> entries) {
			this.entries = entries;
		}

		double positiveNumber(String key, double fallback) {
			return boundedNumber(key, fallback, false);
		}

		double nonNegativeNumber(String key, double fallback) {
			return boundedNumber(key, fallback, true);
		}

		private double boundedNumber(String key, double fallback, boolean zeroAllowed) {
			TomlEntry entry = take(key);

			if (entry == null) {
				return fallback;
			}

			double value;

			if (entry.value() instanceof Double d) {
				value = d;
			} else if (entry.value() instanceof Long l) {
				// `target_mspt = 40` is a perfectly reasonable thing to write.
				value = l;
			} else {
				reject(key, entry, "expected a number", num(fallback));
				return fallback;
			}

			if (!Double.isFinite(value) || (zeroAllowed ? value < 0.0 : value <= 0.0)) {
				reject(key, entry, zeroAllowed ? "must not be negative" : "must be greater than 0",
						num(fallback));
				return fallback;
			}

			return value;
		}

		int intAtLeast(String key, int min, int fallback) {
			TomlEntry entry = take(key);

			if (entry == null) {
				return fallback;
			}

			if (!(entry.value() instanceof Long value)) {
				reject(key, entry, "expected a whole number", Integer.toString(fallback));
				return fallback;
			}

			if (value < min || value > Integer.MAX_VALUE) {
				reject(key, entry, "must be between " + min + " and " + Integer.MAX_VALUE,
						Integer.toString(fallback));
				return fallback;
			}

			return value.intValue();
		}

		boolean bool(String key, boolean fallback) {
			TomlEntry entry = take(key);

			if (entry == null) {
				return fallback;
			}

			if (!(entry.value() instanceof Boolean value)) {
				reject(key, entry, "expected true or false", Boolean.toString(fallback));
				return fallback;
			}

			return value;
		}

		AdaptiveMode mode(String key, AdaptiveMode fallback) {
			TomlEntry entry = take(key);

			if (entry == null) {
				return fallback;
			}

			if (!(entry.value() instanceof String raw)) {
				reject(key, entry, "expected one of " + AdaptiveMode.accepted() + " in quotes",
						fallback.configValue());
				return fallback;
			}

			AdaptiveMode mode = AdaptiveMode.parse(raw);

			if (mode == null) {
				reject(key, entry, "expected one of " + AdaptiveMode.accepted(), fallback.configValue());
				return fallback;
			}

			return mode;
		}

		List<String> strings(String key) {
			TomlEntry entry = take(key);

			if (entry == null) {
				return List.of();
			}

			if (!(entry.value() instanceof List<?> raw)) {
				reject(key, entry, "expected an array of quoted strings", "an empty list");
				return List.of();
			}

			List<String> out = new ArrayList<>(raw.size());

			for (Object item : raw) {
				if (!(item instanceof String text)) {
					problems.add(key + " (line " + entry.line() + "): entry " + display(item)
							+ " is not a quoted string; skipping it");
					continue;
				}

				String trimmed = text.trim();

				if (trimmed.isEmpty()) {
					problems.add(key + " (line " + entry.line() + "): skipping a blank entry");
					continue;
				}

				out.add(trimmed);
			}

			return out;
		}

		void reportUnknownKeys() {
			for (TomlEntry entry : entries.values()) {
				if (!consumed.contains(entry.key())) {
					problems.add("unknown key '" + entry.key() + "' (line " + entry.line()
							+ "); ignoring it");
				}
			}
		}

		private TomlEntry take(String key) {
			consumed.add(key);
			return entries.get(key);
		}

		private void reject(String key, TomlEntry entry, String requirement, String fallback) {
			problems.add(key + " (line " + entry.line() + "): " + requirement + ", got "
					+ display(entry.value()) + "; using " + fallback);
		}

		private static String display(Object value) {
			if (value instanceof String text) {
				return '"' + text + '"';
			}

			if (value instanceof List<?> list) {
				return "an array of " + list.size() + " item(s)";
			}

			return String.valueOf(value);
		}
	}
}
