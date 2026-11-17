package works.bosk.spring.boot;

import java.util.List;
import org.junit.jupiter.api.Test;
import works.bosk.Identifier;
import works.bosk.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EtagTest {
	@Test
	void headerValue_plainRevision_isQuotedUnchanged() {
		assertEquals("\"rev1\"", Etag.headerValue(Identifier.from("rev1")));
	}

	@Test
	void headerValue_escapesCharactersOutsideOpaqueTag() {
		assertEquals("\"rev%201\"", Etag.headerValue(Identifier.from("rev 1")));
		assertEquals("\"rev%221\"", Etag.headerValue(Identifier.from("rev\"1")));
		assertEquals("\"100%25\"", Etag.headerValue(Identifier.from("100%")));
		assertEquals("\"rev%C3%A9\"", Etag.headerValue(Identifier.from("revé")));
		assertEquals("\"a%0Ab\"", Etag.headerValue(Identifier.from("a\nb")));
	}

	@Test
	void headerValue_containsOnlyOpaqueTagCharacters() {
		String headerValue = Etag.headerValue(Identifier.from("a b\"c%dé\n"));
		for (int i = 0; i < headerValue.length(); i++) {
			char c = headerValue.charAt(i);
			assertTrue(c == '"' || c == 0x21 || (0x23 <= c && c <= 0x7E),
				"Header value character at position " + i + " must be permitted in an ETag: " + c);
		}
	}

	@Test
	void headerValue_usesSameEncodingAsPathSegments() {
		Identifier revision = Identifier.from("a b/100%\"é");
		String pathSegment = Path.just(revision.toString()).urlEncoded().substring(1);
		String etag = Etag.headerValue(revision);
		assertEquals(pathSegment, etag.substring(1, etag.length() - 1),
			"An Identifier must be encoded the same way in a path segment and in an ETag");
	}

	@Test
	void headerValue_awkwardRevisions_roundTrip() {
		for (String value : List.of(
			"rev1",
			"rev 1",
			"rev\"1",
			"100%",
			"revé",
			"a\nb",
			"a\tb",
			"a+b",
			"a\\b",
			"a/b",
			"a,b",
			"a*b"
		)) {
			Identifier revision = Identifier.from(value);
			assertEquals(revision, Etag.revisionFrom(Etag.headerValue(revision)),
				"Revision must survive a round trip through an ETag");
		}
	}

	@Test
	void revisionFrom_legacyRawRevision_isAccepted() {
		assertEquals(Identifier.from("rev1"), Etag.revisionFrom("\"rev1\""));
	}

	@Test
	void revisionFrom_plusIsLiteral() {
		assertEquals(Identifier.from("a+b"), Etag.revisionFrom("\"a+b\""));
		assertEquals(Identifier.from("a+b"), Etag.revisionFrom("\"a%2Bb\""));
		assertEquals(Identifier.from("a b"), Etag.revisionFrom("\"a%20b\""));
	}

	@Test
	void revisionFrom_encodedPercent_isDecoded() {
		assertEquals(Identifier.from("100%"), Etag.revisionFrom("\"100%25\""));
	}

	@Test
	void revisionFrom_lowercaseHex_isAccepted() {
		assertEquals(Identifier.from("é"), Etag.revisionFrom("\"%c3%a9\""));
	}

	@Test
	void revisionFrom_malformedValues_throw() {
		assertThrows(IllegalArgumentException.class, () -> Etag.revisionFrom(null));
		assertThrows(IllegalArgumentException.class, () -> Etag.revisionFrom("rev1"));
		assertThrows(IllegalArgumentException.class, () -> Etag.revisionFrom("\"\""));
		assertThrows(IllegalArgumentException.class, () -> Etag.revisionFrom("\"a\" \"b\""));
		assertThrows(IllegalArgumentException.class, () -> Etag.revisionFrom("W/\"rev1\""));
		assertThrows(IllegalArgumentException.class, () -> Etag.revisionFrom("\"100%\""));
	}

	@Test
	void revisionFrom_undecodableTag_hasClearMessage() {
		for (String value : List.of("\"100%\"", "\"%ZZ\"", "\"-x\"", "\"x-\"")) {
			IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
				() -> Etag.revisionFrom(value));
			assertTrue(e.getMessage().contains("does not encode a valid revision"),
				"Message should explain that the ETag cannot be a revision: " + e.getMessage());
		}
	}

	@Test
	void revisionFrom_unsupportedCharacters_throw() {
		for (String value : List.of("\"a b\"", "\"a\tb\"", "\"a\nb\"", "\"aé\"")) {
			IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
				() -> Etag.revisionFrom(value));
			assertTrue(e.getMessage().contains("unsupported character"),
				"Message should identify the unsupported character: " + e.getMessage());
		}
	}
}
