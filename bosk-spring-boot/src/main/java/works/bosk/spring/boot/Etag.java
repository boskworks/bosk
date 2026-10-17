package works.bosk.spring.boot;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import works.bosk.Identifier;

/**
 * Encodes and decodes the ETag header values used by the maintenance endpoints.
 * <p>
 * An ETag is a quoted opaque tag, so the characters that may appear inside it are
 * restricted by RFC 9110. Rather than reject revisions containing other characters,
 * we percent-encode their UTF-8 bytes: {@code %} itself always becomes {@code %25},
 * and every character outside the opaque-tag set becomes one or more {@code %XX}
 * sequences. The result is always a valid strong ETag, and decoding recovers the
 * original revision exactly.
 * <p>
 * Decoding is deliberately lenient about a {@code %} that is not followed by two
 * hexadecimal digits, which lets hand-written headers use the older convention of
 * quoting the raw revision.
 */
final class Etag {
	private Etag() {
	}

	/**
	 * @return the revision as a complete ETag header value, including quotes
	 */
	static String headerValue(Identifier revision) {
		return '"' + escape(revision.toString()) + '"';
	}

	/**
	 * @return the revision encoded in the given ETag header value
	 * @throws IllegalArgumentException if the value is not a single strong ETag
	 */
	static Identifier revisionFrom(String headerValue) {
		if (headerValue != null && headerValue.startsWith("W/")) {
			throw new IllegalArgumentException("Weak ETags are not supported; use the strong ETag returned by a GET");
		} else if (headerValue == null || headerValue.length() < 3 || headerValue.charAt(0) != '"' || headerValue.charAt(headerValue.length() - 1) != '"') {
			throw new IllegalArgumentException("ETag string must be a non-empty string surrounded by quotes: " + headerValue);
		} else {
			String value = headerValue.substring(1, headerValue.length() - 1);
			if (value.indexOf('"') != -1) {
				throw new IllegalArgumentException("Only a single ETag string is supported: " + headerValue);
			} else {
				return Identifier.from(unescape(value));
			}
		}
	}

	private static String escape(String value) {
		StringBuilder result = new StringBuilder(value.length());
		for (byte b : value.getBytes(StandardCharsets.UTF_8)) {
			int ch = b & 0xFF;
			if (isOpaqueTagChar(ch) && ch != '%') {
				result.append((char) ch);
			} else {
				result
					.append('%')
					.append(HEX_DIGITS[ch >>> 4])
					.append(HEX_DIGITS[ch & 0x0F]);
			}
		}
		return result.toString();
	}

	private static String unescape(String value) {
		ByteArrayOutputStream bytes = new ByteArrayOutputStream(value.length());
		for (int i = 0; i < value.length(); ) {
			char c = value.charAt(i);
			if (c == '%' && i + 2 < value.length() && isHexDigit(value.charAt(i + 1)) && isHexDigit(value.charAt(i + 2))) {
				bytes.write((hexValue(value.charAt(i + 1)) << 4) | hexValue(value.charAt(i + 2)));
				i += 3;
			} else {
				int codePoint = value.codePointAt(i);
				bytes.writeBytes(new String(Character.toChars(codePoint)).getBytes(StandardCharsets.UTF_8));
				i += Character.charCount(codePoint);
			}
		}
		return bytes.toString(StandardCharsets.UTF_8);
	}

	/**
	 * @return true if the given byte may appear literally in an RFC 9110 opaque tag
	 */
	private static boolean isOpaqueTagChar(int ch) {
		return ch == 0x21 || (0x23 <= ch && ch <= 0x7E);
	}

	private static boolean isHexDigit(char c) {
		return Character.digit(c, 16) >= 0;
	}

	private static int hexValue(char c) {
		return Character.digit(c, 16);
	}

	private static final char[] HEX_DIGITS = "0123456789ABCDEF".toCharArray();
}
