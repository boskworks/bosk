package works.bosk.bosonSerializer;

import java.io.IOException;
import java.io.StringWriter;
import java.io.Writer;
import java.lang.reflect.Type;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.NonNull;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import works.bosk.Bosk;
import works.bosk.BoskConfig;
import works.bosk.Catalog;
import works.bosk.CatalogReference;
import works.bosk.Entity;
import works.bosk.Identifier;
import works.bosk.MapValue;
import works.bosk.Path;
import works.bosk.Reference;
import works.bosk.SideTable;
import works.bosk.SideTableReference;
import works.bosk.StateTreeNode;
import works.bosk.TaggedUnion;
import works.bosk.TaggedUnionCase;
import works.bosk.annotations.ReferencePath;
import works.bosk.annotations.Self;
import works.bosk.annotations.TaggedUnionCaseMap;
import works.bosk.boson.codec.Codec;
import works.bosk.boson.codec.CodecBuilder;
import works.bosk.boson.codec.io.CharArrayJsonReader;
import works.bosk.boson.exceptions.JsonProcessingException;
import works.bosk.boson.mapping.TypeMap;
import works.bosk.boson.mapping.TypeScanner;
import works.bosk.boson.types.DataType;
import works.bosk.boson.types.TypeReference;
import works.bosk.exceptions.InvalidTypeException;
import works.bosk.util.Types;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

public class BosonSerializerTest {

	public record Root(
		Catalog<Key> keys,
		Catalog<Item> items,
		SideTable<Key, String> sideTable,
		@Self Reference<Root> self
	) implements StateTreeNode {}

	public record Key(
		Identifier id
	) implements Entity {}

	public record Item(
		Identifier id,
		@Self Reference<Item> self
	) implements Entity {}

	public interface Refs {
		@ReferencePath("/keys") CatalogReference<Key> keys();
		@ReferencePath("/items") CatalogReference<Item> items();
		@ReferencePath("/items/-item-") Reference<Item> item(Identifier item);
		@ReferencePath("/sideTable") SideTableReference<Key, String> sideTable();
	}

	Bosk<Root> bosk;
	Refs refs;
	TypeMap typeMap;
	Codec codec;

	@BeforeEach
	void setup() throws InvalidTypeException {
		bosk = new Bosk<>(
			"test",
			Root.class,
			BosonSerializerTest::emptyState,
			BoskConfig.simple());
		refs = bosk.buildReferences(Refs.class);
		typeMap = new TypeScanner(TypeMap.Settings.DEFAULT)
			.addBundle(new BosonSerializer().bundleFor(bosk))
			.scan(DataType.of(Root.class))
			.build();
		codec = CodecBuilder.using(typeMap).buildInterpreter();
	}

	private static Root emptyState(Bosk<Root> b) throws InvalidTypeException {
		return emptyRoot(b);
	}

	private static @NonNull Root emptyRoot(Bosk<Root> b) throws InvalidTypeException {
		Refs refs = b.buildReferences(Refs.class);
		return new Root(
			Catalog.empty(),
			Catalog.empty(),
			SideTable.empty(refs.keys()),
			b.rootReference()
		);
	}

	@Test
	void sideTable() throws IOException {
		var object = SideTable.of(refs.keys(), Identifier.from("key"), "value");


		var gen = codec.generatorFor(typeMap.get(DataType.of(refs.sideTable().targetType())));
		Writer stringWriter = new StringWriter();
		gen.generate(stringWriter, object);
		String json = stringWriter.toString();

		var parser = codec.parserFor(typeMap.get(DataType.of(refs.sideTable().targetType())));
		var parsed = parser.parse(new CharArrayJsonReader(json.toCharArray()));
		assertEquals(object, parsed);
	}

	@Test
	void reference() {
		var gen = codec.generatorFor(typeMap.get(DataType.of(new TypeReference<CatalogReference<Key>>(){})));
		Writer stringWriter = new StringWriter();
		gen.generate(stringWriter, refs.keys());
		String json = stringWriter.toString();
		assertEquals("\"/keys\"", json);
	}

