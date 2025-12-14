package works.bosk.jackson;

import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.stream.Stream;
import lombok.experimental.FieldNameConstants;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import tools.jackson.core.exc.StreamReadException;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.JavaType;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.type.TypeFactory;
import works.bosk.AbstractBoskTest;
import works.bosk.BindingEnvironment;
import works.bosk.Bosk;
import works.bosk.Catalog;
import works.bosk.CatalogReference;
import works.bosk.Identifier;
import works.bosk.ListValue;
import works.bosk.Listing;
import works.bosk.ListingEntry;
import works.bosk.MapValue;
import works.bosk.Path;
import works.bosk.Reference;
import works.bosk.SideTable;
import works.bosk.StateTreeNode;
import works.bosk.TaggedUnion;
import works.bosk.TestEntityBuilder;
import works.bosk.annotations.DeserializationPath;
import works.bosk.annotations.Polyfill;
import works.bosk.annotations.ReferencePath;
import works.bosk.exceptions.MalformedPathException;
import works.bosk.exceptions.ParameterUnboundException;
import works.bosk.exceptions.UnexpectedPathException;

import static java.util.Arrays.asList;
import static java.util.Collections.emptyMap;
import static java.util.Collections.singletonList;
import static java.util.Collections.singletonMap;
import static java.util.stream.Collectors.toList;
import static org.hamcrest.CoreMatchers.containsString;
import static org.hamcrest.CoreMatchers.not;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static tools.jackson.databind.SerializationFeature.INDENT_OUTPUT;
import static works.bosk.BoskConfig.simpleDriver;
import static works.bosk.ListingEntry.LISTING_ENTRY;

class JacksonSerializerTest extends AbstractBoskTest {
	private Bosk<TestRoot> bosk;
	private TestEntityBuilder teb;
	private JacksonSerializer jacksonSerializer;
	private ObjectMapper boskMapper;
	private Refs refs;

	private static final TypeFactory typeFactory = TypeFactory.createDefaultInstance();

	public interface Refs {
		@ReferencePath("/entities") CatalogReference<TestEntity> entities();
		@ReferencePath("/entities/-entity-") Reference<TestEntity> entity(Identifier entity);
		@ReferencePath("/entities/-entity-/children") CatalogReference<TestChild> children(Identifier entity);
		@ReferencePath("/entities/-entity-/implicitRefs") Reference<ImplicitRefs> implicitRefs(Identifier id);
	}

	/**
	 * Not configured by JacksonSerializer. Only for checking the properties of the generated JSON.
	 */
	private ObjectMapper plainMapper;

	@BeforeEach
	void setUpJackson() throws Exception {
		bosk = setUpBosk(simpleDriver());
		teb = new TestEntityBuilder(bosk);
		refs = bosk.buildReferences(Refs.class);

		plainMapper = JsonMapper.builder()
			.enable(INDENT_OUTPUT)
			.build();

		jacksonSerializer = new JacksonSerializer();
		boskMapper = JsonMapper.builder()
			.addModule(jacksonSerializer.moduleFor(bosk))
			.enable(INDENT_OUTPUT)
			.build();
	}

	@Test
	void identifier_works() {
		Identifier id = Identifier.from("testID");
		String expected = "\"testID\"";
		assertEquals(expected, boskMapper.writeValueAsString(id));
		assertEquals(id, boskMapper.readerFor(Identifier.class).readValue(expected));
	}

	@ParameterizedTest
	@MethodSource("catalogArguments")
	void catalog_works(List<String> ids) {
		// Build entities and put them in a Catalog
		List<TestEntity> entities = new ArrayList<>();
		for (String id : ids) {
			entities.add(teb.blankEntity(Identifier.from(id), TestEnum.OK));
		}
		Catalog<TestEntity> catalog = Catalog.of(entities);

		// Build the expected JSON structure
		List<Map<String, Object>> expected = new ArrayList<>();
		entities.forEach(e1 -> expected.add(singletonMap(e1.id().toString(), plainObjectFor(e1))));

		assertJacksonWorks(expected, catalog, new TypeReference<Catalog<TestEntity>>(){}, Path.just(TestRoot.Fields.entities));
	}

	static Stream<Arguments> catalogArguments() {
		return Stream.of(
//				catalogCase(),
				catalogCase("1", "3", "2")
		);
	}

