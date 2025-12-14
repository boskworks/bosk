package works.bosk.drivers.mongo.internal;

import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import works.bosk.Bosk;
import works.bosk.BoskConfig;
import works.bosk.Path;
import works.bosk.Reference;
import works.bosk.exceptions.InvalidTypeException;
import works.bosk.testing.drivers.AbstractDriverTest;
import works.bosk.testing.drivers.state.TestEntity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static works.bosk.testing.BoskTestUtils.boskName;

class DottedFieldNameTest {
	private Bosk<TestEntity> bosk;

	@BeforeEach
	void setUpStuff() {
		bosk = new Bosk<>(boskName(), TestEntity.class, AbstractDriverTest::initialRoot, BoskConfig.simple());
	}

	static Stream<Arguments> pathArgumentSource() {
		final String base = "state";
		return Stream.of(
			args("/", base),
			args("/catalog", base + ".catalog"),
			args("/listing", base + ".listing"),
			args("/sideTable", base + ".sideTable"),
			args("/catalog/xyz", base + ".catalog.xyz"),
			args("/listing/xyz", base + ".listing.ids.xyz"),
			args("/sideTable/xyz", base + ".sideTable.valuesById.xyz"),
			args(Path.of("catalog", "$field.with%unusual\uD83D\uDE09characters").toString(), base + ".catalog.%24field%2Ewith%25unusual\uD83D\uDE09characters")
		);
	}

	static Arguments args(String boskPath, String dottedFieldName) {
		return Arguments.of(boskPath, dottedFieldName);
	}

	@ParameterizedTest
	@MethodSource("pathArgumentSource")
	void testDottedFieldNameOf(String boskPath, String dottedFieldName) throws InvalidTypeException {
		Reference<?> reference = bosk.rootReference().then(Object.class, Path.parse(boskPath));
		String actual = BsonFormatter.dottedFieldNameOf(reference, bosk.rootReference());
		assertEquals(dottedFieldName, actual);
		//assertThrows(AssertionError.class, ()-> MongoDriver.dottedFieldNameOf(reference, catalogReference.then(Identifier.from("whoopsie"))));
	}

	@ParameterizedTest
	@MethodSource("pathArgumentSource")
	void testReferenceTo(String boskPath, String dottedFieldName) throws InvalidTypeException {
		Reference<?> expected = bosk.rootReference().then(Object.class, Path.parse(boskPath));
		Reference<?> actual = BsonFormatter.referenceTo(dottedFieldName, bosk.rootReference());
		assertEquals(expected, actual);
		assertEquals(expected.path(), actual.path());
		assertEquals(expected.targetType(), actual.targetType());
	}

	@Test
	void testTruncatedPaths() throws InvalidTypeException {
		assertEquals("state", dotted("/", 0));
		assertEquals("state.catalog", dotted("/catalog", 1));
		assertEquals("state", dotted("/catalog", 0));

		assertEquals("state.catalog.x.catalog.y",  dotted("/catalog/x/catalog/y", 5));
		assertEquals("state.catalog.x.catalog.y",  dotted("/catalog/x/catalog/y", 4));
		assertEquals("state.catalog.x.catalog",    dotted("/catalog/x/catalog/y", 3));
		assertEquals("state.catalog.x",            dotted("/catalog/x/catalog/y", 2));
		assertEquals("state.catalog",              dotted("/catalog/x/catalog/y", 1));
		assertEquals("state",                      dotted("/catalog/x/catalog/y", 0));

		assertEquals("state.sideTable.valuesById.x.sideTable.valuesById.y",  dotted("/sideTable/x/sideTable/y", 5));
		assertEquals("state.sideTable.valuesById.x.sideTable.valuesById.y",  dotted("/sideTable/x/sideTable/y", 4));
		assertEquals("state.sideTable.valuesById.x.sideTable",               dotted("/sideTable/x/sideTable/y", 3));
		assertEquals("state.sideTable.valuesById.x",                         dotted("/sideTable/x/sideTable/y", 2));
		assertEquals("state.sideTable",                                      dotted("/sideTable/x/sideTable/y", 1));
		assertEquals("state",                                                dotted("/sideTable/x/sideTable/y", 0));
	}

	private String dotted(String path, int pathLength) throws InvalidTypeException {
		Reference<?> reference = bosk.rootReference().then(Object.class, Path.parseParameterized(path));
		return BsonFormatter.dottedFieldNameOf(reference, pathLength, bosk.rootReference());
	}
}
