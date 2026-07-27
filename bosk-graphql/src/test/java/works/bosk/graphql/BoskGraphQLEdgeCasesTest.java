package works.bosk.graphql;

import graphql.GraphQL;
import graphql.GraphQLContext;
import graphql.execution.CoercedVariables;
import graphql.language.FloatValue;
import graphql.language.IntValue;
import graphql.language.StringValue;
import graphql.schema.CoercingParseLiteralException;
import graphql.schema.CoercingParseValueException;
import graphql.schema.GraphQLSchema;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import works.bosk.Bosk;
import works.bosk.Bosk.DefaultStateFunction;
import works.bosk.BoskConfig;
import works.bosk.Catalog;
import works.bosk.CatalogReference;
import works.bosk.Entity;
import works.bosk.Identifier;
import works.bosk.ListValue;
import works.bosk.Listing;
import works.bosk.MapValue;
import works.bosk.Reference;
import works.bosk.SideTable;
import works.bosk.StateTreeNode;
import works.bosk.TaggedUnion;
import works.bosk.VariantCase;
import works.bosk.annotations.ReferencePath;
import works.bosk.annotations.Self;
import works.bosk.annotations.VariantCaseMap;
import works.bosk.exceptions.InvalidTypeException;
import works.bosk.graphql.exceptions.UnsupportedNameException;
import works.bosk.graphql.exceptions.UnsupportedTypeException;

import static java.util.stream.Collectors.toMap;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static works.bosk.graphql.BoskGraphQL.GRAPHQL_BIG_DECIMAL;
import static works.bosk.graphql.BoskGraphQL.GRAPHQL_BIG_INTEGER;
import static works.bosk.graphql.BoskGraphQL.GRAPHQL_IDENTIFIER;
import static works.bosk.graphql.BoskGraphQL.GRAPHQL_LONG;
import static works.bosk.graphql.BoskGraphQL.GRAPHQL_PATH;
import static works.bosk.graphql.BoskGraphQLTest.assertQueryReturns;

class BoskGraphQLEdgeCasesTest {
	@Test
	void deeplyNestedLinearChain() {
		var bosk = createBosk(DeepRoot.class,
			new DeepRoot(new DeepA("a1", new DeepB("b1", new DeepC("c1", new DeepD(42)))))
		);
		var graphQL = buildGraphQL(bosk);
		try (var _ = bosk.readSession()) {
			assertQueryReturns(
				Map.of("a", Map.of(
					"field1", "a1", "b", Map.of(
						"field1", "b1", "c", Map.of(
							"field1", "c1", "d", Map.of(
								"field1", 42))))),
				"{ a { field1 b { field1 c { field1 d { field1 } } } } }",
				graphQL,
				bosk.rootReference().valueIfExists());
		}
	}

	@Test
	void selfReferencingCatalog() {
		var bosk = createBosk(
			Category.class,
			new Category(
				Identifier.from("root"),
				"Root",
				Catalog.of(new Category(Identifier.from("child"), "Child", Catalog.empty()))
			)
		);
		var graphQL = buildGraphQL(bosk);
		try (var _ = bosk.readSession()) {
			assertQueryReturns(
				Map.of(
					"id", "root",
					"field1", "Root",
					"children", List.of(Map.of(
						"id", "child",
						"field1", "Child",
						"children", List.of()
					))
				),
				"{ id field1 children { id field1 children { id field1 } } }",
				graphQL,
				bosk.rootReference().valueIfExists());
		}
	}

	@Test
	void mutualRecursion() {
		var id1 = Identifier.from("1");
		var id2 = Identifier.from("2");
		var ping = new Ping(id1, "ping-1", Catalog.empty());
		var pong = new Pong(id2, 42, Catalog.empty());
		var bosk = createBosk(MutualRoot.class, new MutualRoot(Catalog.of(ping), Catalog.of(pong)));
		var graphQL = buildGraphQL(bosk);
		try (var _ = bosk.readSession()) {
			assertQueryReturns(
				Map.of(
					"pings", List.of(Map.of("id", "1", "label", "ping-1")),
					"pongs", List.of(Map.of("id", "2", "score", 42))
				),
				"{ pings { id label } pongs { id score } }",
				graphQL,
				bosk.rootReference().valueIfExists());
		}
	}

	@Test
	void mutualRecursionWithDeepQuery() {
		var id1 = Identifier.from("1");
		var id2 = Identifier.from("2");
		var ping = new Ping(id1, "ping-1", Catalog.of());
		var pong = new Pong(id2, 42, Catalog.of(ping));
		var bosk = createBosk(MutualRoot.class, new MutualRoot(Catalog.of(ping), Catalog.of(pong)));
		var graphQL = buildGraphQL(bosk);
		try (var _ = bosk.readSession()) {
			assertQueryReturns(
				Map.of(
					"pongs", List.of(Map.of(
						"id", "2",
						"score", 42,
						"pings", List.of(Map.of(
							"id", "1",
							"label", "ping-1",
							"pongs", List.of()
						))
					))
				),
				"{ pongs { id score pings { id label pongs { id } } } }",
				graphQL,
				bosk.rootReference().valueIfExists());
		}
	}

	@Test
	void emptyCatalog() {
		var bosk = createEmptyBagsBosk();
		var graphQL = buildGraphQL(bosk);
		try (var _ = bosk.readSession()) {
			String query = "{ catalog { id field1 } }";
			var expected = Map.of("catalog", List.of());
			assertQueryReturns(expected, query, graphQL, bosk.rootReference().valueIfExists());
		}
	}