	private static Arguments catalogCase(String ...ids) {
		return Arguments.of(asList(ids));
	}

	@ParameterizedTest
	@MethodSource("listingArguments")
	void listing_works(List<String> strings, List<Identifier> ids) {
		Listing<TestEntity> listing = Listing.of(refs.entities(), ids);

		Map<String, Object> expected = new LinkedHashMap<>();
		expected.put("ids", strings);
		expected.put("domain", refs.entities().pathString());

		assertJacksonWorks(expected, listing, new TypeReference<Listing<TestEntity>>() {}, Path.just("doesn't matter"));
	}

	static Stream<Arguments> listingArguments() {
		return Stream.of(
			listingCase(),
			listingCase("1", "3", "2")
		);
	}

	private static Arguments listingCase(String ...strings) {
		return Arguments.of(asList(strings), Stream.of(strings).map(Identifier::from).collect(toList()));
	}

	@Test
	void listingEntry_works() {
		assertEquals("true", boskMapper.writeValueAsString(LISTING_ENTRY));
		Assertions.assertEquals(LISTING_ENTRY, boskMapper.readValue("true", ListingEntry.class));
	}

	@ParameterizedTest
	@MethodSource("sideTableArguments")
	void sideTable_works(List<String> keys, Map<String,String> valuesByString, Map<Identifier, String> valuesById) {
		SideTable<TestEntity, String> sideTable = SideTable.copyOf(refs.entities(), valuesById);

		List<Map<String, Object>> expectedList = new ArrayList<>();
		valuesByString.forEach((key, value) -> expectedList.add(singletonMap(key, value)));

		Map<String, Object> expected = new LinkedHashMap<>();
		expected.put("valuesById", expectedList);
		expected.put("domain", refs.entities().pathString());

		assertJacksonWorks(
			expected,
			sideTable,
			new TypeReference<SideTable<TestEntity, String>>(){},
			Path.just("doesn't matter")
		);
	}

	static Stream<Arguments> sideTableArguments() {
		return Stream.of(
				sideTableCase(f->{}),
				sideTableCase(f->{
					f.accept("1", "First");
					f.accept("3", "Second");
					f.accept("2", "Third");
				})
		);
	}

	static <V> Arguments sideTableCase(Consumer<BiConsumer<String,V>> initializer) {
		Map<String,V> valuesByString = new LinkedHashMap<>();
		initializer.accept(valuesByString::put);

		Map<Identifier, V> valuesById = new LinkedHashMap<>();
		initializer.accept((k,v) -> valuesById.put(Identifier.from(k), v));

		List<String> keys = new ArrayList<>();
		initializer.accept((k,v)-> keys.add(k));
		return Arguments.of(keys, valuesByString, valuesById);
	}

	@Test
	void phantom_isOmitted() {
		TestEntity entity = makeEntityWithOptionalString(Optional.empty());
		String json = boskMapper.writeValueAsString(entity);
		assertThat(json, not(containsString(Phantoms.Fields.phantomString)));
	}

	@Test
	void optional_isOmitted() {
		TestEntity entity = makeEntityWithOptionalString(Optional.empty());
		String json = boskMapper.writeValueAsString(entity);
		assertThat(json, not(containsString(Optionals.Fields.optionalString)));
	}

	@Test
	void optional_isIncluded() {
		String contents = "OPTIONAL STRING CONTENTS";
		TestEntity entity = makeEntityWithOptionalString(Optional.of(contents));
		String json = boskMapper.writeValueAsString(entity);
		assertThat(json, containsString(Optionals.Fields.optionalString));
		assertThat(json, containsString(contents));
	}

	@Test
	void rootReference_works() {
		String json = boskMapper.writeValueAsString(bosk.rootReference());
		assertEquals("\"/\"", json);
	}

	@ParameterizedTest
	@MethodSource("listValueArguments")
	void listValue_serializationWorks(List<?> list, JavaType type) {
		ListValue<?> listValue = ListValue.from(list);
		String expected = plainMapper.writeValueAsString(list);
		assertEquals(expected, boskMapper.writeValueAsString(listValue));
	}

