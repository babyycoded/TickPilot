package com.tickpilot.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;

import com.tickpilot.config.ConfigLoadResult.Status;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * SPEC FR-15 and AC-15. The three things worth breaking a build over are: a bad file never takes
 * the server down, a bad file is never overwritten, and one bad value never costs the operator
 * the rest of their settings.
 */
class ConfigLoaderTest {

	private static ConfigLoadResult read(String text) {
		return ConfigLoader.read(text);
	}

	/** Asserts that {@code problems} mentions {@code key}, and returns the matching line. */
	private static String problemAbout(ConfigLoadResult result, String key) {
		for (String problem : result.problems()) {
			if (problem.startsWith(key + " ") || problem.startsWith(key + "(")) {
				return problem;
			}
		}

		throw new AssertionError("no problem reported for '" + key + "' in " + result.problems());
	}

	// --- defaults ---------------------------------------------------------------------------

	@Test
	void defaultsMatchTheSpecSchema() {
		TickPilotConfig c = TickPilotConfig.defaults();

		assertEquals(40.0, c.targetMspt());
		assertEquals(50.0, c.criticalMspt());
		assertEquals(10.0, c.reserveMspt());
		assertEquals(32, c.fullRadius());
		assertEquals(96, c.reducedRadius());
		assertEquals(1, c.minEntityUpdateIntervalTicks());
		assertEquals(1, c.minBlockEntityUpdateIntervalTicks());
		assertTrue(c.enableAdaptiveMode());
		assertEquals(AdaptiveMode.BALANCED, c.defaultMode());
		assertEquals(10_000, c.maxDeferredTasks());
		assertEquals(8, c.maxChunkOperationsPerTick());
		assertEquals(1200, c.profileBufferSize());
		assertEquals(2.0, c.logSlowOperationsAboveMs());
		assertFalse(c.samplingEnabled());
		assertTrue(c.singleplayerEnabled());
		assertFalse(c.clientHudEnabled());
		assertTrue(c.integratedServerOptimizations());
		assertFalse(c.safeCompatibilityMode());
		assertEquals(List.of(), c.excludedEntityIds());
		assertEquals(List.of(), c.excludedBlockEntityIds());
		assertEquals(List.of(), c.excludedModIds());
		assertEquals(List.of(), c.throttleAllowlist());
		assertEquals(List.of(), c.throttleDenylist());
	}

	@Test
	void anEmptyFileYieldsTheDefaultsWithoutComplaining() {
		ConfigLoadResult result = read("");

		assertEquals(Status.LOADED, result.status());
		assertEquals(TickPilotConfig.defaults(), result.config());
	}

	@Test
	void aMissingKeyIsNotReported() {
		// An operator upgrading from an older version has a file with keys missing by definition.
		ConfigLoadResult result = read("target_mspt = 30.0\n");

		assertTrue(result.isClean(), result.problems().toString());
		assertEquals(30.0, result.config().targetMspt());
		assertEquals(50.0, result.config().criticalMspt());
	}

	@Test
	void safeCompatibilityModeForcesStrict() {
		ConfigLoadResult result = read("""
				default_mode = "AGGRESSIVE"
				safe_compatibility_mode = true
				""");

		assertEquals(AdaptiveMode.AGGRESSIVE, result.config().defaultMode());
		assertEquals(AdaptiveMode.STRICT, result.config().effectiveMode());
	}

	// --- a good file ------------------------------------------------------------------------

