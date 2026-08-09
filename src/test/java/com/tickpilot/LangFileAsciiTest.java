package com.tickpilot;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

/**
 * Keeps player-facing strings inside ASCII.
 *
 * <p>Command feedback is echoed to the server console, and a Windows console running under a
 * legacy code page (CP866, CP437, ...) renders anything above ASCII as mojibake — an em dash
 * shows up as {@code ΓÇö}. The mod cannot fix the operator's code page, so it stays inside the
 * character set every code page agrees on. Found in manual testing of Phase 3.
 */
class LangFileAsciiTest {
	private static final String LANG_PATH = "/assets/tickpilot/lang/en_us.json";

	@Test
	void englishLangFileIsPureAscii() throws IOException {
		String content;

		try (InputStream stream = LangFileAsciiTest.class.getResourceAsStream(LANG_PATH)) {
			assertNotNull(stream, LANG_PATH + " must be on the classpath");
			content = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
		}

		List<String> offenders = new ArrayList<>();

		for (String line : content.split("\n")) {
			for (int i = 0; i < line.length(); i++) {
				char c = line.charAt(i);

				if (c > 0x7F) {
					offenders.add("'" + c + "' (U+%04X) in: %s".formatted((int) c, line.trim()));
					break;
				}
			}
		}

		assertTrue(offenders.isEmpty(),
				"non-ASCII characters render as mojibake on a legacy console code page: " + offenders);
	}
}