	@ParameterizedTest
	@MethodSource("listValueArguments")
	void listValue_deserializationWorks(List<?> list, JavaType type) {
		ListValue<?> expected = ListValue.from(list);
		String json = plainMapper.writeValueAsString(list);
		Object actual = boskMapper.readerFor(type).readValue(json);
		assertEquals(expected, actual);
		assertInstanceOf(ListValue.class, actual);
	}

	private static Stream<Arguments> listValueArguments() {
		return Stream.of(
			listValueCase(String.class),
			listValueCase(String.class, "Hello"),
			listValueCase(String.class, "first", "second")
		);
	}

	private static Arguments listValueCase(Type entryType, Object...entries) {
		JavaType entryJavaType = typeFactory.constructType(entryType);
		return Arguments.of(asList(entries), typeFactory.constructParametricType(ListValue.class, entryJavaType));
	}

	@Test
	void listValue_parameterizedElement_works() {
		var actual = new NodeWithGenerics<>(
			ListValue.of(1, 2),
			ListValue.of(
				new NodeWithGenerics<>(
					ListValue.of(3.0, 4.0),
					ListValue.of("string1, string2")
				)
			)
		);
		LinkedHashMap<Object, Object> expectedB = new LinkedHashMap<>();
		expectedB.put("listOfA", asList(3.0, 4.0));
		expectedB.put("listOfB", singletonList("string1, string2"));
		Map<String, Object> expected = new LinkedHashMap<>();
		expected.put("listOfA", asList(1, 2));
		expected.put("listOfB", singletonList(expectedB));

		assertJacksonWorks(
			expected,
			actual,
			new TypeReference<NodeWithGenerics<Integer, NodeWithGenerics<Double, String>>>() {},
			Path.empty());
	}

	public record NodeWithGenerics<A, B>(
		ListValue<A> listOfA,
		ListValue<B> listOfB
	) implements StateTreeNode { }

	@ParameterizedTest
	@MethodSource("mapValueArguments")
	void mapValue_serializationWorks(Map<String,?> map, JavaType type) {
		MapValue<?> mapValue = MapValue.copyOf(map);
		String expected = plainMapper.writeValueAsString(map);
		assertEquals(expected, boskMapper.writeValueAsString(mapValue));
	}

	@ParameterizedTest
	@MethodSource("mapValueArguments")
	void mapValue_deserializationWorks(Map<String,?> map, JavaType type) {
		MapValue<?> expected = MapValue.copyOf(map);
		String json = plainMapper.writeValueAsString(map);
		Object actual = boskMapper.readerFor(type).readValue(json);
		assertEquals(expected, actual);
		assertInstanceOf(MapValue.class, actual);
	}

	private static Stream<Arguments> mapValueArguments() {
		return Stream.of(
			mapValueCase(String.class),
			mapValueCase(String.class, kv("key1", "Hello")),
			mapValueCase(String.class, kv("first", "firstValue"), kv("second", "secondValue"))
		);
	}

	@SafeVarargs
	private static Arguments mapValueCase(Type entryType, Map<String, Object>...entries) {
		JavaType entryJavaType = typeFactory.constructType(entryType);
		Map<String, Object> map = new LinkedHashMap<>();
		for (var entry: entries) {
			map.putAll(entry);
		}
		return Arguments.of(map, typeFactory.constructParametricType(MapValue.class, entryJavaType));
	}

	private static Map<String, Object> kv(String key, Object value) {
		return singletonMap(key, value);
	}

	@Test
	void implicitRefs_omitted() {
		TestEntity entity = makeEntityWithOptionalString(Optional.empty());
		String json = boskMapper.writeValueAsString(entity);
		assertThat(json, not(containsString(ImplicitRefs.Fields.reference)));
		assertThat(json, not(containsString(ImplicitRefs.Fields.enclosingRef)));
	}

	@Test
	void idsOmitted_filledInFromContext() {
		Identifier child1ID = Identifier.from("child1");
		TestEntity expected = makeEntityWith(
			Optional.empty(),
			Catalog.of(new TestChild(child1ID, "child1", TestEnum.OK, Catalog.empty())));
		Map<String, Object> plain = plainObjectFor(expected);

		// Remove id
		Map<?,?> child = (Map<?, ?>) ((Map<?, ?>) ((List<?>)plain.get("children")).get(0)).get("child1");
		Object removed = child.remove("id");
		assertEquals(child1ID.toString(), removed, "Expected to remove the ID");

		// Make sure it comes back when deserialized
		Object actual = boskObjectFor(plain, new TypeReference<TestEntity>() {}, Path.parse("/entities/" + expected.id()));
		assertEquals(expected, actual);
	}