	@Test
	void emptySideTable() {
		var bosk = createEmptyBagsBosk();
		var graphQL = buildGraphQL(bosk);
		try (var _ = bosk.readSession()) {
			String query = "{ sideTable { path value { field1 } } }";
			var expected = Map.of("sideTable", List.of());
			assertQueryReturns(expected, query, graphQL, bosk.rootReference().valueIfExists());
		}
	}

	@Test
	void emptyListing() {
		var bosk = createEmptyBagsBosk();
		var graphQL = buildGraphQL(bosk);
		try (var _ = bosk.readSession()) {
			String query = "{ listing { path value { id field1 } } }";
			var expected = Map.of("listing", List.of());
			assertQueryReturns(expected, query, graphQL, bosk.rootReference().valueIfExists());
		}
	}

	@Test
	void optionalFilled() {
		var bosk = createBosk(MaybeBag.class, new MaybeBag(Optional.of("hello"), Optional.empty()));
		var graphQL = buildGraphQL(bosk);
		try (var _ = bosk.readSession()) {
			var expected = new HashMap<String, Object>();
			expected.put("filled", "hello");
			expected.put("empty", null);
			assertQueryReturns(
				expected,
				"{ filled empty }",
				graphQL,
				bosk.rootReference().valueIfExists());
		}
	}

	@Test
	void optionalAllEmpty() {
		var bosk = createBosk(MaybeBag.class, new MaybeBag(Optional.empty(), Optional.empty()));
		var graphQL = buildGraphQL(bosk);
		try (var _ = bosk.readSession()) {
			var expected = new HashMap<String, Object>();
			expected.put("filled", null);
			expected.put("empty", null);
			assertQueryReturns(
				expected,
				"{ filled empty }",
				graphQL,
				bosk.rootReference().valueIfExists());
		}
	}

	@Test
	void graphQLKeywordFieldNames() {
		var bosk = createBosk(Reserved.class, new Reserved("typeVal", "queryVal"));
		var graphQL = buildGraphQL(bosk);
		try (var _ = bosk.readSession()) {
			assertQueryReturns(
				Map.of(
					"type", "typeVal",
					"query", "queryVal"),
				"{ type query }",
				graphQL,
				bosk.rootReference().valueIfExists());
		}
	}

	@Test
	void recordWithManyFields() {
		var bosk = createBosk(WideRec.class, new WideRec(
			"aVal", "bVal", "cVal", "dVal", "eVal", "fVal", "gVal", "hVal", "iVal", "jVal", "kVal"));
		var graphQL = buildGraphQL(bosk);
		try (var _ = bosk.readSession()) {
			var expected = "abcdefghijk".chars()
				.mapToObj(Character::toString)
				.collect(toMap(s -> s, s -> s + "Val"));
			assertQueryReturns(
				expected,
				"{ a b c d e f g h i j k }",
				graphQL,
				bosk.rootReference().valueIfExists());
		}
	}

	@Test
	void listingWithAllDanglingRefs() {
		var bosk = createBoskFromFn(DanglingRoot.class, b -> {
			var refs = b.buildReferences(DanglingRefs.class);
			var domain = Catalog.of(new SimplePart(Identifier.from("p"), "exists"));
			var listing = Listing.empty(refs.catalog()).withID(Identifier.from("ghost"));
			return new DanglingRoot(domain, listing);
		});
		var graphQL = buildGraphQL(bosk);
		try (var _ = bosk.readSession()) {
			var entry = new HashMap<String, Object>();
			entry.put("path", "/catalog/ghost");
			entry.put("value", null);
			assertQueryReturns(
				Map.of("refs", List.of(entry)),
				"{ refs { path value { id field1 } } }",
				graphQL,
				bosk.rootReference().valueIfExists());
		}
	}

	@Test
	void nestedTaggedUnion() throws InvalidTypeException {
		var fid = Identifier.from("f");
		var bid = Identifier.from("b");
		var foo = new VariedItem(fid, TaggedUnion.of(new FooItem("payload-foo")));
		var bar = new VariedItem(bid, TaggedUnion.of(new BarItem(99)));
		var bosk = createBosk(NestedRoot.class, new NestedRoot(Catalog.of(foo, bar)));
		var graphQL = buildGraphQL(bosk);
		try (var _ = bosk.readSession()) {
			assertQueryReturns(
				Map.of("items", List.of(
					Map.of(
						"id", "f",
						"variant", Map.of("tag", "foo", "payload", "payload-foo")),
					Map.of(
						"id", "b",
						"variant", Map.of("tag", "bar", "number", 99))
				)),
				"{ items { id variant { tag ... on FooItem { payload } ... on BarItem { number } } } }",
				graphQL,
				bosk.rootReference().valueIfExists());
		}
	}

	@Test
	void enumField() {
		var bosk = createBosk(ItemWithEnum.class, new ItemWithEnum(
			Identifier.from("i1"), Level.MEDIUM, Optional.of(Level.HIGH)));
		var graphQL = buildGraphQL(bosk);
		try (var _ = bosk.readSession()) {
			var expected = Map.of("id", "i1", "level", "MEDIUM", "nextLevel", "HIGH");
			assertQueryReturns(
				expected,
				"{ id level nextLevel }",
				graphQL,
				bosk.rootReference().valueIfExists());
		}
	}