	@Test
	void selfReferences() throws IOException {
		var parser = codec.parserFor(typeMap.get(DataType.of(Root.class)));
		Root parsed = (Root)parser.parse(new CharArrayJsonReader(
			// Note: no explicit self-references here
			"""
			{
				"keys": [],
				"items": [{"item1": {"id": "item1"}}],
				"sideTable": {
					"domain": "/keys",
					"valuesById": []
				}
			}
			""".toCharArray()
		));

		assertEquals(bosk.rootReference(), parsed.self());
		Identifier item1 = Identifier.from("item1");
		assertEquals(refs.item(item1), parsed.items().get(item1).self());
	}

	@Test
	void taggedUnionCaseSelfReference_includesTagPath() throws InvalidTypeException, IOException {
		// A variant case lives at /variant/<tag>, so a @Self reference inside the case
		// must resolve to the tag path, not the union field path.
		Bosk<VariantRoot> variantBosk = new Bosk<>("variant", VariantRoot.class, BosonSerializerTest::initialVariantRoot, BoskConfig.simple());
		var variantTypeMap = new TypeScanner(TypeMap.Settings.DEFAULT)
			.addBundle(new BosonSerializer().bundleFor(variantBosk))
			.scan(DataType.of(VariantRoot.class))
			.build();
		var variantCodec = CodecBuilder.using(variantTypeMap).buildInterpreter();

		var parser = variantCodec.parserFor(variantTypeMap.get(DataType.of(VariantRoot.class)));
		VariantRoot parsed = (VariantRoot)parser.parse(new CharArrayJsonReader(
			"""
			{"variant": {"case1": {"stringField": "hello"}}}
			""".toCharArray()
		));

		assertEquals(Path.parse("/variant/case1"), ((TaggedUnionCase1) parsed.variant().value()).self().path());
	}

	@Test
	void taggedUnionWithTwoParameterizationsOfOneRecord() throws IOException {
		Bosk<ParameterizedVariantRoot> variantBosk = new Bosk<>("paramVariant", ParameterizedVariantRoot.class,
			_ -> new ParameterizedVariantRoot(TaggedUnion.of(new BoxedCase<>("hello"))), BoskConfig.simple());
		var parameterizedTypeMap = new TypeScanner(TypeMap.Settings.DEFAULT)
			.addBundle(new BosonSerializer().bundleFor(variantBosk))
			.scan(DataType.of(ParameterizedVariantRoot.class))
			.build();
		var parameterizedCodec = CodecBuilder.using(parameterizedTypeMap).buildInterpreter();
		var spec = parameterizedTypeMap.get(DataType.of(ParameterizedVariantRoot.class));

		for (ParameterizedVariant variant : List.of(new BoxedCase<>("hello"), new BoxedCase<>(42))) {
			var original = new ParameterizedVariantRoot(TaggedUnion.of(variant));
			var writer = new StringWriter();
			parameterizedCodec.generatorFor(spec).generate(writer, original);
			var parsed = parameterizedCodec.parserFor(spec).parse(new CharArrayJsonReader(writer.toString().toCharArray()));
			assertEquals(original, parsed);
		}
	}

	@Test
	void taggedUnionWithGenericCaseSupertype() throws IOException {
		Bosk<GenericVariantRoot> variantBosk = new Bosk<>("genericVariant", GenericVariantRoot.class,
			_ -> new GenericVariantRoot(TaggedUnion.<GenericVariant<String>>of(new GenericCase<>("hello"))), BoskConfig.simple());
		var genericTypeMap = new TypeScanner(TypeMap.Settings.DEFAULT)
			.addBundle(new BosonSerializer().bundleFor(variantBosk))
			.scan(DataType.of(GenericVariantRoot.class))
			.build();
		var genericCodec = CodecBuilder.using(genericTypeMap).buildInterpreter();
		var spec = genericTypeMap.get(DataType.of(GenericVariantRoot.class));

		var original = new GenericVariantRoot(TaggedUnion.<GenericVariant<String>>of(new GenericCase<>("hello")));
		var writer = new StringWriter();
		genericCodec.generatorFor(spec).generate(writer, original);
		var parsed = genericCodec.parserFor(spec).parse(new CharArrayJsonReader(writer.toString().toCharArray()));
		assertEquals(original, parsed);
	}