	@Test
	void readsAFullyPopulatedFile() {
		ConfigLoadResult result = read("""
				# --- Tick budget ---
				target_mspt = 35.0
				critical_mspt = 48.5
				reserve_mspt = 0.0

				full_radius = 16
				reduced_radius = 64
				min_entity_update_interval_ticks = 2
				min_block_entity_update_interval_ticks = 4

				enable_adaptive_mode = false
				default_mode = "strict"
				max_deferred_tasks = 500
				max_chunk_operations_per_tick = 1

				profile_buffer_size = 600
				log_slow_operations_above_ms = 5.0
				sampling_enabled = true

				singleplayer_enabled = false
				client_hud_enabled = true
				integrated_server_optimizations = false
				safe_compatibility_mode = true

				[lists]
				excluded_entity_ids = ["minecraft:villager"]
				excluded_block_entity_ids = ["minecraft:hopper", "minecraft:chest"]
				excluded_mod_ids = ["create"]
				throttle_allowlist = ["minecraft:zombie"]
				throttle_denylist = ["minecraft:armor_stand"]
				""");

		assertEquals(Status.LOADED, result.status());
		assertTrue(result.isClean(), result.problems().toString());

		TickPilotConfig c = result.config();
		assertEquals(35.0, c.targetMspt());
		assertEquals(48.5, c.criticalMspt());
		assertEquals(0.0, c.reserveMspt());
		assertEquals(16, c.fullRadius());
		assertEquals(64, c.reducedRadius());
		assertEquals(2, c.minEntityUpdateIntervalTicks());
		assertEquals(4, c.minBlockEntityUpdateIntervalTicks());
		assertFalse(c.enableAdaptiveMode());
		assertEquals(AdaptiveMode.STRICT, c.defaultMode());
		assertEquals(500, c.maxDeferredTasks());
		assertEquals(1, c.maxChunkOperationsPerTick());
		assertEquals(600, c.profileBufferSize());
		assertEquals(5.0, c.logSlowOperationsAboveMs());
		assertTrue(c.samplingEnabled());
		assertFalse(c.singleplayerEnabled());
		assertTrue(c.clientHudEnabled());
		assertFalse(c.integratedServerOptimizations());
		assertTrue(c.safeCompatibilityMode());
		assertEquals(List.of("minecraft:villager"), c.excludedEntityIds());
		assertEquals(List.of("minecraft:hopper", "minecraft:chest"), c.excludedBlockEntityIds());
		assertEquals(List.of("create"), c.excludedModIds());
		assertEquals(List.of("minecraft:zombie"), c.throttleAllowlist());
		assertEquals(List.of("minecraft:armor_stand"), c.throttleDenylist());
	}

	@Test
	void acceptsAWholeNumberForAFloatField() {
		ConfigLoadResult result = read("target_mspt = 30\ncritical_mspt = 45\n");

		assertTrue(result.isClean(), result.problems().toString());
		assertEquals(30.0, result.config().targetMspt());
		assertEquals(45.0, result.config().criticalMspt());
	}

	// --- a broken file ----------------------------------------------------------------------

	@Test
	void anUnparseableFileFallsBackToDefaultsWithoutThrowing() {
		ConfigLoadResult result = read("target_mspt = = 40.0\n");

		assertEquals(Status.UNREADABLE, result.status());
		assertEquals(TickPilotConfig.defaults(), result.config());
		assertEquals(1, result.problems().size());
	}

	@Test
	void aParseFailureNamesTheLine() {
		ConfigLoadResult result = read("""
				target_mspt = 40.0
				critical_mspt = 50.0
				full_radius = @
				""");

		assertEquals(Status.UNREADABLE, result.status());
		assertTrue(result.problems().get(0).contains("line 3"), result.problems().toString());
	}

	@Test
	void oneBadLineDoesNotSalvageTheGoodValues() {
		// Deliberate: a syntax error means the file as a whole cannot be trusted, so AC-15 says
		// defaults - not a half-applied config the operator cannot predict.
		ConfigLoadResult result = read("target_mspt = 5.0\nbroken here\n");

		assertEquals(Status.UNREADABLE, result.status());
		assertEquals(40.0, result.config().targetMspt());
	}

	// --- invalid values ---------------------------------------------------------------------

	@Test
	void criticalMsptBelowTargetIsRejected() {
		ConfigLoadResult result = read("target_mspt = 45.0\ncritical_mspt = 20.0\n");

		assertEquals(Status.LOADED_WITH_PROBLEMS, result.status());
		assertEquals(45.0, result.config().targetMspt(), "a valid target must survive");
		assertEquals(50.0, result.config().criticalMspt());
		assertTrue(problemAbout(result, "critical_mspt").contains("greater than target_mspt"));
	}

	@Test
	void criticalMsptEqualToTargetIsRejected() {
		// SPEC 13 entry 7: equal thresholds collapse ELEVATED and HIGH to nothing.
		ConfigLoadResult result = read("target_mspt = 30.0\ncritical_mspt = 30.0\n");

		assertEquals(Status.LOADED_WITH_PROBLEMS, result.status());
		assertEquals(30.0, result.config().targetMspt(), "a valid target must survive");
		assertEquals(50.0, result.config().criticalMspt());
	}

	@Test
	void bothThresholdsResetWhenTheDefaultCriticalCannotSaveThePair() {
		// target 80 is valid on its own, but the default critical of 50 is below it, so keeping
		// the operator's target would still leave an inverted pair. Both go back to defaults.
		ConfigLoadResult result = read("target_mspt = 80.0\ncritical_mspt = 10.0\n");

		assertEquals(40.0, result.config().targetMspt());
		assertEquals(50.0, result.config().criticalMspt());
		assertTrue(problemAbout(result, "critical_mspt").contains("for both"));
	}