	@Test
	void enumFieldAllowsNullOptional() {
		var bosk = createBosk(ItemWithEnum.class, new ItemWithEnum(
			Identifier.from("i1"), Level.LOW, Optional.empty()));
		var graphQL = buildGraphQL(bosk);
		try (var _ = bosk.readSession()) {
			var expected = new HashMap<String, Object>();
			expected.put("level", "LOW");
			expected.put("nextLevel", null);
			assertQueryReturns(
				expected,
				"{ level nextLevel }",
				graphQL,
				bosk.rootReference().valueIfExists());
		}
	}

	@Test
	void longScalarField() {
		var bosk = createBosk(ScalarExtras.class, new ScalarExtras(
			42L, 3.14f, new BigDecimal("123.45"), new BigInteger("999")));
		var graphQL = buildGraphQL(bosk);
		try (var _ = bosk.readSession()) {
			assertQueryReturns(
				Map.of(
					"longField", 42L,
					"floatField", 3.14,
					"decimalField", "123.45",
					"integerField", "999"
				),
				"{ longField floatField decimalField integerField }",
				graphQL,
				bosk.rootReference().valueIfExists());
		}
	}

	@Test
	void scalarParseValue_badInput_throwsCoercingParseValueException() {
		var ctx = GraphQLContext.getDefault();
		var locale = Locale.getDefault();
		assertThrows(CoercingParseValueException.class,
			() -> GRAPHQL_LONG.getCoercing().parseValue("not a number", ctx, locale));
		assertThrows(CoercingParseValueException.class,
			() -> GRAPHQL_BIG_DECIMAL.getCoercing().parseValue(true, ctx, locale));
		assertThrows(CoercingParseValueException.class,
			() -> GRAPHQL_BIG_INTEGER.getCoercing().parseValue(true, ctx, locale));
		assertThrows(CoercingParseValueException.class,
			() -> GRAPHQL_IDENTIFIER.getCoercing().parseValue(42, ctx, locale));
		assertThrows(CoercingParseValueException.class,
			() -> GRAPHQL_IDENTIFIER.getCoercing().parseValue(null, ctx, locale));
		assertThrows(CoercingParseValueException.class,
			() -> GRAPHQL_IDENTIFIER.getCoercing().parseValue("", ctx, locale));
		assertThrows(CoercingParseValueException.class,
			() -> GRAPHQL_LONG.getCoercing().parseValue(3.14, ctx, locale));
		assertThrows(CoercingParseValueException.class,
			() -> GRAPHQL_BIG_DECIMAL.getCoercing().parseValue("not a number", ctx, locale));
		assertThrows(CoercingParseValueException.class,
			() -> GRAPHQL_BIG_INTEGER.getCoercing().parseValue("not a number", ctx, locale));
		assertThrows(CoercingParseValueException.class,
			() -> GRAPHQL_PATH.getCoercing().parseValue(null, ctx, locale));
		assertThrows(CoercingParseValueException.class,
			() -> GRAPHQL_PATH.getCoercing().parseValue("no-leading-slash", ctx, locale));
	}

	@Test
	void scalarParseLiteral_badInput_throwsCoercingParseLiteralException() {
		var ctx = GraphQLContext.getDefault();
		var locale = Locale.getDefault();
		var vars = CoercedVariables.emptyVariables();
		assertThrows(CoercingParseLiteralException.class,
			() -> GRAPHQL_IDENTIFIER.getCoercing().parseLiteral(new StringValue(""), vars, ctx, locale));
		assertThrows(CoercingParseLiteralException.class,
			() -> GRAPHQL_BIG_DECIMAL.getCoercing().parseLiteral(new StringValue("not a number"), vars, ctx, locale));
		assertThrows(CoercingParseLiteralException.class,
			() -> GRAPHQL_BIG_INTEGER.getCoercing().parseLiteral(new StringValue("not a number"), vars, ctx, locale));
		assertThrows(CoercingParseLiteralException.class,
			() -> GRAPHQL_PATH.getCoercing().parseLiteral(new StringValue("no-slash"), vars, ctx, locale));
	}

	@Test
	void longOverflow_throwsCoercingParseLiteralException() {
		var ctx = GraphQLContext.getDefault();
		var locale = Locale.getDefault();
		var vars = CoercedVariables.emptyVariables();
		var overflowValue = BigInteger.valueOf(Long.MAX_VALUE).add(BigInteger.ONE);
		assertThrows(CoercingParseLiteralException.class,
			() -> GRAPHQL_LONG.getCoercing().parseLiteral(new IntValue(overflowValue), vars, ctx, locale));
	}

	@Test
	void bigDecimalParseLiteral_withNumericValues() {
		var ctx = GraphQLContext.getDefault();
		var locale = Locale.getDefault();
		var vars = CoercedVariables.emptyVariables();
		var coercing = GRAPHQL_BIG_DECIMAL.getCoercing();
		assertEquals(new BigDecimal("42"),
			coercing.parseLiteral(new IntValue(BigInteger.valueOf(42)), vars, ctx, locale));
		assertEquals(new BigDecimal("3.14"),
			coercing.parseLiteral(new FloatValue(new BigDecimal("3.14")), vars, ctx, locale));
	}

	@Test
	void bigDecimalParseValue_passesBigDecimalDirectly() {
		var ctx = GraphQLContext.getDefault();
		var locale = Locale.getDefault();
		var value = new BigDecimal("123456789.123456789");
		assertSame(value, GRAPHQL_BIG_DECIMAL.getCoercing().parseValue(value, ctx, locale));
	}

