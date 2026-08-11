package com.tickpilot;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;

/**
 * SPEC FR-18: nothing on the common/server side may name a client class.
 *
 * <h2>What this adds over the compiler, which was measured rather than assumed</h2>
 * {@code splitEnvironmentSourceSets()} already keeps client-only Minecraft classes off the main
 * compile classpath, so an actual {@code import net.minecraft.client.Minecraft} in a common class
 * fails to compile on its own — that was checked by putting one there. This test is not what stops
 * that case, and claiming otherwise would be taking credit for the build.
 *
 * <p>What it does stop is everything the compiler cannot see, also checked by probe:
 * <ul>
 *   <li>a client class named in a string literal and reached reflectively, which compiles and then
 *       throws {@code ClassNotFoundException} on a dedicated server;</li>
 *   <li>a client class named in a comment or a Javadoc link, which compiles, says nothing, and is
 *       the name the next person copies into code.</li>
 * </ul>
 *
 * <p>The trade is that this must not flag the prose that <em>explains</em> the rule, so a line
 * carrying a marker comment is exempt — see {@link #ALLOWED_MARKER}.
 */
class SideSeparationTest {
	/**
	 * A line carrying this marker is documentation about the rule rather than a use of it. Kept
	 * deliberately ugly so that it cannot be typed by accident and cannot be mistaken for a
	 * suppression that silences a real problem.
	 */
	private static final String ALLOWED_MARKER = "// FR-18-mentions-client-on-purpose";

	private static final Path MAIN_SOURCES = Path.of("src", "main", "java");
	private static final Path CLIENT_SOURCES = Path.of("src", "client", "java");

	/** Any mention of a client-only package, in code or in prose. */
	private static final Pattern FORBIDDEN =
			Pattern.compile("net\\.minecraft\\.client|com\\.tickpilot\\.client");

	/**
	 * Roughly how many common source files exist. Not a count to keep in step with the tree — it is
	 * a floor, and its only job is to make "the scan found nothing" distinguishable from "the scan
	 * found no files".
	 */
	private static final int MIN_EXPECTED_COMMON_SOURCES = 20;

	@Test
	void noCommonSourceFileNamesAClientClass() {
		List<String> offences = new ArrayList<>();
		List<Path> scanned = javaFilesUnder(MAIN_SOURCES);

		assertTrue(scanned.size() >= MIN_EXPECTED_COMMON_SOURCES,
				"only " + scanned.size() + " common source file(s) were scanned; a scanner that "
						+ "reads nothing passes for the wrong reason");

		for (Path file : scanned) {
			List<String> lines;

			try {
				lines = Files.readAllLines(file, StandardCharsets.UTF_8);
			} catch (IOException e) {
				throw new UncheckedIOException(e);
			}

			for (int i = 0; i < lines.size(); i++) {
				String line = lines.get(i);

				if (line.contains(ALLOWED_MARKER)) {
					continue;
				}

				Matcher matcher = FORBIDDEN.matcher(line);

				if (matcher.find()) {
					offences.add(file + ":" + (i + 1) + " names " + matcher.group() + " - "
							+ line.trim());
				}
			}
		}

		if (!offences.isEmpty()) {
			fail("SPEC FR-18: common/server code must not name a client class. A dedicated server "
					+ "never loads these, so a reference here is a crash waiting for somebody else's "
					+ "server:\n  " + String.join("\n  ", offences));
		}
	}

	/**
	 * The other half of the split, and the reason the scan above is worth anything: if the client
	 * source set were empty, the test would pass by having nothing to separate from.
	 */
	@Test
	void theClientSourceSetExistsAndIsWhereTheClientCodeLives() {
		List<Path> clientFiles = javaFilesUnder(CLIENT_SOURCES);

		assertTrue(!clientFiles.isEmpty(), "no client sources found under " + CLIENT_SOURCES
				+ "; this test is meaningless if the client side is empty");

		for (Path file : clientFiles) {
			assertTrue(file.toString().replace('\\', '/').contains("com/tickpilot/client/"),
					file + " is in the client source set but outside com.tickpilot.client");
		}
	}

	private static List<Path> javaFilesUnder(Path root) {
		assertTrue(Files.isDirectory(root), root + " is missing; run this from the project root");

		try (Stream<Path> files = Files.walk(root)) {
			return files.filter(path -> path.toString().endsWith(".java")).toList();
		} catch (IOException e) {
			throw new UncheckedIOException(e);
		}
	}
}
