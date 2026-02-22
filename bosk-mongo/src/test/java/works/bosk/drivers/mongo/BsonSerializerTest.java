package works.bosk.drivers.mongo;

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
import works.bosk.Path;
import works.bosk.SideTable;
import works.bosk.StateTreeNode;
import works.bosk.exceptions.InvalidTypeException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static works.bosk.testing.BoskTestUtils.boskName;

class BsonSerializerTest {

	@Test
	void sideTableOfSideTables() {
		BsonSerializer bp = new BsonSerializer();
		Bosk<Root> bosk = new Bosk<Root>(boskName(), Root.class, this::defaultRoot, BoskConfig.simple());
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

	private Root defaultRoot(Bosk<Root> bosk) throws InvalidTypeException {
		CatalogReference<Item> catalogRef = bosk.rootReference().thenCatalog(Item.class, Path.just(Root.Fields.items));
		return new Root(Catalog.empty(), SideTable.empty(catalogRef));
	}

	@FieldNameConstants
	public record Root(
		Catalog<Item> items,
		SideTable<Item, SideTable<Item, String>> nestedSideTable
	) implements StateTreeNode { }

	public record Item(
		Identifier id
	) implements Entity { }

}