	@Test
	void bigDecimalParseValue_preservesPrecision() {
		var ctx = GraphQLContext.getDefault();
		var locale = Locale.getDefault();
		var coercing = GRAPHQL_BIG_DECIMAL.getCoercing();
		assertEquals(new BigDecimal("123456789123456789"),
			coercing.parseValue(123456789123456789L, ctx, locale));
		assertEquals(new BigDecimal("0.1"),
			coercing.parseValue(0.1, ctx, locale));
	}

	@Test
	void bigIntegerParseLiteral_withIntValue() {
		var ctx = GraphQLContext.getDefault();
		var locale = Locale.getDefault();
		var vars = CoercedVariables.emptyVariables();
		var coercing = GRAPHQL_BIG_INTEGER.getCoercing();
		assertEquals(BigInteger.valueOf(42),
			coercing.parseLiteral(new IntValue(BigInteger.valueOf(42)), vars, ctx, locale));
	}

	@Test
	void bigIntegerParseValue_passesBigIntegerDirectly() {
		var ctx = GraphQLContext.getDefault();
		var locale = Locale.getDefault();
		var value = BigInteger.valueOf(Long.MAX_VALUE).add(BigInteger.ONE);
		assertSame(value, GRAPHQL_BIG_INTEGER.getCoercing().parseValue(value, ctx, locale));
	}

	@Test
	void listValueOfStrings() {
		var bosk = createBosk(WithListValues.class, new WithListValues(
			ListValue.of("a", "b", "c"),
			ListValue.of(),
			new ListValueSubclass(new String[]{"x", "y"})));
		var graphQL = buildGraphQL(bosk);
		try (var _ = bosk.readSession()) {
			assertQueryReturns(
				Map.of(
					"strings", List.of("a", "b", "c"),
					"subclass", List.of("x", "y")),
				"{ strings subclass }",
				graphQL,
				bosk.rootReference().valueIfExists());
		}
	}

	@Test
	void listValueOfStructs() {
		var bosk = createBosk(WithListValues.class, new WithListValues(
			ListValue.of(),
			ListValue.of(new ListValueItem("first"), new ListValueItem("second")),
			new ListValueSubclass(new String[0])));
		var graphQL = buildGraphQL(bosk);
		try (var _ = bosk.readSession()) {
			assertQueryReturns(
				Map.of("items", List.of(
					Map.of("field1", "first"),
					Map.of("field1", "second")
				)),
				"{ items { field1 } }",
				graphQL,
				bosk.rootReference().valueIfExists());
		}
	}

	@Test
	void emptyListValue() {
		var bosk = createBosk(WithListValues.class, new WithListValues(
			ListValue.of(),
			ListValue.of(),
			new ListValueSubclass(new String[0])));
		var graphQL = buildGraphQL(bosk);
		try (var _ = bosk.readSession()) {
			assertQueryReturns(
				Map.of("strings", List.of(), "items", List.of(), "subclass", List.of()),
				"{ strings items { field1 } subclass }",
				graphQL,
				bosk.rootReference().valueIfExists());
		}
	}

	@Test
	void mapValueOfStrings() {
		var bosk = createBosk(MapValueEdgeCases.class, new MapValueEdgeCases(
			MapValue.fromFunction(List.of("env", "region"), k -> switch (k) {
				case "env" -> "prod";
				case "region" -> "us-east-1";
				default -> throw new IllegalArgumentException(k);
			}),
			MapValue.empty(),
			MapValue.empty()));
		var graphQL = buildGraphQL(bosk);
		try (var _ = bosk.readSession()) {
			assertQueryReturns(
				Map.of("stringMap", List.of(
					Map.of("key", "env", "value", "prod"),
					Map.of("key", "region", "value", "us-east-1")
				)),
				"{ stringMap { key value } }",
				graphQL,
				bosk.rootReference().valueIfExists());
		}
	}

	@Test
	void mapValueOfInts() {
		var bosk = createBosk(MapValueEdgeCases.class, new MapValueEdgeCases(
			MapValue.empty(),
			MapValue.fromFunction(List.of("one", "two"), k -> switch (k) {
				case "one" -> 1;
				case "two" -> 2;
				default -> throw new IllegalArgumentException(k);
			}),
			MapValue.empty()));
		var graphQL = buildGraphQL(bosk);
		try (var _ = bosk.readSession()) {
			assertQueryReturns(
				Map.of("intMap", List.of(
					Map.of("key", "one", "value", 1),
					Map.of("key", "two", "value", 2)
				)),
				"{ intMap { key value } }",
				graphQL,
				bosk.rootReference().valueIfExists());
		}
	}

	@Test
	void mapValueOfStructs() {
		var bosk = createBosk(MapValueEdgeCases.class, new MapValueEdgeCases(
			MapValue.empty(),
			MapValue.empty(),
			MapValue.fromFunction(List.of("first", "second"), k -> switch (k) {
				case "first" -> new Config("alpha");
				case "second" -> new Config("beta");
				default -> throw new IllegalArgumentException(k);
			})));
		var graphQL = buildGraphQL(bosk);
		try (var _ = bosk.readSession()) {
			assertQueryReturns(
				Map.of("structMap", List.of(
					Map.of("key", "first", "value", Map.of("field1", "alpha")),
					Map.of("key", "second", "value", Map.of("field1", "beta"))
				)),
				"{ structMap { key value { field1 } } }",
				graphQL,
				bosk.rootReference().valueIfExists());
		}
	}

