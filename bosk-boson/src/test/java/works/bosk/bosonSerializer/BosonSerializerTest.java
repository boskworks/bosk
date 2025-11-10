package works.bosk.bosonSerializer;

import java.io.IOException;
import java.io.StringWriter;
import java.io.Writer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import works.bosk.Bosk;
import works.bosk.Catalog;
import works.bosk.CatalogReference;
import works.bosk.Entity;
import works.bosk.Identifier;
import works.bosk.SideTable;
import works.bosk.SideTableReference;
import works.bosk.StateTreeNode;
import works.bosk.annotations.ReferencePath;
import works.bosk.boson.codec.Codec;
import works.bosk.boson.codec.CodecBuilder;
import works.bosk.boson.codec.io.CharArrayJsonReader;
import works.bosk.boson.mapping.TypeMap;
import works.bosk.boson.mapping.TypeScanner;
import works.bosk.boson.types.DataType;
import works.bosk.boson.types.TypeReference;
import works.bosk.exceptions.InvalidTypeException;

import static org.junit.jupiter.api.Assertions.assertEquals;

public class BosonSerializerTest {


	public record Root(
		Catalog<Key> keys,
		SideTable<Key, String> sideTable
	) implements StateTreeNode {}

	public record Key(
		Identifier id
	) implements Entity {}

	public interface Refs {
		@ReferencePath("/keys") CatalogReference<Key> keys();
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
			b -> {
				Refs refs = b.buildReferences(Refs.class);
				return new Root(Catalog.empty(), SideTable.empty(refs.keys()));
			},
			Bosk.simpleDriver(),
			Bosk.simpleRegistrar()
		);
		refs = bosk.buildReferences(Refs.class);
		typeMap = new TypeScanner(TypeMap.Settings.DEFAULT)
			.addBundle(new BosonSerializer().bundleFor(bosk))
			.scan(DataType.of(Root.class))
			.build();
		codec = CodecBuilder.using(typeMap).buildInterpreter();
	}

	@Test
	void sideTable() throws InvalidTypeException, IOException {
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
}