	@Test
	void reducedRadiusNotAboveFullRadiusIsRejected() {
		ConfigLoadResult result = read("full_radius = 64\nreduced_radius = 64\n");

		assertEquals(64, result.config().fullRadius());
		assertEquals(96, result.config().reducedRadius());
		assertTrue(problemAbout(result, "reduced_radius").contains("greater than full_radius"));
	}

	@Test
	void bothRadiiResetWhenTheDefaultReducedCannotSaveThePair() {
		ConfigLoadResult result = read("full_radius = 200\nreduced_radius = 10\n");

		assertEquals(32, result.config().fullRadius());
		assertEquals(96, result.config().reducedRadius());
	}

	@Test
	void nonPositiveRadiiAndLimitsAreRejected() {
		ConfigLoadResult result = read("""
				full_radius = 0
				reduced_radius = -5
				min_entity_update_interval_ticks = 0
				min_block_entity_update_interval_ticks = -1
				max_deferred_tasks = 0
				max_chunk_operations_per_tick = -8
				profile_buffer_size = 0
				log_slow_operations_above_ms = 0.0
				""");

		assertEquals(Status.LOADED_WITH_PROBLEMS, result.status());
		assertEquals(TickPilotConfig.defaults(), result.config(),
				"every rejected field must fall back to its own default");
		assertEquals(8, result.problems().size(), result.problems().toString());
	}

	@Test
	void aNegativeReserveIsRejectedButZeroIsAllowed() {
		assertTrue(read("reserve_mspt = 0.0\n").isClean());

		ConfigLoadResult negative = read("reserve_mspt = -1.0\n");
		assertEquals(10.0, negative.config().reserveMspt());
		assertTrue(problemAbout(negative, "reserve_mspt").contains("must not be negative"));
	}

	@Test
	void aWrongTypeIsRejectedPerFieldAndTheRestSurvives() {
		ConfigLoadResult result = read("""
				target_mspt = "fast"
				full_radius = 16.5
				sampling_enabled = "yes"
				default_mode = 3
				max_deferred_tasks = 250
				""");

		assertEquals(40.0, result.config().targetMspt());
		assertEquals(32, result.config().fullRadius());
		assertFalse(result.config().samplingEnabled());
		assertEquals(AdaptiveMode.BALANCED, result.config().defaultMode());
		assertEquals(250, result.config().maxDeferredTasks(), "a valid neighbour must survive");

		assertTrue(problemAbout(result, "target_mspt").contains("expected a number"));
		assertTrue(problemAbout(result, "full_radius").contains("expected a whole number"));
		assertTrue(problemAbout(result, "sampling_enabled").contains("true or false"));
		assertTrue(problemAbout(result, "default_mode").contains("STRICT"));
	}

	@Test
	void anUnknownModeIsRejected() {
		ConfigLoadResult result = read("default_mode = \"TURBO\"\n");

		assertEquals(AdaptiveMode.BALANCED, result.config().defaultMode());
		assertTrue(problemAbout(result, "default_mode").contains("TURBO"));
	}

	@Test
	void modeNamesAreCaseInsensitive() {
		assertEquals(AdaptiveMode.AGGRESSIVE, read("default_mode = \"aggressive\"\n").config().defaultMode());
		assertEquals(AdaptiveMode.STRICT, read("default_mode = \" Strict \"\n").config().defaultMode());
	}

	@Test
	void anIntegerTooLargeForTheFieldIsRejected() {
		ConfigLoadResult result = read("max_deferred_tasks = 99999999999\n");

		assertEquals(10_000, result.config().maxDeferredTasks());
		assertTrue(problemAbout(result, "max_deferred_tasks").contains("must be between"));
	}

	@Test
	void badListEntriesAreDroppedIndividually() {
		ConfigLoadResult result = read("""
				[lists]
				excluded_mod_ids = ["create", "", 7, "  farmersdelight  "]
				""");

		assertEquals(List.of("create", "farmersdelight"), result.config().excludedModIds());
		assertEquals(2, result.problems().size(), result.problems().toString());
	}

	@Test
	void aListThatIsNotAnArrayIsRejectedWhole() {
		ConfigLoadResult result = read("[lists]\nexcluded_mod_ids = \"create\"\n");

		assertEquals(List.of(), result.config().excludedModIds());
		assertTrue(problemAbout(result, "lists.excluded_mod_ids").contains("array"));
	}

	@Test
	void unknownKeysAreReportedButHarmless() {
		ConfigLoadResult result = read("""
				target_mspt = 30.0
				targt_mspt = 30.0
				""");

		assertEquals(30.0, result.config().targetMspt());
		assertEquals(1, result.problems().size());
		assertTrue(result.problems().get(0).contains("targt_mspt"), result.problems().toString());
	}

