package com.tickpilot.config;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Reads the subset of TOML that the SPEC FR-15 schema is written in.
 *
 * <h2>Why a hand-written parser and not a library</h2>
 * There is no TOML parser anywhere on the compile classpath: unlike NeoForge, Fabric Loader does
 * not ship one, so a library would have to be nested into the mod jar. The FR-15 schema is
 * eighteen flat scalars plus one {@code [lists]} table of five string arrays — a closed grammar of
 * a few hundred lines. For a mod whose selling point is being light and not breaking a modpack, a
 * jar-in-jar dependency and its version-conflict risk buys nothing here. Decided with the owner
 * before Phase 4; recorded in SPEC §13.
 *
 * <h2>Supported</h2>
 * <ul>
 *   <li>{@code key = value} with bare keys ({@code A-Z a-z 0-9 _ -});</li>
 *   <li>one level of table header, e.g. {@code [lists]}; keys under it come back dotted,
 *       as {@code lists.excluded_mod_ids};</li>
 *   <li>{@code true} / {@code false};</li>
 *   <li>integers and floats, including a leading sign, exponents and underscore separators;</li>
 *   <li>double-quoted strings, with the standard TOML escapes for quote, backslash, backspace,
 *       tab, newline, form feed, carriage return and the 4- and 8-digit code point forms;</li>
 *   <li>arrays, possibly spanning lines, with a trailing comma allowed;</li>
 *   <li>{@code #} comments and blank lines anywhere.</li>
 * </ul>
 *
 * <h2>Not supported</h2>
 * Single-quoted (literal) strings, multi-line strings, inline tables, arrays of tables, dotted
 * keys, dates and times, and hex/octal/binary integers. Each of these produces a
 * {@link TomlSyntaxException} naming the line and saying what is not supported, which SPEC AC-15
 * turns into a log line plus defaults — never a silent misreading and never a crash.
 *
 * <p>The parser is deliberately schema-agnostic: it does not know which keys exist or what type
 * each should be. {@link ConfigLoader} does that, so a wrong type is a validation problem for one
 * field rather than a syntax error that discards the whole file.
 */
final class TomlParser {
	private final String src;
	private int pos;
	private int line = 1;

	private TomlParser(String src) {
		this.src = src;
	}

	/**
	 * @param src the whole file as text
	 * @return every entry in file order, keyed by its full dotted name
	 * @throws TomlSyntaxException if the text is not in the supported subset
	 */
	static Map<String, TomlEntry> parse(String src) throws TomlSyntaxException {
		return new TomlParser(src).document();
	}

	/** One parsed assignment: the value as {@link Long}, {@link Double}, {@link Boolean},
	 * {@link String} or {@code List<Object>}, plus the line it came from for error messages. */
	record TomlEntry(String key, Object value, int line) {
	}

	private Map<String, TomlEntry> document() throws TomlSyntaxException {
		Map<String, TomlEntry> entries = new LinkedHashMap<>();
		String prefix = "";

		while (true) {
			skipIgnorable();

			if (eof()) {
				return entries;
			}

			if (peek() == '[') {
				prefix = tableHeader() + ".";
				continue;
			}

			int keyLine = line;
			String key = bareKey("a key");
			skipInlineSpace();
			expect('=');
			skipInlineSpace();
			Object value = value();
			endOfLine();

			String full = prefix + key;

			if (entries.putIfAbsent(full, new TomlEntry(full, value, keyLine)) != null) {
				throw new TomlSyntaxException(keyLine, "duplicate key '" + full + "'");
			}
		}
	}

	private String tableHeader() throws TomlSyntaxException {
		int headerLine = line;
		expect('[');

		if (!eof() && peek() == '[') {
			throw new TomlSyntaxException(headerLine, "arrays of tables ([[...]]) are not supported");
		}

		skipInlineSpace();
		String name = bareKey("a table name");
		skipInlineSpace();

		if (eof() || peek() != ']') {
			throw new TomlSyntaxException(headerLine,
					"expected ']' to close the table header '[" + name + "'");
		}

		advance();
		endOfLine();
		return name;
	}

	private String bareKey(String what) throws TomlSyntaxException {
		int start = pos;

		while (!eof() && isBareKeyChar(peek())) {
			advance();
		}

		if (start == pos) {
			throw new TomlSyntaxException(line, "expected " + what + ", found " + describeHere());
		}

		String key = src.substring(start, pos);

		if (!eof() && peek() == '.') {
			throw new TomlSyntaxException(line, "dotted keys are not supported ('" + key + ".')");
		}

		return key;
	}

	private Object value() throws TomlSyntaxException {
		if (eof()) {
			throw new TomlSyntaxException(line, "expected a value, found end of file");
		}

		char c = peek();

		return switch (c) {
			case '"' -> string();
			case '[' -> array();
			case '\'' -> throw new TomlSyntaxException(line,
					"single-quoted (literal) strings are not supported; use double quotes");
			case '{' -> throw new TomlSyntaxException(line, "inline tables are not supported");
			default -> scalar();
		};
	}

	private String string() throws TomlSyntaxException {
		int startLine = line;
		expect('"');

		if (pos + 1 < src.length() && src.charAt(pos) == '"' && src.charAt(pos + 1) == '"') {
			throw new TomlSyntaxException(startLine, "multi-line strings are not supported");
		}

		StringBuilder out = new StringBuilder();

		while (true) {
			if (eof()) {
				throw new TomlSyntaxException(startLine, "unterminated string");
			}

			char c = peek();

			if (c == '\n' || c == '\r') {
				throw new TomlSyntaxException(startLine, "unterminated string");
			}

			advance();

			if (c == '"') {
				return out.toString();
			}

			if (c == '\\') {
				out.append(escape(startLine));
			} else {
				out.append(c);
			}
		}
	}

	private String escape(int startLine) throws TomlSyntaxException {
		if (eof()) {
			throw new TomlSyntaxException(startLine, "unterminated string");
		}

		char c = peek();
		advance();

		return switch (c) {
			case '"' -> "\"";
			case '\\' -> "\\";
			case 'b' -> "\b";
			case 't' -> "\t";
			case 'n' -> "\n";
			case 'f' -> "\f";
			case 'r' -> "\r";
			case 'u' -> codePoint(4, startLine);
			case 'U' -> codePoint(8, startLine);
			default -> throw new TomlSyntaxException(startLine, "unsupported escape '\\" + c + "'");
		};
	}

	private String codePoint(int digits, int startLine) throws TomlSyntaxException {
		if (pos + digits > src.length()) {
			throw new TomlSyntaxException(startLine, "truncated unicode escape");
		}

		String hex = src.substring(pos, pos + digits);
		int value;

		try {
			value = Integer.parseInt(hex, 16);
		} catch (NumberFormatException e) {
			throw new TomlSyntaxException(startLine, "'" + hex + "' is not a unicode escape");
		}

		if (!Character.isValidCodePoint(value)) {
			throw new TomlSyntaxException(startLine, "'" + hex + "' is not a valid code point");
		}

		for (int i = 0; i < digits; i++) {
			advance();
		}

		return new String(Character.toChars(value));
	}

	private List<Object> array() throws TomlSyntaxException {
		int startLine = line;
		expect('[');
		List<Object> items = new ArrayList<>();

		while (true) {
			// Arrays are the one place a value may span lines, so newlines are ignorable here.
			skipIgnorable();

			if (eof()) {
				throw new TomlSyntaxException(startLine, "unterminated array");
			}

			if (peek() == ']') {
				advance();
				return items;
			}

			items.add(value());
			skipIgnorable();

			if (eof()) {
				throw new TomlSyntaxException(startLine, "unterminated array");
			}

			char c = peek();

			if (c == ',') {
				advance();
			} else if (c != ']') {
				throw new TomlSyntaxException(line,
						"expected ',' or ']' in an array, found " + describeHere());
			}
		}
	}

	/** Anything not quoted, bracketed or braced: {@code true}, {@code false} or a number. */
	private Object scalar() throws TomlSyntaxException {
		int start = pos;

		while (!eof() && !isValueTerminator(peek())) {
			advance();
		}

		String token = src.substring(start, pos);

		if (token.isEmpty()) {
			throw new TomlSyntaxException(line, "expected a value, found " + describeHere());
		}

		if (token.equals("true")) {
			return Boolean.TRUE;
		}

		if (token.equals("false")) {
			return Boolean.FALSE;
		}

		return number(token);
	}

	private Object number(String token) throws TomlSyntaxException {
		// TOML allows '_' between digits purely as a readability separator.
		String digits = token.replace("_", "");

		try {
			if (digits.matches("[+-]?[0-9]+")) {
				return Long.valueOf(Long.parseLong(digits));
			}

			if (digits.matches("[+-]?[0-9]+(\\.[0-9]+)?([eE][+-]?[0-9]+)?")) {
				double value = Double.parseDouble(digits);

				// Overflow to infinity would sail through validation as "a finite-looking literal
				// that is not finite", so it is rejected here as bad syntax instead.
				if (!Double.isFinite(value)) {
					throw new TomlSyntaxException(line, "'" + token + "' is out of range for a number");
				}

				return Double.valueOf(value);
			}
		} catch (NumberFormatException e) {
			throw new TomlSyntaxException(line, "'" + token + "' is out of range for a number");
		}

		throw new TomlSyntaxException(line,
				"'" + token + "' is not a supported value (expected a number, true, false, "
						+ "a double-quoted string or an array)");
	}

	private void endOfLine() throws TomlSyntaxException {
		skipInlineSpace();

		if (!eof() && peek() == '#') {
			skipComment();
		}

		if (eof()) {
			return;
		}

		char c = peek();

		if (c == '\n' || c == '\r') {
			return;
		}

		throw new TomlSyntaxException(line, "unexpected " + describeHere() + " after the value");
	}

	/** Skips spaces, tabs, line breaks and comments — everything that carries no meaning. */
	private void skipIgnorable() {
		while (!eof()) {
			char c = peek();

			if (c == '#') {
				skipComment();
			} else if (c == ' ' || c == '\t' || c == '\n' || c == '\r') {
				advance();
			} else {
				return;
			}
		}
	}

	private void skipInlineSpace() {
		while (!eof() && (peek() == ' ' || peek() == '\t')) {
			advance();
		}
	}

	private void skipComment() {
		while (!eof() && peek() != '\n' && peek() != '\r') {
			advance();
		}
	}

	private void expect(char expected) throws TomlSyntaxException {
		if (eof() || peek() != expected) {
			throw new TomlSyntaxException(line, "expected '" + expected + "', found " + describeHere());
		}

		advance();
	}

	private String describeHere() {
		if (eof()) {
			return "end of file";
		}

		char c = peek();

		if (c == '\n' || c == '\r') {
			return "end of line";
		}

		return "'" + c + "'";
	}

	private boolean eof() {
		return pos >= src.length();
	}

	private char peek() {
		return src.charAt(pos);
	}

	private void advance() {
		// '\r\n' must count as one line; a lone '\r' as one too, hence the look-behind.
		char c = src.charAt(pos);
		pos++;

		if (c == '\n' || (c == '\r' && (eof() || peek() != '\n'))) {
			line++;
		}
	}

	private static boolean isBareKeyChar(char c) {
		return (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9')
				|| c == '_' || c == '-';
	}

	private static boolean isValueTerminator(char c) {
		return c == ' ' || c == '\t' || c == '\n' || c == '\r' || c == ',' || c == ']' || c == '#';
	}
}