	@Test
	void emptyMapValue() {
		var bosk = createBosk(MapValueEdgeCases.class, new MapValueEdgeCases(
			MapValue.empty(), MapValue.empty(), MapValue.empty()));
		var graphQL = buildGraphQL(bosk);
		try (var _ = bosk.readSession()) {
			assertQueryReturns(
				Map.of(
					"stringMap", List.of(),
					"intMap", List.of(),
					"structMap", List.of()
				),
				"{ stringMap { key value } intMap { key value } structMap { key value { field1 } } }",
				graphQL,
				bosk.rootReference().valueIfExists());
		}
	}

	@Test
	void mapValueFilterByKey() {
		var bosk = createBosk(MapValueEdgeCases.class, new MapValueEdgeCases(
			MapValue.copyOf(Map.of("env", "prod", "region", "us-east-1")),
			MapValue.empty(), MapValue.empty()));
		var graphQL = buildGraphQL(bosk);
		try (var _ = bosk.readSession()) {
			assertQueryReturns(
				Map.of("stringMap", List.of(Map.of("value", "prod"))),
				"{ stringMap(key: \"env\") { value } }",
				graphQL,
				bosk.rootReference().valueIfExists());
		}
	}

	@Test
	void mapValueFilter_nonexistentKey_notFound() {
		var bosk = createBosk(MapValueEdgeCases.class, new MapValueEdgeCases(
			MapValue.copyOf(Map.of("env", "prod")),
			MapValue.empty(), MapValue.empty()));
		var graphQL = buildGraphQL(bosk);
		try (var _ = bosk.readSession()) {
			assertQueryReturns(
				Map.of("stringMap", List.of()),
				"{ stringMap(key: \"nonexistent\") { value } }",
				graphQL,
				bosk.rootReference().valueIfExists());
		}
	}

	@Test
	void unsupportedFieldType_throws() {
		assertThrows(IllegalArgumentException.class, () ->
			createBosk(WithUnsupported.class, new WithUnsupported(new java.util.Date())));
	}

	@Test
	void optionalMapValueField_throws() {
		assertThrows(UnsupportedTypeException.class, () ->
			buildGraphQL(createBosk(OptionalContainerRoot.class,
				new OptionalContainerRoot(Optional.of(MapValue.empty())))));
	}

	@Test
	void booleanAndDoubleScalars() {
		var bosk = createBosk(MixedTypes.class, new MixedTypes(true, 3.14));
		var graphQL = buildGraphQL(bosk);
		try (var _ = bosk.readSession()) {
			assertQueryReturns(
				Map.of("flag", true, "ratio", 3.14),
				"{ flag ratio }",
				graphQL,
				bosk.rootReference().valueIfExists());
		}
	}

	@Test
	void schemaGenerationIdempotent() {
		var bosk = createBosk(MixedTypes.class, new MixedTypes(true, 1.5));
		var schema1 = BoskGraphQL.schemaFor(bosk);
		var schema2 = BoskGraphQL.schemaFor(bosk);
		try (var _ = bosk.readSession()) {
			assertQueryReturns(
				Map.of("flag", true),
				"{ flag }",
				GraphQL.newGraphQL(schema1).build(),
				bosk.rootReference().valueIfExists());
			assertQueryReturns(
				Map.of("flag", true),
				"{ flag }",
				GraphQL.newGraphQL(schema2).build(),
				bosk.rootReference().valueIfExists());
		}
	}

	@Test
	void emptyCatalogFilterReturnsEmptyList() {
		var bosk = createEmptyBagsBosk();
		var graphQL = buildGraphQL(bosk);
		try (var _ = bosk.readSession()) {
			assertQueryReturns(
				Map.of("catalog", List.of()),
				"{ catalog(id: \"nonexistent\") { id } }",
				graphQL,
				bosk.rootReference().valueIfExists());
		}
	}

	@Test
	void emptyListingReturnsEmptyList() {
		var bosk = createEmptyBagsBosk();
		var graphQL = buildGraphQL(bosk);
		try (var _ = bosk.readSession()) {
			assertQueryReturns(
				Map.of("listing", List.of()),
				"{ listing { path value { id } } }",
				graphQL,
				bosk.rootReference().valueIfExists());
		}
	}

	@Test
	void duplicateTypeName_rejected() {
		var bosk = createBosk(CollisionRoot.class, new CollisionRoot(
			new Info(1), new works.bosk.graphql.namecollision.Info("two")));
		assertThrows(UnsupportedNameException.class, () -> buildGraphQL(bosk));
	}

	@Test
	void mapValueFieldsWithDifferentValueTypes() {
		var bosk = createBosk(EntryCollisionRoot.class, new EntryCollisionRoot(
			new EntryParentA(MapValue.empty()),
			new EntryParentB(MapValue.empty())));
		var graphQL = buildGraphQL(bosk);
		try (var _ = bosk.readSession()) {
			assertQueryReturns(
				Map.of("a", Map.of("tags", List.of()),
					"b", Map.of("tags", List.of())),
				"{ a { tags { key value } } b { tags { key value } } }",
				graphQL,
				bosk.rootReference().valueIfExists());
		}
	}

	@Test
	void sameFieldNameAcrossMapAndSideTable() {
		var bosk = createBoskFromFn(ConfigCollisionRoot.class, b -> {
			var refs = b.buildReferences(EntryCollisionRefs.class);
			return new ConfigCollisionRoot(
				Catalog.empty(),
				new MapConfigHolder(MapValue.empty()),
				new SideConfigHolder(SideTable.empty(refs.items())));
		});
		var graphQL = buildGraphQL(bosk);
		try (var _ = bosk.readSession()) {
			assertQueryReturns(
				Map.of("mapCfg", Map.of("configs", List.of()),
					"sideCfg", Map.of("configs", List.of())),
				"{ mapCfg { configs { key value } } sideCfg { configs { path value { field1 } } } }",
				graphQL,
				bosk.rootReference().valueIfExists());
		}
	}

