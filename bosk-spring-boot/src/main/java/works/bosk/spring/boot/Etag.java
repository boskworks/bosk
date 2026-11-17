package works.bosk.spring.boot;

import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import works.bosk.Identifier;

/**
 * Encodes and decodes the ETag header values used by the maintenance endpoints.
 * <p>
 * The value is the revision's string form, quoted as an RFC 9110 opaque tag and
 * percent-encoded with the same scheme used for path segments. This keeps the
 * representation of an {@link Identifier} consistent across the places where Bosk
 * encodes one, and the result is always a valid strong ETag.
 * <p>
 * {@link URLEncoder} is form encoding, where a space becomes {@code +}; we replace
 * those with {@code %20}, as {@link works.bosk.Path} does, so a space in a revision
 * is encoded as {@code %20}. Decoding does not apply form encoding's rule that
 * {@code +} means a space: an ETag is opaque, so a {@code +} in it is a plus sign.
 * Incoming values must use the ASCII subset of the opaque-tag grammar; any other
 * character must be percent-encoded.
 */
final class Etag {
	private Etag() {
	}

	/**
	 * @return the revision as a complete ETag header value, including quotes
	 */
	static String headerValue(Identifier revision) {
		return '"' + encode(revision.toString()) + '"';
	}

	/**
	 * @return the revision encoded in the given ETag header value
	 * @throws IllegalArgumentException if the value is not a single strong ETag,
	 * or contains invalid percent-encoding
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
			}
			for (int i = 0; i < value.length(); i++) {
				int ch = value.codePointAt(i);
				if (ch == 0x21 || (0x23 <= ch && ch <= 0x7E)) {
					// all is well
				} else {
					throw new IllegalArgumentException("ETag string contains an unsupported character at position "
						+ i + ", code point " + ch + ": " + headerValue);
				}
			}
			try {
				return Identifier.from(decode(value));
			} catch (IllegalArgumentException e) {
				throw new IllegalArgumentException(
					"ETag is syntactically valid but does not encode a valid revision: " + headerValue, e);
			}
		}
	}

	private static String encode(String value) {
		return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
	}

	private static String decode(String value) {
		// URLDecoder implements form decoding, where a plus sign stands for a space.
		// That rule doesn't apply to an ETag, so escape the plus signs before
		// decoding and leave them as literal plus signs.
		return URLDecoder.decode(value.replace("+", "%2B"), StandardCharsets.UTF_8);
	}
}
