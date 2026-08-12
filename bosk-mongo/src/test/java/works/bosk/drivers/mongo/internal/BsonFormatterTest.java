package works.bosk.drivers.mongo.internal;

import java.io.IOException;
import java.util.ArrayList;
import java.util.stream.Stream;
import org.bson.BsonDocument;
import org.bson.BsonString;
import org.bson.BsonValue;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import works.bosk.Bosk;
import works.bosk.BoskConfig;
import works.bosk.Catalog;
import works.bosk.CatalogReference;
import works.bosk.Identifier;
import works.bosk.Path;
import works.bosk.Reference;
import works.bosk.drivers.mongo.BsonSerializer;
import works.bosk.exceptions.InvalidTypeException;
import works.bosk.libtesting.AbstractBoskTest;
import works.bosk.libtesting.TestEntityBuilder;
import works.bosk.testing.drivers.AbstractDriverTest;
import works.bosk.testing.drivers.state.TestEntity;
import works.bosk.util.Types;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static works.bosk.BoskConfig.simpleDriver;
import static works.bosk.testing.BoskTestUtils.boskName;

/**
 * Tests for {@link BsonFormatter}: converting bosk values to BSON, and
 * converting references to and from dotted field names. The three groups have
 * different setup needs, so each lives in its own {@link Nested} class.
 */
class BsonFormatterTest {

	@Nested
	class ObjectToBsonValue extends AbstractBoskTest {
		Bosk<TestRoot> bosk;
		CatalogReference<TestEntity> entitiesRef;
		Reference<TestEntity> weirdRef;
		static final String WEIRD_ID = "weird|i.d.";
		BsonFormatter formatter;
		private TestEntity weirdEntity;

		@BeforeEach
		void setupFormatter() throws InvalidTypeException, IOException, InterruptedException {
			bosk = setUpBosk(simpleDriver());
			TestEntityBuilder builder = new TestEntityBuilder(bosk);
			entitiesRef = builder.entitiesRef();
			weirdRef = builder.entityRef(Identifier.from(WEIRD_ID));
			weirdEntity = builder.blankEntity(Identifier.from(WEIRD_ID), TestEnum.OK);
			bosk.driver().submitReplacement(entitiesRef, Catalog.of(weirdEntity));
			bosk.driver().flush();
			formatter = new BsonFormatter(bosk, new BsonSerializer());
		}

		@Test
		void object2bsonValue() {
			BsonValue actual = formatter.object2bsonValue(Catalog.of(weirdEntity), Types.parameterizedType(Catalog.class, TestEntity.class));
			BsonValue weirdDoc = new BsonDocument()
				.append("id", new BsonString(WEIRD_ID))
				.append("string", new BsonString(WEIRD_ID))
				.append("testEnum", new BsonString("OK"))
				.append("children", new BsonDocument())
				.append("oddChildren", new BsonDocument()
					.append("domain", new BsonString("/entities/weird%7Ci.d./children"))
					.append("ids", new BsonDocument())
				)
				.append("stringSideTable", new BsonDocument()
					.append("domain", new BsonString("/entities/weird%7Ci.d./children"))
					.append("valuesById", new BsonDocument())
				)
				.append("phantoms", new BsonDocument()
					.append("id", new BsonString(WEIRD_ID + "_phantoms"))
				)
				.append("optionals", new BsonDocument()
					.append("id", new BsonString(WEIRD_ID + "_optionals"))
				)
				.append("implicitRefs", new BsonDocument()
					.append("id", new BsonString(WEIRD_ID + "_implicitRefs"))
				)
				.append("variant", new BsonDocument()
					.append("variant1", new BsonDocument("stringField", new BsonString("variantCase1String")))
				)
				;

			ArrayList<String> dottedName = BsonFormatter.dottedFieldNameSegments(weirdRef, weirdRef.path().length(), bosk.rootReference());
			BsonDocument expected = new BsonDocument()
				.append(dottedName.getLast(), weirdDoc);
			assertEquals(expected, actual);
		}
	}

	@Nested
	class DottedFieldNames extends AbstractDriverTest {
		@BeforeEach
		void setUpStuff() {
			bosk = new Bosk<>(boskName(), TestEntity.class, this::initialState, BoskConfig.simple());
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

	@Nested
	class FieldNameSegments {

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

}
