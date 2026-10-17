package works.bosk.spring.boot;

import java.util.List;
import org.junit.jupiter.api.Test;
import works.bosk.Identifier;

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
	void headerValue_awkwardRevisions_roundTrip() {
		for (String value : List.of(
			"rev1",
			"rev 1",
			"rev\"1",
			"100%",
			"revé",
			"a\nb",
			"a\tb",
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
	void revisionFrom_percentWithoutHexDigits_isLiteral() {
		assertEquals(Identifier.from("100%"), Etag.revisionFrom("\"100%\""));
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
	}
}