	@Test
	void sameFieldNameAcrossListingAndSideTable() {
		var bosk = createBoskFromFn(PropsCollisionRoot.class, b -> {
			var refs = b.buildReferences(EntryCollisionRefs.class);
			return new PropsCollisionRoot(
				Catalog.empty(),
				new SidePropsHolder(SideTable.empty(refs.items())),
				new ListingPropsHolder(Listing.empty(refs.items())));
		});
		var graphQL = buildGraphQL(bosk);
		try (var _ = bosk.readSession()) {
			assertQueryReturns(
				Map.of("sideProps", Map.of("props", List.of()),
					"listProps", Map.of("props", List.of())),
				"{ sideProps { props { path value { field1 } } } listProps { props { path value { id field1 } } } }",
				graphQL,
				bosk.rootReference().valueIfExists());
		}
	}

	@Test
	void generatedTypeNameSameAsUserRecord() {
		var bosk = createBosk(TagsCollisionRoot.class, new TagsCollisionRoot(
			new TagsEntry(42),
			MapValue.empty()));
		var graphQL = buildGraphQL(bosk);
		try (var _ = bosk.readSession()) {
			assertQueryReturns(
				Map.of("entry", Map.of("value", 42),
					"tagsMap", List.of()),
				"{ entry { value } tagsMap { key value } }",
				graphQL,
				bosk.rootReference().valueIfExists());
		}
	}

	@Test
	void userTypeWithLeadingUnderscore_rejected() {
		var bosk = createBosk(UnderscoreRoot.class, new UnderscoreRoot(new _Bar(1)));
		assertThrows(UnsupportedNameException.class, () -> buildGraphQL(bosk));
	}

	// Deep nesting
	public record DeepRoot(DeepA a) implements StateTreeNode {}
	public record DeepA(String field1, DeepB b) implements StateTreeNode {}
	public record DeepB(String field1, DeepC c) implements StateTreeNode {}
	public record DeepC(String field1, DeepD d) implements StateTreeNode {}
	public record DeepD(int field1) implements StateTreeNode {}


	/**
	 * Self-referencing via {@link Catalog}
	 *
	 * @param id
	 * @param field1
	 * @param children
	 */
	public record Category(Identifier id, String field1, Catalog<Category> children) implements Entity {}

	// Mutual recursion — Ping has Pongs, Pong has Pings
	public record MutualRoot(Catalog<Ping> pings, Catalog<Pong> pongs) implements StateTreeNode {}
	public record Ping(Identifier id, String label, Catalog<Pong> pongs) implements Entity {}
	public record Pong(Identifier id, int score, Catalog<Ping> pings) implements Entity {}

	// Empty collection holders
	public record EmptyBags(
		Catalog<SimplePart> catalog,
		SideTable<SimplePart, Config> sideTable,
		Listing<SimplePart> listing
	) implements StateTreeNode {}
	public record SimplePart(Identifier id, String field1) implements Entity {}
	public record Config(String field1) implements StateTreeNode {}

	public interface EmptyBagsRefs {
		@ReferencePath("/catalog") CatalogReference<SimplePart> catalog();
	}

	public record MaybeBag(Optional<String> filled, Optional<String> empty) implements StateTreeNode {}

	/**
	 * GraphQL reserved words as field names
	 * @param type
	 * @param query
	 */
	public record Reserved(String type, String query) implements StateTreeNode {}

	public record WideRec(
		String a, String b, String c, String d, String e,
		String f, String g, String h, String i, String j,
		String k
	) implements StateTreeNode {}

	public record DanglingRoot(Catalog<SimplePart> catalog, Listing<SimplePart> refs) implements StateTreeNode {}

	public interface DanglingRefs {
		@ReferencePath("/catalog") CatalogReference<SimplePart> catalog();
	}

	public record NestedRoot(Catalog<VariedItem> items) implements StateTreeNode {}
	public record VariedItem(Identifier id, TaggedUnion<ItemVariant> variant) implements Entity {}

	public interface ItemVariant extends VariantCase {
		@VariantCaseMap
		MapValue<Class<? extends ItemVariant>> CASE_MAP = MapValue.copyOf(Map.of(
			"foo", FooItem.class,
			"bar", BarItem.class
		));
	}
	public record FooItem(String payload) implements ItemVariant {
		@Override public String tag() { return "foo"; }
	}
	public record BarItem(int number) implements ItemVariant {
		@Override public String tag() { return "bar"; }
	}

	public enum Level { LOW, MEDIUM, HIGH }
	public record ItemWithEnum(Identifier id, Level level, Optional<Level> nextLevel) implements Entity {}

	public record ScalarExtras(
		long longField,
		float floatField,
		BigDecimal decimalField,
		BigInteger integerField
	) implements StateTreeNode {}

	public record ListValueItem(String field1) implements StateTreeNode {}
	public record WithListValues(
		ListValue<String> strings,
		ListValue<ListValueItem> items,
		ListValueSubclass subclass
	) implements StateTreeNode {}

	public static final class ListValueSubclass extends ListValue<String> {
		ListValueSubclass(String[] entries) {
			super(entries);
		}
	}