	@Test
	void deserializationPath_works() {
		Reference<ImplicitRefs> ref1 = refs.implicitRefs(Identifier.from("123"));
		ImplicitRefs firstObject = new ImplicitRefs(
			Identifier.from("firstObject"),
			ref1, ref1.enclosingReference(TestEntity.class),
			ref1, ref1.enclosingReference(TestEntity.class)
			);
		Reference<ImplicitRefs> ref2 = refs.implicitRefs(Identifier.from("456"));
		ImplicitRefs secondObject = new ImplicitRefs(
			Identifier.from("secondObject"),
			ref2, ref2.enclosingReference(TestEntity.class),
			ref2, ref2.enclosingReference(TestEntity.class)
		);

		DeserializationPathContainer boskObject = new DeserializationPathContainer(firstObject, secondObject);

		Map<String, Object> plainObject = new LinkedHashMap<>();
		plainObject.put(DeserializationPathContainer.Fields.firstField, singletonMap("id", firstObject.id().toString()));
		plainObject.put(DeserializationPathContainer.Fields.secondField, singletonMap("id", secondObject.id().toString()));

		BindingEnvironment env = BindingEnvironment.empty().builder()
			.bind("entity1", Identifier.from("123"))
			.bind("entity2", Identifier.from("456"))
			.build();
		try (var _ = jacksonSerializer.overlayScope(env)) {
			assertJacksonWorks(plainObject, boskObject, new TypeReference<DeserializationPathContainer>() {}, Path.empty());
		}
	}

	@FieldNameConstants
	public record DeserializationPathContainer(
		@DeserializationPath("/entities/-entity1-/implicitRefs") ImplicitRefs firstField,
		@DeserializationPath("/entities/-entity2-/implicitRefs") ImplicitRefs secondField
	) implements StateTreeNode { }

	@Test
	void deserializationPathMissingID_filledInFromContext() {
		DeserializationPathMissingID expected = new DeserializationPathMissingID(
			makeEntityWithOptionalString(Optional.empty()));

		// Make roughly the right plain object structure
		Map<String, Object> plainObject = plainObjectFor(expected);

		// Remove the ID field
		((Map<?,?>)plainObject.get("entity")).remove("id");

		BindingEnvironment env = BindingEnvironment.empty().builder()
			.bind("entity", expected.entity.id())
			.build();
		try (var _ = jacksonSerializer.overlayScope(env)) {
			Object actual = boskObjectFor(plainObject, new TypeReference<DeserializationPathMissingID>() {}, Path.empty());
			assertEquals(expected, actual, "Object should deserialize without \"id\" field");
		}
	}

	@FieldNameConstants
	public record DeserializationPathMissingID(
		@DeserializationPath("/entities/-entity-") TestEntity entity
	) implements StateTreeNode { }

	private TestEntity makeEntityWith(Optional<String> optionalString, Catalog<TestChild> children) {
		Identifier entityID = Identifier.unique("testOptional");
		Reference<TestEntity> entityRef = refs.entity(entityID);
		CatalogReference<TestChild> childrenRef = refs.children(entityID);
		Reference<ImplicitRefs> implicitRefsRef = refs.implicitRefs(entityID);
		return new TestEntity(entityID, entityID.toString(), TestEnum.OK, children, Listing.empty(childrenRef), SideTable.empty(childrenRef),
			Phantoms.empty(Identifier.unique("phantoms")),
			new Optionals(Identifier.unique("optionals"), optionalString, Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty()),
			new ImplicitRefs(Identifier.unique("implicitRefs"), implicitRefsRef, entityRef, implicitRefsRef, entityRef),
			TaggedUnion.of(new VariantCase1("variantCase1")));
	}

	private TestEntity makeEntityWithOptionalString(Optional<String> optionalString) {
		return makeEntityWith(optionalString, Catalog.empty());
	}

