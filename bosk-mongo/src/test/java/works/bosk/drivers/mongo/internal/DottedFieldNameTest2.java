package works.bosk.drivers.mongo.internal;

import java.util.stream.Stream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import static org.junit.jupiter.api.Assertions.assertEquals;

class DottedFieldNameTest2 {

	@ParameterizedTest
	@MethodSource("dottedNameCases")
	void dottedFieldNameSegment(String plain, String dotted) {
		assertEquals(dotted, BsonFormatter.dottedFieldNameSegment(plain));
	}

	@ParameterizedTest
	@MethodSource("dottedNameCases")
	void undottedFieldNameSegment(String plain, String dotted) {
		assertEquals(plain, BsonFormatter.undottedFieldNameSegment(dotted));
	}

	static Stream<Arguments> dottedNameCases() {
		return Stream.of(
			dottedNameCase("%", "%25"),
			dottedNameCase("$", "%24"),
			dottedNameCase(".", "%2E"),
			dottedNameCase("\0", "%00"),
			dottedNameCase("|", "%7C"),
			dottedNameCase("!", "%21"),
			dottedNameCase("~", "%7E"),
			dottedNameCase("[", "%5B"),
			dottedNameCase("]", "%5D"),
			dottedNameCase("+", "%2B"),
			dottedNameCase(" ", "%20")
		);
	}

	static Arguments dottedNameCase(String plain, String dotted) {
		return Arguments.of(plain, dotted);
	}

}