	public record MapValueEdgeCases(
		MapValue<String> stringMap,
		MapValue<Integer> intMap,
		MapValue<Config> structMap
	) implements StateTreeNode {}

	public record WithUnsupported(java.util.Date timestamp) implements StateTreeNode {}

	public record MixedTypes(boolean flag, double ratio) implements StateTreeNode {}

	// Parameterized StateTreeNode types are not yet supported
	// (see ParameterizedField in TypeValidationTest)

	public record OptionalContainerRoot(Optional<MapValue<String>> tags) implements StateTreeNode {}

	public record Info(int field1) implements StateTreeNode {}
	public record CollisionRoot(
		Info info1,
		works.bosk.graphql.namecollision.Info info2
	) implements StateTreeNode {}

	// Entry type naming collisions

	public record EntryParentA(MapValue<String> tags) implements StateTreeNode {}
	public record EntryParentB(MapValue<Integer> tags) implements StateTreeNode {}
	public record EntryCollisionRoot(EntryParentA a, EntryParentB b) implements StateTreeNode {}

	public record MapConfigHolder(MapValue<String> configs) implements StateTreeNode {}
	public record SideConfigHolder(SideTable<SimplePart, Config> configs) implements StateTreeNode {}
	public record ConfigCollisionRoot(Catalog<SimplePart> items, MapConfigHolder mapCfg, SideConfigHolder sideCfg) implements StateTreeNode {}

	public record SidePropsHolder(SideTable<SimplePart, Config> props) implements StateTreeNode {}
	public record ListingPropsHolder(Listing<SimplePart> props) implements StateTreeNode {}
	public record PropsCollisionRoot(Catalog<SimplePart> items, SidePropsHolder sideProps, ListingPropsHolder listProps) implements StateTreeNode {}

	public interface EntryCollisionRefs {
		@ReferencePath("/items") CatalogReference<SimplePart> items();
	}

	public record TagsEntry(int value) implements StateTreeNode {}
	public record TagsCollisionRoot(TagsEntry entry, MapValue<String> tagsMap) implements StateTreeNode {}

	// Leading underscore is reserved for generated types
	public record _Bar(int field1) implements StateTreeNode {}
	public record UnderscoreRoot(_Bar bar) implements StateTreeNode {}

	// Nested parameterized types

	public record NestedGenericRoot(
		Optional<ListValue<String>> optionalList,
		ListValue<ListValue<String>> nestedList
	) implements StateTreeNode {}

	@Test
	void nestedGenericTypes_optionalListValue() {
		var bosk = createBosk(NestedGenericRoot.class, new NestedGenericRoot(
			Optional.of(ListValue.of("a", "b", "c")),
			ListValue.of()
		));
		var graphQL = buildGraphQL(bosk);
		try (var _ = bosk.readSession()) {
			assertQueryReturns(
				Map.of("optionalList", List.of("a", "b", "c"), "nestedList", List.of()),
				"{ optionalList nestedList }",
				graphQL,
				bosk.rootReference().valueIfExists());
		}
	}

	@Test
	void nestedGenericTypes_optionalListValue_empty() {
		var bosk = createBosk(NestedGenericRoot.class, new NestedGenericRoot(
			Optional.empty(),
			ListValue.of(ListValue.of("x", "y"), ListValue.of("z"))
		));
		var graphQL = buildGraphQL(bosk);
		try (var _ = bosk.readSession()) {
			var expected = new HashMap<String, Object>();
			expected.put("optionalList", null);
			expected.put("nestedList", List.of(List.of("x", "y"), List.of("z")));
			assertQueryReturns(
				expected,
				"{ optionalList nestedList }",
				graphQL,
				bosk.rootReference().valueIfExists());
		}
	}

	// Stress tests for nested generic type tracking

	public record NestedItem(String name, int value) implements StateTreeNode {}

	public record ConfusingGenerics(
		ListValue<NestedItem> items,
		ListValue<ListValue<NestedItem>> nestedItems,
		Optional<ListValue<String>> optionalStrings,
		Optional<ListValue<NestedItem>> optionalItems
	) implements StateTreeNode {}

	@Test
	void confusingGenerics_eachFieldResolvesCorrectly() {
		var bosk = createBosk(ConfusingGenerics.class, new ConfusingGenerics(
			ListValue.of(new NestedItem("a", 1), new NestedItem("b", 2)),
			ListValue.of(
				ListValue.of(new NestedItem("x", 10)),
				ListValue.of(new NestedItem("y", 20), new NestedItem("z", 30))
			),
			Optional.of(ListValue.of("hello", "world")),
			Optional.of(ListValue.of(new NestedItem("p", 100)))
		));
		var graphQL = buildGraphQL(bosk);
		try (var _ = bosk.readSession()) {
			assertQueryReturns(
				Map.ofEntries(
					Map.entry("items", List.of(
						Map.of("name", "a", "value", 1),
						Map.of("name", "b", "value", 2)
					)),
					Map.entry("nestedItems", List.of(
						List.of(Map.of("name", "x", "value", 10)),
						List.of(
							Map.of("name", "y", "value", 20),
							Map.of("name", "z", "value", 30)
						)
					)),
					Map.entry("optionalStrings", List.of("hello", "world")),
					Map.entry("optionalItems", List.of(Map.of("name", "p", "value", 100)))
				),
				"{ items { name value } nestedItems { name value } optionalStrings optionalItems { name value } }",
				graphQL,
				bosk.rootReference().valueIfExists());
		}
	}

