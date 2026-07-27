package works.bosk.graphql;

import graphql.ExecutionInput;
import graphql.ExecutionResult;
import graphql.GraphQL;
import graphql.schema.GraphQLSchema;
import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import works.bosk.Bosk;
import works.bosk.BoskConfig;
import works.bosk.BoskInfo;
import works.bosk.Catalog;
import works.bosk.CatalogReference;
import works.bosk.Entity;
import works.bosk.Identifier;
import works.bosk.Listing;
import works.bosk.ListingReference;
import works.bosk.MapValue;
import works.bosk.Reference;
import works.bosk.SideTable;
import works.bosk.SideTableReference;
import works.bosk.StateTreeNode;
import works.bosk.TaggedUnion;
import works.bosk.VariantCase;
import works.bosk.annotations.ReferencePath;
import works.bosk.annotations.VariantCaseMap;
import works.bosk.exceptions.InvalidTypeException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BoskGraphQLTest {
	static final Identifier w1 = Identifier.from("w1");
	static final Identifier w2 = Identifier.from("w2");
	static final Identifier p1 = Identifier.from("p1");
	static final Identifier p2 = Identifier.from("p2");
	static final Identifier p3 = Identifier.from("p3");
	static final Identifier did = Identifier.from("did");

	Bosk<RootType> bosk;
	Refs refs;

	@BeforeEach
	void setupBosk() throws InvalidTypeException {
		bosk = new Bosk<>("test",
			RootType.class,
			BoskGraphQLTest::initialRoot,
			BoskConfig.simple()
		);
		refs = bosk.buildReferences(Refs.class);
	}

	@Test
	void simpleFields() {
		var graphQL = buildGraphQL();
		try (var _ = bosk.readSession()) {
			assertQueryReturns(
				Map.of("field1", "testApp", "field2", 1),
				"{ field1 field2 }",
				graphQL,
				bosk.rootReference().valueIfExists());
		}
	}

	@Test
	void catalogList() {
		var graphQL = buildGraphQL();
		try (var _ = bosk.readSession()) {
			assertQueryReturns(
				Map.of("widgets", List.of(
					Map.of(
						"id", "w1",
						"field1", "Alpha",
						"field2", "active"),
					Map.of(
						"id", "w2",
						"field1", "Beta",
						"field2", "deferred")
				)),
				"{ widgets { id field1 field2 } }",
				graphQL,
				bosk.rootReference().valueIfExists());
		}
	}

	@Test
	void catalogFilterById() {
		var graphQL = buildGraphQL();
		try (var _ = bosk.readSession()) {
			assertQueryReturns(
				Map.of("widgets", List.of(
					Map.of(
						"id", "w1",
						"field1", "Alpha",
						"field2", "active")
				)),
				"{ widgets(id: \"w1\") { id field1 field2 } }",
				graphQL,
				bosk.rootReference().valueIfExists());
		}
	}

	@Test
	void catalogFilter_nonexistentId_notFound() {
		var graphQL = buildGraphQL();
		try (var _ = bosk.readSession()) {
			assertQueryReturns(
				Map.of("widgets", List.of()),
				"{ widgets(id: \"nonexistent\") { id field1 } }",
				graphQL,
				bosk.rootReference().valueIfExists());
		}
	}

	@Test
	void nestedCatalog() {
		var graphQL = buildGraphQL();
		try (var _ = bosk.readSession()) {
			assertQueryReturns(
				Map.of("widgets", List.of(
					Map.of(
						"id", "w1",
						"field1", "Alpha",
						"parts", List.of(
							Map.of("id", "p1",
								"field1", "Bolt",
								"field2", 4),
							Map.of(
								"id", "p2",
								"field1", "Nut",
								"field2", 8)
						)),
					Map.of(
						"id", "w2",
						"field1", "Beta",
						"parts", List.of(
							Map.of(
								"id", "p3",
								"field1", "Screw",
								"field2", 2)
						))
				)),
				"{ widgets { id field1 parts { id field1 field2 } } }",
				graphQL,
				bosk.rootReference().valueIfExists());
		}
	}

	@Test
	void nestedCatalogFilterById() {
		var graphQL = buildGraphQL();
		try (var _ = bosk.readSession()) {
			assertQueryReturns(
				Map.of("widgets", List.of(
					Map.of("parts", List.of(
						Map.of("field1", "Bolt", "field2", 4)
					))
				)),
				"{ widgets(id: \"w1\") { parts(id: \"p1\") { field1 field2 } } }",
				graphQL,
				bosk.rootReference().valueIfExists());
		}
	}

	@Test
	void sideTableEntries() {
		var graphQL = buildGraphQL();
		try (var _ = bosk.readSession()) {
			assertQueryReturns(
				Map.of("widgets", List.of(
					Map.of("partValues", List.of(
						Map.of(
							"path", "/widgets/w1/parts/p1",
							"value", Map.of(
								"field1", "steel",
								"field2", "red")),
						Map.of(
							"path", "/widgets/w1/parts/p2", "value", Map.of(
								"field1", "plastic",
								"field2", "blue"))
					))
				)),
				"{ widgets(id: \"w1\") { partValues { path value { field1 field2 } } } }",
				graphQL,
				bosk.rootReference().valueIfExists());
		}
	}

	@Test
	void sideTableFilterByKey() {
		var graphQL = buildGraphQL();
		try (var _ = bosk.readSession()) {
			assertQueryReturns(
				Map.of("widgets", List.of(
					Map.of("partValues", List.of(
						Map.of(
							"value", Map.of(
								"field1", "steel",
								"field2", "red"))
					))
				)),
				"{ widgets(id: \"w1\") { partValues(id: \"p1\") { value { field1 field2 } } } }",
				graphQL,
				bosk.rootReference().valueIfExists());
		}
	}

	@Test
	void sideTableFilter_nonexistentKey_notFound() {
		var graphQL = buildGraphQL();
		try (var _ = bosk.readSession()) {
			assertQueryReturns(
				Map.of("widgets", List.of(
					Map.of("partValues", List.of())
				)),
				"{ widgets(id: \"w1\") { partValues(id: \"nonexistent\") { value { field1 } } } }",
				graphQL,
				bosk.rootReference().valueIfExists());
		}
	}

	@Test
	void listingEntries() {
		var graphQL = buildGraphQL();
		try (var _ = bosk.readSession()) {
			assertQueryReturns(
				Map.of("widgets", List.of(
					Map.of("partOrdering", List.of(
						Map.of(
							"path", "/widgets/w1/parts/p1",
							"value", Map.of("field1", "Bolt")),
						Map.of(
							"path", "/widgets/w1/parts/p2",
							"value", Map.of("field1", "Nut"))
					))
				)),
				"{ widgets(id: \"w1\") { partOrdering { path value { field1 } } } }",
				graphQL,
				bosk.rootReference().valueIfExists());
		}
	}

	@Test
	void listingEntry_danglingRef_returnsNull() throws IOException, InterruptedException {
		// Add a dangling ref to widget w2's part ordering
		bosk.driver().submitReplacement(refs.partOrdering(w2),
			Listing.empty(refs.parts(w2))
				.withID(p3)
				.withID(Identifier.from("missing")));
		bosk.driver().flush();

		var graphQL = buildGraphQL();
		try (var _ = bosk.readSession()) {
			var missingEntry = new HashMap<String, Object>();
			missingEntry.put("path", "/widgets/w2/parts/missing");
			missingEntry.put("value", null);
			assertQueryReturns(
				Map.of("widgets", List.of(
					Map.of("partOrdering", List.of(
						Map.of(
							"path", "/widgets/w2/parts/p3",
							"value", Map.of("field1", "Screw")),
						missingEntry
					))
				)),
				"{ widgets(id: \"w2\") { partOrdering { path value { field1 } } } }",
				graphQL,
				bosk.rootReference().valueIfExists());
		}
	}

	@Test
	void listingFilterByKey() {
		var graphQL = buildGraphQL();
		try (var _ = bosk.readSession()) {
			assertQueryReturns(
				Map.of("widgets", List.of(
					Map.of("partOrdering", List.of(
						Map.of("value", Map.of("field1", "Nut"))
					))
				)),
				"{ widgets(id: \"w1\") { partOrdering(id: \"p2\") { value { field1 } } } }",
				graphQL,
				bosk.rootReference().valueIfExists());
		}
	}

	@Test
	void taggedUnionSimpleVariant() {
		var graphQL = buildGraphQL();
		try (var _ = bosk.readSession()) {
			assertQueryReturns(
				Map.of("variant", Map.of("tag", "simple")),
				"{ variant { tag } }",
				graphQL,
				bosk.rootReference().valueIfExists());
		}
	}

	@Test
	void taggedUnionSelectFields() {
		var graphQL = buildGraphQL();
		try (var _ = bosk.readSession()) {
			assertQueryReturns(
				Map.of("variant", Map.of(
					"tag", "simple",
					"__typename", "SimpleVariant"
				)),
				"{ variant { tag ... on SimpleVariant { __typename } ... on DetailedVariant { extraId } } }",
				graphQL,
				bosk.rootReference().valueIfExists());
		}
	}

	@Test
	void taggedUnionDetailedVariant() throws IOException, InterruptedException {
		bosk.driver().submitReplacement(refs.variant(),
			TaggedUnion.of(new DetailedVariant(did)));
		bosk.driver().flush();

		var graphQL = buildGraphQL();
		try (var _ = bosk.readSession()) {
			assertQueryReturns(
				Map.of("variant", Map.of(
					"tag", "detailed",
					"extraId", "did")),
				"{ variant { tag ... on SimpleVariant { __typename } ... on DetailedVariant { extraId } } }",
				graphQL,
				bosk.rootReference().valueIfExists());
		}
	}

	@Test
	void optionalField() {
		var graphQL = buildGraphQL();
		try (var _ = bosk.readSession()) {
			assertQueryReturns(
				Map.of("field1", "testApp"),
				"{ field1 }",
				graphQL,
				bosk.rootReference().valueIfExists());
		}
	}

	@Test
	void mapValueField() {
		var mvBosk = new Bosk<RootWithMapValue>("test", RootWithMapValue.class,
			_ -> new RootWithMapValue("app",
				MapValue.fromFunction(List.of("env", "region"), k -> switch (k) {
					case "env" -> "prod";
					case "region" -> "us-east-1";
					default -> throw new IllegalArgumentException(k);
				})), BoskConfig.simple());
		GraphQLSchema schema = BoskGraphQL.schemaFor(mvBosk);
		var graphQL = GraphQL.newGraphQL(schema).build();
		try (var _ = mvBosk.readSession()) {
			assertQueryReturns(
				Map.of(
					"field1", "app",
					"tags", List.of(
						Map.of("key", "env", "value", "prod"),
						Map.of("key", "region", "value", "us-east-1")
					)),
				"{ field1 tags { key value } }",
				graphQL,
				mvBosk.rootReference().valueIfExists());
		}
	}

	@Test
	void allFieldsTogether() {
		var graphQL = buildGraphQL();
		try (var _ = bosk.readSession()) {
			String query = """
				{
					field1
					field2
					widgets {
						id
						field1
						field2
						parts { id field1 }
						partValues { path value { field1 } }
						partOrdering { path value { field1 } }
					}
					variant { tag }
				}
				""";
			Map<String, Object> expected = Map.of(
				"field1", "testApp",
				"field2", 1,
				"widgets", List.of(
					Map.of("id", "w1", "field1", "Alpha", "field2", "active",
						"parts", List.of(
							Map.of("id", "p1", "field1", "Bolt"),
							Map.of("id", "p2", "field1", "Nut")),
					"partValues", List.of(
						Map.of("path", "/widgets/w1/parts/p1", "value", Map.of("field1", "steel")),
						Map.of("path", "/widgets/w1/parts/p2", "value", Map.of("field1", "plastic"))),
					"partOrdering", List.of(
						Map.of("path", "/widgets/w1/parts/p1", "value", Map.of("field1", "Bolt")),
						Map.of("path", "/widgets/w1/parts/p2", "value", Map.of("field1", "Nut")))),
					Map.of("id", "w2", "field1", "Beta", "field2", "deferred",
						"parts", List.of(
							Map.of("id", "p3", "field1", "Screw")),
					"partValues", List.of(
						Map.of("path", "/widgets/w2/parts/p3", "value", Map.of("field1", "brass"))),
					"partOrdering", List.of(
						Map.of("path", "/widgets/w2/parts/p3", "value", Map.of("field1", "Screw"))))
				),
				"variant", Map.of("tag", "simple")
			);
			assertQueryReturns(expected, query, graphQL, bosk.rootReference().valueIfExists());
		}
	}

	public interface Refs {
		@ReferencePath("/widgets/-widget-") Reference<Widget> widget(Identifier widget);
		@ReferencePath("/widgets/-widget-/parts") CatalogReference<Part> parts(Identifier widget);
		@ReferencePath("/widgets/-widget-/partValues") SideTableReference<Part, PartInfo> partValues(Identifier widget);
		@ReferencePath("/widgets/-widget-/partOrdering") ListingReference<Part> partOrdering(Identifier widget);
		@ReferencePath("/variant") Reference<TaggedUnion<TestVariant>> variant();
	}

	public record RootType(
		Catalog<Widget> widgets,
		String field1,
		int field2,
		TaggedUnion<TestVariant> variant
	) implements StateTreeNode {}

	public record Widget(
		Identifier id,
		String field1,
		String field2,
		Catalog<Part> parts,
		SideTable<Part, PartInfo> partValues,
		Listing<Part> partOrdering
	) implements Entity {}

	public record Part(Identifier id, String field1, int field2) implements Entity {}
	public record PartInfo(String field1, String field2) implements StateTreeNode {}

	public interface TestVariant extends VariantCase {
		@VariantCaseMap
		MapValue<Class<? extends TestVariant>> CASE_MAP = MapValue.copyOf(Map.of(
			"simple", SimpleVariant.class,
			"detailed", DetailedVariant.class
		));
	}

	public record SimpleVariant() implements TestVariant {
		@Override public String tag() { return "simple"; }
	}

	public record DetailedVariant(Identifier extraId) implements TestVariant {
		@Override public String tag() { return "detailed"; }
	}

	public record RootWithMapValue(
		String field1,
		MapValue<String> tags
	) implements StateTreeNode {}

	private static RootType initialRoot(BoskInfo<RootType> bosk) throws InvalidTypeException {
		var refs = bosk.rootReference().buildReferences(Refs.class);
		// Widget w1 — full, with parts
		var w1Parts = Catalog.of(
			new Part(p1, "Bolt", 4),
			new Part(p2, "Nut", 8));
		var w1Configs = SideTable.<Part, PartInfo>empty(refs.parts(w1))
			.with(p1, new PartInfo("steel", "red"))
			.with(p2, new PartInfo("plastic", "blue"));
		var w1Ordering = Listing.empty(refs.parts(w1))
			.withID(p1)
			.withID(p2);
		var widget1 = new Widget(w1, "Alpha", "active", w1Parts, w1Configs, w1Ordering);

		// Widget w2 — simpler
		var w2Parts = Catalog.of(
			new Part(p3, "Screw", 2));
		var w2Configs = SideTable.<Part, PartInfo>empty(refs.parts(w2))
			.with(p3, new PartInfo("brass", "gold"));
		var w2Ordering = Listing.empty(refs.parts(w2))
			.withID(p3);
		var widget2 = new Widget(w2, "Beta", "deferred", w2Parts, w2Configs, w2Ordering);

		RootType result = new RootType(Catalog.of(widget1, widget2), "testApp", 1, TaggedUnion.of(new SimpleVariant()));
		return result;
	}

	GraphQL buildGraphQL() {
		GraphQLSchema schema = BoskGraphQL.schemaFor(bosk);
		return GraphQL.newGraphQL(schema).build();
	}

	static void assertQueryReturns(Object expected, String query, GraphQL graphQL, Object root) {
		var input = ExecutionInput.newExecutionInput()
			.query(query)
			.root(root)
			.build();
		ExecutionResult result = graphQL.execute(input);
		assertTrue(result.getErrors().isEmpty(),
			() -> "Should have no errors: " + result.getErrors());
		assertEquals(expected, result.getData());
	}

}
