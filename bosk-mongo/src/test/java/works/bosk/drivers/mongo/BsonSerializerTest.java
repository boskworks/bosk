package works.bosk.drivers.mongo;

import java.lang.reflect.Type;
import java.util.LinkedHashMap;
import java.util.Map;
import lombok.experimental.FieldNameConstants;
import org.bson.BsonDocument;
import org.bson.BsonDocumentReader;
import org.bson.BsonDocumentWriter;
import org.bson.codecs.Codec;
import org.bson.codecs.DecoderContext;
import org.bson.codecs.EncoderContext;
import org.bson.codecs.ValueCodecProvider;
import org.bson.codecs.configuration.CodecRegistries;
import org.bson.codecs.configuration.CodecRegistry;
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
import works.bosk.StateTreeNode;
import works.bosk.TaggedUnion;
import works.bosk.VariantCase;
import works.bosk.annotations.Self;
import works.bosk.annotations.VariantCaseMap;
import works.bosk.exceptions.InvalidTypeException;
import works.bosk.util.Types;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static works.bosk.testing.BoskTestUtils.boskName;

class BsonSerializerTest {

	@Test
	void mapValueKeys_areEncodedLikeOtherFieldNames() throws InvalidTypeException {
		BsonSerializer bp = new BsonSerializer();
		Bosk<Root> bosk = new Bosk<Root>(boskName(), Root.class, this::initialState, BoskConfig.simple());
		CodecRegistry registry = CodecRegistries.fromProviders(bp.codecProviderFor(bosk), new ValueCodecProvider());
		Type mapValueType = Types.parameterizedType(MapValue.class, String.class);
		@SuppressWarnings("unchecked")
		Codec<MapValue<String>> codec = (Codec<MapValue<String>>) (Codec<?>) bp.getCodec(mapValueType, MapValue.class, registry, bosk);

		// These keys are all legal MapValue keys, but MongoDB forbids them as literal field names
		Map<String, String> entries = new LinkedHashMap<>();
		entries.put("plain", "fine");
		entries.put("a.b", "dot");
		entries.put("$dollar", "dollar");
		entries.put("a|pipe", "pipe");
		entries.put("a b", "space");
		entries.put("100%", "percent");
		entries.put("", "blank");
		MapValue<String> original = MapValue.copyOf(entries);

		BsonDocument document = new BsonDocument();
		codec.encode(new BsonDocumentWriter(document), original, EncoderContext.builder().build());
		for (String key : document.keySet()) {
			assertFalse(key.contains("."), "MapValue key must not contain a literal '.': \"" + key + "\"");
			assertFalse(key.contains("$"), "MapValue key must not contain a literal '$': \"" + key + "\"");
			assertFalse(key.contains("|"), "MapValue key must not contain a literal '|': \"" + key + "\"");
		}

		MapValue<String> decoded = codec.decode(new BsonDocumentReader(document), DecoderContext.builder().build());
		assertEquals(original, decoded);
	}

	@Test
	void sideTableOfSideTables() {
		BsonSerializer bp = new BsonSerializer();
		Bosk<Root> bosk = new Bosk<Root>(boskName(), Root.class, this::initialState, BoskConfig.simple());
		CodecRegistry registry = CodecRegistries.fromProviders(bp.codecProviderFor(bosk), new ValueCodecProvider());
		Codec<Root> codec = registry.get(Root.class);
		try (var _ = bosk.readSession()) {
			BsonDocument document = new BsonDocument();
			Root original = bosk.rootReference().value();
			codec.encode(new BsonDocumentWriter(document), original, EncoderContext.builder().build());
			Root decoded = codec.decode(new BsonDocumentReader(document), DecoderContext.builder().build());
			assertEquals(original, decoded);
		}
	}

	@Test
	void variantCaseSelfReference_includesTagPath() throws InvalidTypeException {
		BsonSerializer bp = new BsonSerializer();
		Bosk<VariantRoot> bosk = new Bosk<VariantRoot>(boskName(), VariantRoot.class, this::initialVariantRoot, BoskConfig.simple());
		CodecRegistry registry = CodecRegistries.fromProviders(bp.codecProviderFor(bosk), new ValueCodecProvider());
		Codec<VariantRoot> codec = registry.get(VariantRoot.class);

		VariantRoot original = initialVariantRoot(bosk);
		BsonDocument document = new BsonDocument();
		codec.encode(new BsonDocumentWriter(document), original, EncoderContext.builder().build());
		VariantRoot decoded = codec.decode(new BsonDocumentReader(document), DecoderContext.builder().build());
		assertEquals(Path.parse("/variant/case1"), ((VariantCase1) decoded.variant().variant()).self().path());
	}

	private VariantRoot initialVariantRoot(Bosk<VariantRoot> bosk) throws InvalidTypeException {
		return new VariantRoot(TaggedUnion.of(new VariantCase1(bosk.rootReference().then(VariantCase1.class, Path.parse("/variant/case1")), "hello")));
	}

	private Root initialState(Bosk<Root> bosk) throws InvalidTypeException {
		CatalogReference<Item> catalogRef = bosk.rootReference().thenCatalog(Item.class, Path.just(Root.Fields.items));
		return new Root(
			Catalog.empty(),
			SideTable.empty(catalogRef)
		);
	}

	@FieldNameConstants
	public record Root(
		Catalog<Item> items,
		SideTable<Item, SideTable<Item, String>> nestedSideTable
	) implements StateTreeNode { }

	public record Item(
		Identifier id
	) implements Entity { }

	public record VariantRoot(TaggedUnion<Variant> variant) implements StateTreeNode { }

	public interface Variant extends VariantCase {
		@Override default String tag() { return "case1"; }
		@VariantCaseMap
		MapValue<Type> CASES = MapValue.singleton("case1", VariantCase1.class);
	}

	public record VariantCase1(@Self Reference<VariantCase1> self, String stringField) implements Variant { }

}