	private void assertJacksonWorks(Map<String,?> plainObject, Object boskObject, TypeReference<?> boskObjectTypeRef, Path path) {
		Map<String, Object> actualPlainObject = plainObjectFor(boskObject);
		assertEquals(plainObject, actualPlainObject, "Serialized object should match expected");

		Object deserializedBoskObject = boskObjectFor(plainObject, boskObjectTypeRef, path);
		assertEquals(boskObject, deserializedBoskObject, "Deserialized object should match expected");

		Map<String, Object> roundTripPlainObject = plainObjectFor(deserializedBoskObject);
		assertEquals(plainObject, roundTripPlainObject, "Round-trip serialized object should match expected");

	}

	private void assertJacksonWorks(List<?> plainList, Object boskObject, TypeReference<?> boskObjectTypeRef, Path path) {
		JavaType boskObjectType = typeFactory.constructType(boskObjectTypeRef);
		List<Object> actualPlainList = plainListFor(boskObject);
		assertEquals(plainList, actualPlainList, "Serialized object should match expected");

		Object deserializedBoskObject = boskListFor(plainList, boskObjectType, path);
		assertEquals(boskObject, deserializedBoskObject, "Deserialized object should match expected");

		List<Object> roundTripPlainObject = plainListFor(deserializedBoskObject);
		assertEquals(plainList, roundTripPlainObject, "Round-trip serialized object should match expected");

	}

	private Map<String, Object> plainObjectFor(Object boskObject) {
		JavaType mapJavaType = typeFactory.constructParametricType(Map.class, String.class, Object.class);
		String json = boskMapper.writeValueAsString(boskObject);
		return plainMapper.readerFor(mapJavaType).readValue(json);
	}

	private List<Object> plainListFor(Object boskObject) {
		JavaType listJavaType = typeFactory.constructParametricType(List.class, Object.class);
		String json = boskMapper.writeValueAsString(boskObject);
		return plainMapper.readerFor(listJavaType).readValue(json);
	}

	private <T> T boskObjectFor(Map<String, ?> plainObject, TypeReference<T> boskObjectTypeRef, Path path) {
		JavaType boskJavaType = typeFactory.constructType(boskObjectTypeRef);
		JavaType mapJavaType = typeFactory.constructParametricType(Map.class, String.class, Object.class);
		String json = plainMapper.writerFor(mapJavaType).writeValueAsString(plainObject);
		try (var _ = jacksonSerializer.newDeserializationScope(path)) {
			return boskMapper.readerFor(boskJavaType).readValue(json);
		}
	}

	private Object boskListFor(List<?> plainList, JavaType boskListType, Path path) {
		JavaType boskJavaType = typeFactory.constructType(boskListType);
		JavaType listJavaType = typeFactory.constructParametricType(List.class, Object.class);
		String json = plainMapper.writerFor(listJavaType).writeValueAsString(plainList);
		try (var _ = jacksonSerializer.newDeserializationScope(path)) {
			return boskMapper.readerFor(boskJavaType).readValue(json);
		}
	}

	@Test
	void polyfill_works() {
		HasPolyfill deserialized = boskObjectFor(emptyMap(), new TypeReference<>(){}, Path.empty());
		assertEquals(HasPolyfill.DEFAULT_STRING_FIELD_VALUE, deserialized.stringField1());
		assertEquals(HasPolyfill.DEFAULT_STRING_FIELD_VALUE, deserialized.stringField2());
	}

	public record HasPolyfill(
		String stringField1,
		String stringField2
	) implements StateTreeNode {
		@Polyfill({"stringField1","stringField2"})
		public static final String DEFAULT_STRING_FIELD_VALUE = "defaultValue";
	}

	@Test
	void taggedUnion_works() {
		var taggedUnion = TaggedUnion.of(new VariantCase1("fieldValue"));

		Map<String, Object> expected = Map.of(
			"variant1", Map.of("stringField", "fieldValue")
		);

		assertJacksonWorks(expected, taggedUnion, new TypeReference<TaggedUnion<Variant>>() {}, Path.just("doesn't matter"));
	}

	// Sad paths

	@Test
	void nonexistentPath_throws() {
		assertThrows(UnexpectedPathException.class, () ->
			boskMapper
				.readerFor(typeFactory.constructParametricType(Reference.class, String.class))
				.readValue("\"/some/nonexistent/path\""));
	}