	@Test
	void confusingGenerics_optionalStringsEmpty() {
		var bosk = createBosk(ConfusingGenerics.class, new ConfusingGenerics(
			ListValue.of(),
			ListValue.of(),
			Optional.empty(),
			Optional.empty()
		));
		var graphQL = buildGraphQL(bosk);
		try (var _ = bosk.readSession()) {
			var expected = new HashMap<String, Object>();
			expected.put("items", List.of());
			expected.put("nestedItems", List.of());
			expected.put("optionalStrings", null);
			expected.put("optionalItems", null);
			assertQueryReturns(
				expected,
				"{ items { name value } nestedItems { name value } optionalStrings optionalItems { name value } }",
				graphQL,
				bosk.rootReference().valueIfExists());
		}
	}

	// Self references

	public record SelfRefRoot(
		String field1,
		@Self Reference<SelfRefRoot> selfRef
	) implements StateTreeNode {}

	@Test
	void selfReferenceField() {
		var bosk = createBoskFromFn(SelfRefRoot.class,
			b -> new SelfRefRoot("hello", b.rootReference()));
		var graphQL = buildGraphQL(bosk);
		try (var _ = bosk.readSession()) {
			var root = bosk.rootReference().valueIfExists();
			assertQueryReturns(
				Map.of("field1", "hello", "selfRef", Map.of("path", "/")),
				"{ field1 selfRef { path } }",
				graphQL,
				root);
		}
	}

	// Optional<Reference<T>> support

	public record OptionalRefRoot(
		String field1,
		Optional<Reference<OptionalRefRoot>> selfRef
	) implements StateTreeNode {}

	@Test
	void optionalReferenceField_filled() {
		var bosk = createBoskFromFn(OptionalRefRoot.class,
			b -> new OptionalRefRoot("hello", Optional.of(b.rootReference())));
		var graphQL = buildGraphQL(bosk);
		try (var _ = bosk.readSession()) {
			assertQueryReturns(
				Map.of("field1", "hello", "selfRef", Map.of("path", "/", "value", Map.of("field1", "hello"))),
				"{ field1 selfRef { path value { field1 } } }",
				graphQL,
				bosk.rootReference().valueIfExists());
		}
	}

	@Test
	void optionalReferenceField_empty() {
		var bosk = createBoskFromFn(OptionalRefRoot.class,
			b -> new OptionalRefRoot("hello", Optional.empty()));
		var graphQL = buildGraphQL(bosk);
		try (var _ = bosk.readSession()) {
			var expected = new HashMap<String, Object>();
			expected.put("field1", "hello");
			expected.put("selfRef", null);
			assertQueryReturns(
				expected,
				"{ field1 selfRef { path } }",
				graphQL,
				bosk.rootReference().valueIfExists());
		}
	}

	// Recursive entry types — value type is the same record that contains the container field

	public record SideTableRecursiveRoot(
		Catalog<SimplePart> parts,
		SideTableRecursiveNode node
	) implements StateTreeNode {}

	public record SideTableRecursiveNode(
		String label,
		SideTable<SimplePart, SideTableRecursiveNode> children
	) implements StateTreeNode {}

	public interface SideTableRecursiveRefs {
		@ReferencePath("/parts") CatalogReference<SimplePart> parts();
	}

	@Test
	void sideTableRecursiveEntryType() {
		var bosk = createBoskFromFn(SideTableRecursiveRoot.class, b -> {
			var refs = b.buildReferences(SideTableRecursiveRefs.class);
			return new SideTableRecursiveRoot(
				Catalog.empty(),
				new SideTableRecursiveNode("root", SideTable.empty(refs.parts())));
		});
		var graphQL = buildGraphQL(bosk);
		try (var _ = bosk.readSession()) {
			assertQueryReturns(
				Map.of("parts", List.of(), "node", Map.of("label", "root", "children", List.of())),
				"{ parts { id } node { label children { path value { label } } } }",
				graphQL,
				bosk.rootReference().valueIfExists());
		}
	}

	public record EntityRoot(
		Identifier id,
		String name,
		int count
	) implements Entity {}

	@Test
	void entityRoot() {
		var bosk = createBosk(EntityRoot.class, new EntityRoot(Identifier.from("root-id"), "test", 42));
		var graphQL = buildGraphQL(bosk);
		try (var _ = bosk.readSession()) {
			assertQueryReturns(
				Map.of("id", "root-id", "name", "test", "count", 42),
				"{ id name count }",
				graphQL,
				bosk.rootReference().valueIfExists());
		}
	}

	<Root extends StateTreeNode> Bosk<Root> createBosk(Class<Root> rootClass, Root initialState) {
		return new Bosk<>("test", rootClass, _ -> initialState, BoskConfig.simple());
	}

	<Root extends StateTreeNode> Bosk<Root> createBoskFromFn(Class<Root> rootClass, DefaultStateFunction<Root> fn) {
		return new Bosk<>("test", rootClass, fn, BoskConfig.simple());
	}

	Bosk<EmptyBags> createEmptyBagsBosk() {
		return createBoskFromFn(EmptyBags.class, b -> {
			var refs = b.buildReferences(EmptyBagsRefs.class);
			return new EmptyBags(
				Catalog.empty(),
				SideTable.empty(refs.catalog()),
				Listing.empty(refs.catalog()));
		});
	}

	<Root extends StateTreeNode> GraphQL buildGraphQL(Bosk<Root> bosk) {
		GraphQLSchema schema = BoskGraphQL.schemaFor(bosk);
		return GraphQL.newGraphQL(schema).build();
	}

}