	private static @NonNull VariantRoot initialVariantRoot(Bosk<VariantRoot> bosk) throws InvalidTypeException {
		return new VariantRoot(TaggedUnion.of(new TaggedUnionCase1(bosk.rootReference().then(TaggedUnionCase1.class, Path.parse("/variant/case1")), "hello")));
	}

	public record VariantRoot(TaggedUnion<Variant> variant) implements StateTreeNode { }

	public interface Variant extends TaggedUnionCase {
		@Override default String tag() { return "case1"; }
		@TaggedUnionCaseMap
		MapValue<Type> CASES = MapValue.singleton("case1", TaggedUnionCase1.class);
	}

	public record TaggedUnionCase1(@Self Reference<TaggedUnionCase1> self, String stringField) implements Variant { }

	public record ParameterizedVariantRoot(TaggedUnion<ParameterizedVariant> variant) implements StateTreeNode { }

	public interface ParameterizedVariant extends TaggedUnionCase {
		@TaggedUnionCaseMap
		MapValue<Type> CASES = MapValue.copyOf(Map.of(
			"string", Types.parameterizedType(BoxedCase.class, String.class),
			"integer", Types.parameterizedType(BoxedCase.class, Integer.class)
		));
	}

	public record BoxedCase<T>(T value) implements ParameterizedVariant {
		@Override public String tag() { return value instanceof String ? "string" : "integer"; }
	}

	public record GenericVariantRoot(TaggedUnion<GenericVariant<String>> variant) implements StateTreeNode { }

	public interface GenericVariant<T> extends TaggedUnionCase {
		@TaggedUnionCaseMap
		MapValue<Type> CASES = MapValue.singleton("caseA",
			Types.parameterizedType(GenericCase.class, GenericVariant.class.getTypeParameters()[0]));
	}

	public record GenericCase<T>(T value) implements GenericVariant<T> {
		@Override public String tag() { return "caseA"; }
	}

	@Test
	void genericNode() throws IOException {
		var nodeType = new TypeReference<Node<String>>() {};
		var nodeTypeMap = new TypeScanner(TypeMap.Settings.DEFAULT)
			.addBundle(new BosonSerializer().bundleFor(bosk))
			.scan(DataType.of(nodeType))
			.build();
		var nodeCodec = CodecBuilder.using(nodeTypeMap).buildInterpreter();

		Node<String> original = new Node<>("hello");
		Writer writer = new StringWriter();
		nodeCodec.generatorFor(nodeTypeMap.get(DataType.of(nodeType))).generate(writer, original);
		var parsed = nodeCodec.parserFor(nodeTypeMap.get(DataType.of(nodeType)))
			.parse(new CharArrayJsonReader(writer.toString().toCharArray()));
		assertEquals(original, parsed);
	}

	public record Node<T>(T value) implements StateTreeNode { }

	@Test
	void catalogEntryKeyMismatch_throws() throws InvalidTypeException {
		// The JSON member name is the catalog key, so it must agree with the entry's own id.
		// Otherwise the entry would be silently re-keyed by its id, discarding the key.
		var parser = codec.parserFor(typeMap.get(DataType.of(new TypeReference<Catalog<Key>>(){})));
		assertThrows(JsonProcessingException.class, () -> parser.parse(new CharArrayJsonReader(
			"""
			[{"w1": {"id": "w2"}}]
			""".toCharArray()
		)));
	}

}