	@Test
	void catalogWithContentsArray_throws() {
		assertJsonException("{ \"contents\": [] }", Catalog.class, TestEntity.class);
	}

	@Test
	void listingWithoutDomain_throws() {
		assertJsonException("{ \"ids\": [] }", Listing.class, TestEntity.class);
	}

	@Test
	void listingWithoutIDs_throws() {
		assertJsonException("{ \"domain\": \"/entities\" }", Listing.class, TestEntity.class);
	}

	@Test
	void listingWithExtraneousField_throws() {
		assertJsonException("{ \"domain\": \"/entities\", \"extraneous\": 0, \"ids\": [] }", Listing.class, TestEntity.class);
	}

	@Test
	void listingWithTwoDomains_throws() {
		assertJsonException("{ \"domain\": \"/entities\", \"domain\": \"/entities\", \"ids\": [] }", Listing.class, TestEntity.class);
	}

	@Test
	void listingWithTwoIDsFields_throws() {
		assertJsonException("{ \"domain\": \"/entities\", \"ids\": [], \"ids\": [] }", Listing.class, TestEntity.class);
	}

	@Test
	void sideTableWithNoDomain_throws() {
		assertJsonException("{ \"valuesById\": [] }", SideTable.class, TestEntity.class, String.class);
	}

	@Test
	void sideTableWithNoValues_throws() {
		assertJsonException("{ \"domain\": \"/entities\" }", SideTable.class, TestEntity.class, String.class);
	}

	@Test
	void sideTableWithExtraneousField_throws() {
		assertJsonException("{ \"domain\": \"/entities\", \"valuesById\": [], \"extraneous\": 0 }", SideTable.class, TestEntity.class, String.class);
	}

	@Test
	void sideTableWithTwoDomains_throws() {
		assertJsonException("{ \"domain\": \"/entities\", \"domain\": \"/entities\", \"valuesById\": [] }", SideTable.class, TestEntity.class, String.class);
	}

	@Test
	void sideTableWithTwoValuesFields_throws() {
		assertJsonException("{ \"domain\": \"/entities\", \"valuesById\": [], \"valuesById\": [] }", SideTable.class, TestEntity.class, String.class);
	}

	private void assertJsonException(String json, Class<?> rawClass, Type... parameters) {
		JavaType[] params = new JavaType[parameters.length];
		for (int i = 0; i < parameters.length; i++) {
			params[i] = typeFactory.constructType(parameters[i]);
		}
		JavaType parametricType = typeFactory.constructParametricType(rawClass, params);
		assertThrows(StreamReadException.class, () -> boskMapper.readerFor(parametricType).readValue(json));
	}

	@Test
	void deserializationPath_wrongType_throws() {
		assertThrows(UnexpectedPathException.class, () -> {
			boskMapper.readerFor(WrongType.class).readValue("{ \"notAString\": { \"id\": \"123\" } }");
		});
	}

	public record WrongType(
		@DeserializationPath("/entities/123/string") ImplicitRefs notAString
	) implements StateTreeNode { }

	@Test
	void deserializationPath_parameterUnbound_throws() {
		assertThrows(ParameterUnboundException.class, () -> {
			boskMapper.readerFor(EntityParameter.class).readValue("{ \"field\": { \"id\": \"123\" } }");
		});
	}

	public record EntityParameter(
		@DeserializationPath("/entities/-entity-") ImplicitRefs field
	) implements StateTreeNode { }

	@Test
	void deserializationPath_malformedPath() {
		assertThrows(MalformedPathException.class, () -> {
			boskMapper.readerFor(MalformedPath.class).readValue("{ \"field\": { \"id\": \"123\" } }");
		});
	}

	public record MalformedPath(
		@DeserializationPath("/malformed////path") ImplicitRefs field
	) implements StateTreeNode { }

	@Test
	void deserializationPath_nonexistentPath_throws() {
		assertThrows(UnexpectedPathException.class, () -> {
			boskMapper.readerFor(NonexistentPath.class).readValue("{ \"field\": { \"id\": \"123\" } }");
		});
	}

	public record NonexistentPath(
		@DeserializationPath("/nonexistent/path") ImplicitRefs field
	) implements StateTreeNode { }

}
