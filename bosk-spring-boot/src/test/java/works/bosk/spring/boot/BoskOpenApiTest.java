package works.bosk.spring.boot;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;
import works.bosk.Bosk;
import works.bosk.BoskConfig;
import works.bosk.Catalog;
import works.bosk.CatalogReference;
import works.bosk.Entity;
import works.bosk.Identifier;
import works.bosk.ListValue;
import works.bosk.Listing;
import works.bosk.MapValue;
import works.bosk.Phantom;
import works.bosk.Reference;
import works.bosk.SideTable;
import works.bosk.StateTreeNode;
import works.bosk.TaggedUnion;
import works.bosk.TaggedUnionCase;
import works.bosk.annotations.Enclosing;
import works.bosk.annotations.Polyfill;
import works.bosk.annotations.Self;
import works.bosk.annotations.TaggedUnionCaseMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BoskOpenApiTest {
	private static final ObjectMapper MAPPER = JsonMapper.builder().build();

	public record State(
		Catalog<Target> targets,
		Catalog<Parent> parents,
		SideTable<Target, String> notes,
		Listing<Target> featured,
		TaggedUnion<Shape> shape,
		Optional<String> nickname,
		GenericNode<String> generic,
		SelfRef selfRef,
		Recursive recursive,
		Polyfilled polyfilled,
		ListValue<Integer> numbers,
		MapValue<String> labels,
		Scalars scalars,
		Phantom<Catalog<Target>> phantom
	) implements StateTreeNode {}

	public record Target(
		Identifier id,
		String name,
		int count,
		Color color,
		Optional<String> note,
		BigDecimal amount,
		boolean active,
		Identifier revision
	) implements Entity {}

	public enum Color {
		RED,
		GREEN
	}

	public record Parent(Identifier id, Catalog<Child> children) implements Entity {}
	public record Child(Identifier id, @Enclosing Reference<Parent> parent) implements Entity {}

	public interface Shape extends TaggedUnionCase {
		@TaggedUnionCaseMap
		MapValue<Class<? extends Shape>> CASES = cases();

		private static MapValue<Class<? extends Shape>> cases() {
			Map<String, Class<? extends Shape>> result = new LinkedHashMap<>();
			result.put("circle", Circle.class);
			result.put("square", Square.class);
			return MapValue.copyOf(result);
		}
	}

	public record Circle(double radius) implements Shape {
		@Override
		public String tag() {
			return "circle";
		}
	}

	public record Square(double side) implements Shape {
		@Override
		public String tag() {
			return "square";
		}
	}

	public record GenericNode<T>(T value) implements StateTreeNode {}
	public record SelfRef(String value, @Self Reference<SelfRef> self) implements StateTreeNode {}
	public record Recursive(String value, Optional<Recursive> next) implements StateTreeNode {}

	public record Polyfilled(String field1, String field2) implements StateTreeNode {
		@Polyfill("field2")
		public static final String FIELD2_DEFAULT = "default";
	}

	public record Scalars(
		char charField,
		long longField,
		BigInteger bigIntegerField,
		float floatField,
		double doubleField,
		short shortField,
		byte byteField
	) implements StateTreeNode {}

	public record CollisionState(First.Duplicate first, Second.Duplicate second) implements StateTreeNode {}

	public static final class First {
		public record Duplicate(String value) implements StateTreeNode {}
	}

	public static final class Second {
		public record Duplicate(String value) implements StateTreeNode {}
	}


	@Test
	void documentDescribesEveryAddressableNode() {
		Map<String, Object> paths = paths(document());

		assertEquals(Set.of(
			"/bosk/state",
			"/bosk/state/targets",
			"/bosk/state/targets/{targetId}",
			"/bosk/state/targets/{targetId}/id",
			"/bosk/state/targets/{targetId}/name",
			"/bosk/state/targets/{targetId}/count",
			"/bosk/state/targets/{targetId}/color",
			"/bosk/state/targets/{targetId}/note",
			"/bosk/state/targets/{targetId}/amount",
			"/bosk/state/targets/{targetId}/active",
			"/bosk/state/targets/{targetId}/revision",
			"/bosk/state/parents",
			"/bosk/state/parents/{parentId}",
			"/bosk/state/parents/{parentId}/id",
			"/bosk/state/parents/{parentId}/children",
			"/bosk/state/parents/{parentId}/children/{childId}",
			"/bosk/state/parents/{parentId}/children/{childId}/id",
			"/bosk/state/parents/{parentId}/children/{childId}/parent",
			"/bosk/state/notes",
			"/bosk/state/notes/{targetId}",
			"/bosk/state/featured",
			"/bosk/state/featured/{targetId}",
			"/bosk/state/shape",
			"/bosk/state/shape/circle",
			"/bosk/state/shape/circle/radius",
			"/bosk/state/shape/square",
			"/bosk/state/shape/square/side",
			"/bosk/state/nickname",
			"/bosk/state/generic",
			"/bosk/state/generic/value",
			"/bosk/state/polyfilled",
			"/bosk/state/polyfilled/field1",
			"/bosk/state/polyfilled/field2",
			"/bosk/state/selfRef",
			"/bosk/state/selfRef/value",
			"/bosk/state/selfRef/self",
			"/bosk/state/recursive",
			"/bosk/state/recursive/value",
			"/bosk/state/recursive/next",
			"/bosk/state/numbers",
			"/bosk/state/labels",
			"/bosk/state/scalars",
			"/bosk/state/scalars/charField",
			"/bosk/state/scalars/longField",
			"/bosk/state/scalars/bigIntegerField",
			"/bosk/state/scalars/floatField",
			"/bosk/state/scalars/doubleField",
			"/bosk/state/scalars/shortField",
			"/bosk/state/scalars/byteField",
			"/bosk/state/phantom"
		), paths.keySet());
	}

	@Test
	void operationsMatchBoskCapabilities() {
		Map<String, Object> paths = paths(document());

		assertTrue(hasOperation(paths, "/bosk/state", "get"));
		assertTrue(hasOperation(paths, "/bosk/state", "put"));
		assertFalse(hasOperation(paths, "/bosk/state", "delete"));

		assertTrue(hasOperation(paths, "/bosk/state/targets", "put"));
		assertFalse(hasOperation(paths, "/bosk/state/targets", "delete"));

		assertTrue(hasOperation(paths, "/bosk/state/targets/{targetId}", "delete"));

		assertTrue(hasOperation(paths, "/bosk/state/notes", "put"));
		assertFalse(hasOperation(paths, "/bosk/state/notes", "delete"));
		assertTrue(hasOperation(paths, "/bosk/state/notes/{targetId}", "delete"));

		assertTrue(hasOperation(paths, "/bosk/state/nickname", "delete"));

		assertTrue(hasOperation(paths, "/bosk/state/shape", "put"));
		assertTrue(hasOperation(paths, "/bosk/state/shape/circle", "get"));
		assertFalse(hasOperation(paths, "/bosk/state/shape/circle", "put"));
		assertFalse(hasOperation(paths, "/bosk/state/shape/circle", "delete"));
		assertTrue(hasOperation(paths, "/bosk/state/shape/circle/radius", "get"));
		assertFalse(hasOperation(paths, "/bosk/state/shape/circle/radius", "put"));
		assertFalse(hasOperation(paths, "/bosk/state/shape/circle/radius", "delete"));

		assertTrue(hasOperation(paths, "/bosk/state/selfRef/self", "get"));
		assertFalse(hasOperation(paths, "/bosk/state/selfRef/self", "put"));
		assertFalse(hasOperation(paths, "/bosk/state/selfRef/self", "delete"));

		assertTrue(hasOperation(paths, "/bosk/state/parents/{parentId}/children/{childId}/parent", "get"));
		assertFalse(hasOperation(paths, "/bosk/state/parents/{parentId}/children/{childId}/parent", "put"));
		assertFalse(hasOperation(paths, "/bosk/state/parents/{parentId}/children/{childId}/parent", "delete"));

		assertTrue(hasOperation(paths, "/bosk/state/phantom", "get"));
		assertFalse(hasOperation(paths, "/bosk/state/phantom", "put"));
		assertTrue(hasOperation(paths, "/bosk/state/phantom", "delete"));
	}

	@Test
	void schemasMatchJacksonRepresentation() {
		Map<String, Object> document = document();
		Map<String, Object> schemas = schemas(document);

		Map<String, Object> state = map(schemas.get("State"));
		Map<String, Object> stateProperties = map(state.get("properties"));
		assertFalse(stateProperties.containsKey("phantom"));
		assertFalse(list(state.get("required")).contains("nickname"));

		Map<String, Object> target = map(schemas.get("Target"));
		Map<String, Object> targetProperties = map(target.get("properties"));
		assertEquals("string", map(targetProperties.get("id")).get("type"));
		assertEquals("number", map(targetProperties.get("amount")).get("type"));
		assertEquals("boolean", map(targetProperties.get("active")).get("type"));
		assertEquals("string", map(targetProperties.get("note")).get("type"));
		assertFalse(list(target.get("required")).contains("note"));
		assertTrue(list(target.get("required")).contains("revision"));

		Map<String, Object> catalog = map(stateProperties.get("targets"));
		assertEquals("array", catalog.get("type"));
		Map<String, Object> catalogEntry = map(catalog.get("items"));
		assertEquals(1, catalogEntry.get("minProperties"));
		assertEquals(1, catalogEntry.get("maxProperties"));
		assertNotNull(catalogEntry.get("additionalProperties"));

		Map<String, Object> sideTable = map(stateProperties.get("notes"));
		assertEquals("array", map(map(sideTable.get("properties")).get("valuesById")).get("type"));
		assertEquals("string", map(map(sideTable.get("properties")).get("domain")).get("type"));

		Map<String, Object> listing = map(stateProperties.get("featured"));
		assertEquals("array", map(map(listing.get("properties")).get("ids")).get("type"));
		assertEquals("string", map(map(listing.get("properties")).get("domain")).get("type"));

		Map<String, Object> union = map(stateProperties.get("shape"));
		assertEquals(2, list(union.get("oneOf")).size());
		assertTrue(schemas.containsKey("GenericNode_String"));
		assertEquals(List.of("RED", "GREEN"), list(map(targetProperties.get("color")).get("enum")));

		Map<String, Object> polyfilled = map(schemas.get("Polyfilled"));
		List<Object> polyfilledRequired = list(polyfilled.get("required"));
		assertTrue(polyfilledRequired.contains("field1"));
		assertFalse(polyfilledRequired.contains("field2"));

		Map<String, Object> selfRef = map(schemas.get("SelfRef"));
		Map<String, Object> selfRefProperties = map(selfRef.get("properties"));
		assertFalse(selfRefProperties.containsKey("self"));
		assertTrue(list(selfRef.get("required")).contains("value"));

		Map<String, Object> child = map(schemas.get("Child"));
		Map<String, Object> childProperties = map(child.get("properties"));
		assertFalse(childProperties.containsKey("parent"));
		assertTrue(list(child.get("required")).contains("id"));

		Map<String, Object> recursive = map(schemas.get("Recursive"));
		Map<String, Object> recursiveProperties = map(recursive.get("properties"));
		assertEquals("#/components/schemas/Recursive", map(recursiveProperties.get("next")).get("$ref"));
		assertFalse(list(recursive.get("required")).contains("next"));

		Map<String, Object> scalarProperties = map(map(schemas.get("Scalars")).get("properties"));
		assertEquals(Map.of("type", "string", "minLength", 1, "maxLength", 1), map(scalarProperties.get("charField")));
		assertEquals(Map.of("type", "integer", "format", "int64"), map(scalarProperties.get("longField")));
		assertEquals(Map.of("type", "integer"), map(scalarProperties.get("bigIntegerField")));
		assertEquals(Map.of("type", "number", "format", "float"), map(scalarProperties.get("floatField")));
		assertEquals(Map.of("type", "number", "format", "double"), map(scalarProperties.get("doubleField")));
		assertEquals(Map.of("type", "integer", "format", "int32"), map(scalarProperties.get("shortField")));
		assertEquals(Map.of("type", "integer", "format", "int32"), map(scalarProperties.get("byteField")));
	}

	@Test
	void everyReferenceResolves() {
		Map<String, Object> document = document();
		Map<String, Object> schemas = schemas(document);
		List<String> references = new ArrayList<>();
		collectReferences(document, references);
		assertFalse(references.isEmpty());
		for (String reference : references) {
			String prefix = "#/components/schemas/";
			assertTrue(reference.startsWith(prefix), reference);
			assertTrue(schemas.containsKey(reference.substring(prefix.length())),
				"Missing schema for reference " + reference);
		}
	}

	@Test
	void everyPathParameterIsDeclared() {
		Map<String, Object> document = document();
		Pattern parameterPattern = Pattern.compile("\\{([^}]+)}");
		for (Map.Entry<String, Object> entry : paths(document).entrySet()) {
			Map<String, Object> pathItem = map(entry.getValue());
			Set<String> declared = new HashSet<>();
			if (pathItem.get("parameters") instanceof List<?> parameters) {
				for (Object parameter : parameters) {
					declared.add((String) map(parameter).get("name"));
				}
			}
			Matcher matcher = parameterPattern.matcher(entry.getKey());
			while (matcher.find()) {
				assertTrue(declared.contains(matcher.group(1)),
					"Path " + entry.getKey() + " does not declare parameter " + matcher.group(1));
			}
		}
	}

	@Test
	void operationIdsAreUnique() {
		Set<String> operationIds = new HashSet<>();
		for (Map.Entry<String, Object> entry : paths(document()).entrySet()) {
			Map<String, Object> pathItem = map(entry.getValue());
			for (String method : List.of("get", "put", "delete")) {
				if (pathItem.get(method) instanceof Map<?, ?> operation) {
					assertTrue(operationIds.add((String) map(operation).get("operationId")),
						"Duplicate operationId at " + method + " " + entry.getKey());
				}
			}
		}
	}

	@Test
	void revisionControlsEtagAndIfMatch() {
		Map<String, Object> paths = paths(document());

		Map<String, Object> targetGet = operation(paths, "/bosk/state/targets/{targetId}", "get");
		Map<String, Object> targetResponse = map(map(targetGet.get("responses")).get("200"));
		assertTrue(map(targetResponse.get("headers")).containsKey("ETag"));
		assertTrue(hasParameter(targetGet, "If-None-Match"));
		assertTrue(map(targetGet.get("responses")).containsKey("304"));
		assertTrue(hasParameter(operation(paths, "/bosk/state/targets/{targetId}", "put"), "If-Match"));
		assertTrue(hasParameter(operation(paths, "/bosk/state/targets/{targetId}", "delete"), "If-Match"));

		Map<String, Object> genericGet = operation(paths, "/bosk/state/generic", "get");
		Map<String, Object> genericResponse = map(map(genericGet.get("responses")).get("200"));
		assertFalse(genericResponse.containsKey("headers"));
		assertFalse(hasParameter(genericGet, "If-None-Match"));
		assertFalse(map(genericGet.get("responses")).containsKey("304"));
		assertFalse(hasParameter(operation(paths, "/bosk/state/generic", "put"), "If-Match"));
	}

	@Test
	void normalizePath_acceptsMissingLeadingAndTrailingSlashes() {
		Map<String, Object> document = document(BoskOpenApi.documentFor(bosk(), "bosk/state/", ""));
		assertTrue(paths(document).containsKey("/bosk/state"));
		assertTrue(paths(document).containsKey("/bosk/state/targets"));
		assertFalse(paths(document).containsKey("//bosk/state"));

		Map<String, Object> noSlashes = document(BoskOpenApi.documentFor(bosk(), "bosk/state", ""));
		assertTrue(paths(noSlashes).containsKey("/bosk/state"));

		Map<String, Object> empty = document(BoskOpenApi.documentFor(bosk(), "", ""));
		assertTrue(paths(empty).containsKey("/"));
		assertTrue(paths(empty).containsKey("/targets"));
	}

	@Test
	void listingEntry_isBooleanTrue() {
		Map<String, Object> paths = paths(document());
		Map<String, Object> response = map(
			map(operation(paths, "/bosk/state/featured/{targetId}", "get").get("responses")).get("200"));
		Map<String, Object> schema = map(
			map(map(response.get("content")).get("application/json")).get("schema"));
		assertEquals("boolean", schema.get("type"));
		assertEquals(true, schema.get("const"));
		assertTrue(((String) operation(paths, "/bosk/state/featured/{targetId}", "put")
			.get("description")).contains("`true`"));
	}

	@Test
	void duplicateSimpleNames_getDistinctSchemaNames() {
		Bosk<CollisionState> bosk = Bosk.simple("collision", new CollisionState(
			new First.Duplicate("first"),
			new Second.Duplicate("second")));
		Map<String, Object> schemas = schemas(document(BoskOpenApi.documentFor(bosk, "/bosk/state", "")));
		assertTrue(schemas.containsKey("Duplicate"));
		assertTrue(schemas.containsKey("works.bosk.spring.boot.BoskOpenApiTest.Second.Duplicate"));
	}

	@Test
	void rootMaintenancePath_isRootOfDocument() {
		Map<String, Object> document = document(BoskOpenApi.documentFor(bosk(), "/", ""));
		assertTrue(paths(document).containsKey("/"));
		assertTrue(paths(document).containsKey("/targets"));
	}

	@Test
	void contextPath_becomesServerUrl() {
		Map<String, Object> document = document(BoskOpenApi.documentFor(bosk(), "/bosk/state", "/context"));
		assertEquals(List.of(Map.of("url", "/context")), document.get("servers"));
		assertFalse(document().containsKey("servers"));
	}

	private static Map<String, Object> document() {
		return document(BoskOpenApi.documentFor(bosk(), "/bosk/state", ""));
	}

	private static Map<String, Object> document(ObjectNode node) {
		return MAPPER.convertValue(node, new TypeReference<>() {});
	}

	private static Bosk<State> bosk() {
		return new Bosk<>("test", State.class, bosk -> {
			CatalogReference<Target> targets = bosk.rootReference().thenCatalog(Target.class, "targets");
			Target target = new Target(
				Identifier.from("w1"),
				"widget",
				1,
				Color.RED,
				Optional.of("note"),
				BigDecimal.ONE,
				true,
				Identifier.from("r1"));
			CatalogReference<Parent> parents = bosk.rootReference().thenCatalog(Parent.class, "parents");
			Reference<Parent> parentRef = parents.then(Identifier.from("p1"));
			Child child = new Child(Identifier.from("c1"), parentRef);
			Parent parent = new Parent(Identifier.from("p1"), Catalog.of(child));
			return new State(
				Catalog.of(target),
				Catalog.of(parent),
				SideTable.of(targets, target.id(), "side value"),
				Listing.of(targets, target.id()),
				TaggedUnion.of(new Circle(1.5)),
				Optional.of("nickname"),
				new GenericNode<>("value"),
				new SelfRef("value", bosk.rootReference().then(SelfRef.class, "selfRef")),
				new Recursive("value", Optional.empty()),
				new Polyfilled("a", "b"),
				ListValue.of(1, 2),
				MapValue.copyOf(Map.of("k", "v")),
				new Scalars('x', 1L, BigInteger.ONE, 1.5f, 2.5, (short) 3, (byte) 4),
				Phantom.empty());
		}, BoskConfig.simple());
	}

	private static Map<String, Object> paths(Map<String, Object> document) {
		return map(document.get("paths"));
	}

	private static Map<String, Object> schemas(Map<String, Object> document) {
		return map(map(document.get("components")).get("schemas"));
	}

	private static boolean hasOperation(Map<String, Object> paths, String path, String method) {
		Map<String, Object> pathItem = map(paths.get(path));
		return pathItem != null && pathItem.containsKey(method);
	}

	private static Map<String, Object> operation(Map<String, Object> paths, String path, String method) {
		return map(map(paths.get(path)).get(method));
	}

	private static boolean hasParameter(Map<String, Object> operation, String name) {
		if (operation.get("parameters") instanceof List<?> parameters) {
			for (Object parameter : parameters) {
				if (name.equals(map(parameter).get("name"))) {
					return true;
				}
			}
		}
		return false;
	}

	private static void collectReferences(Object value, List<String> references) {
		if (value instanceof Map<?, ?> valueMap) {
			for (Map.Entry<?, ?> entry : valueMap.entrySet()) {
				if ("$ref".equals(entry.getKey())) {
					references.add((String) entry.getValue());
				} else {
					collectReferences(entry.getValue(), references);
				}
			}
		} else if (value instanceof List<?> list) {
			for (Object element : list) {
				collectReferences(element, references);
			}
		}
	}

	@SuppressWarnings("unchecked")
	private static Map<String, Object> map(Object value) {
		return (Map<String, Object>) value;
	}

	@SuppressWarnings("unchecked")
	private static List<Object> list(Object value) {
		return (List<Object>) value;
	}


}
