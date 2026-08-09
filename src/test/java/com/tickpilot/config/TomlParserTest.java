package com.tickpilot.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;

import com.tickpilot.config.TomlParser.TomlEntry;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Covers the supported TOML subset and, just as importantly, the shape of the refusal for the
 * syntax that is not supported: SPEC AC-15 turns each of these into a log line plus defaults, so
 * a wrong or misleading message here is a real defect.
 */
class TomlParserTest {

	private static Map<String, TomlEntry> parse(String src) throws TomlSyntaxException {
		return TomlParser.parse(src);
	}

	private static Object value(String src, String key) throws TomlSyntaxException {
		TomlEntry entry = parse(src).get(key);
		assertTrue(entry != null, "no entry for '" + key + "'");
		return entry.value();
	}

	@Test
	void readsScalarsWithTheirTypes() throws Exception {
		Map<String, TomlEntry> entries = parse("""
				an_int = 42
				a_float = 40.5
				a_bool = true
				a_string = "minecraft:villager"
				""");

		assertEquals(Long.valueOf(42L), entries.get("an_int").value());
		assertEquals(Double.valueOf(40.5), entries.get("a_float").value());
		assertEquals(Boolean.TRUE, entries.get("a_bool").value());
		assertEquals("minecraft:villager", entries.get("a_string").value());
	}

	@Test
	void keepsIntegersAndFloatsApart() throws Exception {
		// The validator relies on this: an int field written as 32.0 must be rejected, not rounded.
		assertInstanceOf(Long.class, value("x = 32", "x"));
		assertInstanceOf(Double.class, value("x = 32.0", "x"));
	}

	@Test
	void readsSignsExponentsAndDigitSeparators() throws Exception {
		assertEquals(Long.valueOf(-7L), value("x = -7", "x"));
		assertEquals(Long.valueOf(10_000L), value("x = 10_000", "x"));
		assertEquals(Double.valueOf(1.5e3), value("x = 1.5e3", "x"));
		assertEquals(Double.valueOf(-0.5), value("x = -0.5", "x"));
	}

	@Test
	void prefixesKeysWithTheirTable() throws Exception {
		Map<String, TomlEntry> entries = parse("""
				target_mspt = 40.0

				[lists]
				excluded_mod_ids = ["create"]
				""");

		assertEquals(List.of("target_mspt", "lists.excluded_mod_ids"), List.copyOf(entries.keySet()));
	}

	@Test
	void readsEmptyAndPopulatedArrays() throws Exception {
		assertEquals(List.of(), value("x = []", "x"));
		assertEquals(List.of("a", "b"), value("x = [\"a\", \"b\"]", "x"));
	}

	@Test
	void readsArraysThatSpanLinesWithCommentsAndTrailingCommas() throws Exception {
		Object parsed = value("""
				x = [
				  "minecraft:villager",  # chatty
				  # a whole comment line
				  "minecraft:zombie",
				]
				""", "x");

		assertEquals(List.of("minecraft:villager", "minecraft:zombie"), parsed);
	}

	@Test
	void readsStringEscapes() throws Exception {
		assertEquals("a\"b\\c\td", value("x = \"a\\\"b\\\\c\\td\"", "x"));
		assertEquals("\u00e9", value("x = \"\\u00E9\"", "x"));
	}

	@Test
	void ignoresCommentsAndBlankLines() throws Exception {
		Map<String, TomlEntry> entries = parse("""
				# leading comment

				x = 1   # trailing comment

				# trailing comment line
				""");

		assertEquals(1, entries.size());
		assertEquals(Long.valueOf(1L), entries.get("x").value());
	}

	@Test
	void reportsTheLineOfEachEntry() throws Exception {
		Map<String, TomlEntry> entries = parse("""
				# comment
				a = 1
				b = 2
				""");

		assertEquals(2, entries.get("a").line());
		assertEquals(3, entries.get("b").line());
	}

	@Test
	void countsCarriageReturnLineFeedAsOneLine() throws Exception {
		assertEquals(3, parse("a = 1\r\nb = 2\r\nc = 3\r\n").get("c").line());
	}

	@Test
	void toleratesAMissingFinalNewline() throws Exception {
		assertEquals(Long.valueOf(1L), value("x = 1", "x"));
	}

	@Test
	void toleratesAnEmptyFile() throws Exception {
		assertTrue(parse("").isEmpty());
		assertTrue(parse("# only a comment\n").isEmpty());
	}

	@Test
	void rejectsDuplicateKeys() {
		TomlSyntaxException e = assertThrows(TomlSyntaxException.class, () -> parse("x = 1\nx = 2\n"));
		assertEquals(2, e.line());
		assertTrue(e.getMessage().contains("duplicate"), e.getMessage());
	}

	@Test
	void reportsTheOffendingLineNumber() {
		TomlSyntaxException e = assertThrows(TomlSyntaxException.class, () -> parse("""
				a = 1
				b = 2
				c = @
				"""));

		assertEquals(3, e.line());
	}

	/**
	 * Each of these is valid TOML 1.0 that this parser deliberately does not implement. The
	 * message has to name the construct, because "syntax error" would send the operator hunting
	 * for a typo that is not there.
	 */
	@ParameterizedTest
	@ValueSource(strings = {"'quoted'", "{ a = 1 }", "1979-05-27T07:32:00Z", "\"\"\"multi\"\"\"", "0x1F"})
	void refusesUnsupportedSyntaxWithAnExplanation(String literal) {
		TomlSyntaxException e = assertThrows(TomlSyntaxException.class, () -> parse("x = " + literal));
		assertTrue(e.getMessage().contains("not supported") || e.getMessage().contains("not a supported"),
				"unhelpful message for " + literal + ": " + e.getMessage());
	}

	@Test
	void refusesDottedKeys() {
		TomlSyntaxException e = assertThrows(TomlSyntaxException.class, () -> parse("a.b = 1"));
		assertTrue(e.getMessage().contains("dotted"), e.getMessage());
	}

	@Test
	void refusesArraysOfTables() {
		TomlSyntaxException e = assertThrows(TomlSyntaxException.class, () -> parse("[[lists]]\nx = 1"));
		assertTrue(e.getMessage().contains("arrays of tables"), e.getMessage());
	}

	@Test
	void refusesUnterminatedConstructs() {
		assertThrows(TomlSyntaxException.class, () -> parse("x = \"open"));
		assertThrows(TomlSyntaxException.class, () -> parse("x = [\"a\", "));
		assertThrows(TomlSyntaxException.class, () -> parse("[lists\nx = 1"));
		assertThrows(TomlSyntaxException.class, () -> parse("x 1"));
		assertThrows(TomlSyntaxException.class, () -> parse("x ="));
	}

	@Test
	void refusesTrailingRubbishAfterAValue() {
		TomlSyntaxException e = assertThrows(TomlSyntaxException.class, () -> parse("x = 1 2"));
		assertTrue(e.getMessage().contains("after the value"), e.getMessage());
	}

	@Test
	void refusesNumbersThatDoNotFit() {
		assertThrows(TomlSyntaxException.class, () -> parse("x = 99999999999999999999999999"));
		assertThrows(TomlSyntaxException.class, () -> parse("x = 1.0e400"));
	}
}