	// --- round trip -------------------------------------------------------------------------

	@Test
	void defaultsSurviveAWriteAndRead() {
		ConfigLoadResult result = read(TomlWriter.write(TickPilotConfig.defaults()));

		assertEquals(Status.LOADED, result.status(), result.problems().toString());
		assertEquals(TickPilotConfig.defaults(), result.config());
	}

	@Test
	void nonDefaultValuesSurviveAWriteAndRead() {
		TickPilotConfig original = new TickPilotConfig(
				12.5, 33.25, 0.0, 8, 9, 3, 7,
				false, AdaptiveMode.AGGRESSIVE, 1, 2, 3, 0.125,
				true, false, true, false, true,
				List.of("minecraft:villager"),
				List.of("minecraft:hopper"),
				List.of("create", "farmersdelight"),
				List.of("minecraft:zombie"),
				List.of("minecraft:armor_stand", "a\"quoted\"id"));

		ConfigLoadResult result = read(TomlWriter.write(original));

		assertTrue(result.isClean(), result.problems().toString());
		assertEquals(original, result.config());
	}

	@Test
	void theGeneratedFileIsPureAsciiAndCommented() {
		String text = TomlWriter.write(TickPilotConfig.defaults());

		for (int i = 0; i < text.length(); i++) {
			char c = text.charAt(i);
			assertTrue(c < 0x80,
					String.format(Locale.ROOT, "non-ASCII U+%04X at index %d", (int) c, i));
		}

		assertTrue(text.contains("# --- Tick budget ---"), "the schema sections must be commented");
		assertTrue(text.contains("[lists]"));
	}

	// --- the filesystem side ------------------------------------------------------------------

	@Test
	void aMissingFileIsCreatedWithDefaults(@TempDir Path dir) throws IOException {
		Path file = dir.resolve("nested").resolve(ConfigLoader.FILE_NAME);

		ConfigLoadResult result = ConfigLoader.load(file);

		assertEquals(Status.CREATED, result.status());
		assertEquals(TickPilotConfig.defaults(), result.config());
		assertTrue(Files.exists(file));

		// And what was written is readable again on the next start.
		ConfigLoadResult reread = ConfigLoader.load(file);
		assertEquals(Status.LOADED, reread.status(), reread.problems().toString());
		assertEquals(TickPilotConfig.defaults(), reread.config());
	}

	@Test
	void aBrokenFileIsLeftExactlyAsItWas(@TempDir Path dir) throws IOException {
		Path file = dir.resolve(ConfigLoader.FILE_NAME);
		String broken = "target_mspt = = 40.0\n# my notes\n";
		Files.writeString(file, broken, StandardCharsets.UTF_8);

		ConfigLoadResult result = ConfigLoader.load(file);

		assertEquals(Status.UNREADABLE, result.status());
		assertEquals(TickPilotConfig.defaults(), result.config());
		assertEquals(broken, Files.readString(file, StandardCharsets.UTF_8),
				"AC-15: a config that failed to parse must not be overwritten");
	}

	@Test
	void aFileWithRejectedValuesIsLeftExactlyAsItWas(@TempDir Path dir) throws IOException {
		Path file = dir.resolve(ConfigLoader.FILE_NAME);
		String text = "full_radius = -1\n";
		Files.writeString(file, text, StandardCharsets.UTF_8);

		ConfigLoadResult result = ConfigLoader.load(file);

		assertEquals(Status.LOADED_WITH_PROBLEMS, result.status());
		assertEquals(text, Files.readString(file, StandardCharsets.UTF_8));
	}

	@Test
	void bytesThatAreNotUtf8AreTreatedAsUnreadable(@TempDir Path dir) throws IOException {
		Path file = dir.resolve(ConfigLoader.FILE_NAME);
		// 0xFF is not a valid UTF-8 lead byte; this is what a UTF-16 file looks like here.
		Files.write(file, new byte[] {(byte) 0xFF, (byte) 0xFE, 't', 0, 'x', 0});

		ConfigLoadResult result = ConfigLoader.load(file);

		assertEquals(Status.UNREADABLE, result.status());
		assertSame(TickPilotConfig.defaults(), result.config());
	}

	@Test
	void aDirectoryInThePlaceOfTheFileIsTreatedAsUnreadable(@TempDir Path dir) throws IOException {
		Path file = dir.resolve(ConfigLoader.FILE_NAME);
		Files.createDirectory(file);

		ConfigLoadResult result = ConfigLoader.load(file);

		assertEquals(Status.UNREADABLE, result.status());
		assertEquals(TickPilotConfig.defaults(), result.config());
	}
}
