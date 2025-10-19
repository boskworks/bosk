package works.bosk.boson.mapping;

import java.io.IOException;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import works.bosk.boson.codec.Codec;
import works.bosk.boson.codec.CodecBuilder;
import works.bosk.boson.codec.io.CharArrayJsonReader;
import works.bosk.boson.mapping.TypeScanner.Bundle;
import works.bosk.boson.mapping.TypeScanner.Directive;
import works.bosk.boson.mapping.spec.JsonValueSpec;
import works.bosk.boson.mapping.spec.RepresentAsSpec;
import works.bosk.boson.mapping.spec.TypeRefNode;
import works.bosk.boson.types.DataType;
import works.bosk.boson.types.TypeReference;
import works.bosk.boson.types.WildcardType;

import static org.junit.jupiter.api.Assertions.assertEquals;

// TODO: Test a bundle with some types
class TypeScannerTest {
	TypeScanner scanner;

	@BeforeEach
	void setUp() {
		scanner = new TypeScanner(new TypeMap.Settings(false, false, false, false, false));
	}

	public record FloatAsString(String text) {}

	@Test
	void testSimpleDirective() throws IOException {
		scanner.addLast(new Bundle(List.of(), List.of(), List.of(
			new Directive(
				DataType.FLOAT,
				_ -> RepresentAsSpec.as(
					new TypeRefNode(DataType.known(FloatAsString.class)),
					DataType.FLOAT,
					(Float f) -> new FloatAsString(f.toString()),
					(FloatAsString s) -> Float.parseFloat(s.text())
				))
		)));
		var typeMap = scanner
			.scan(DataType.FLOAT)
			.scan(DataType.known(FloatAsString.class))
			.build();
		JsonValueSpec spec = typeMap.get(DataType.FLOAT);
		Codec codec = CodecBuilder.using(typeMap).build();
		Object actual = codec.parserFor(spec).parse(CharArrayJsonReader.forString(
			"""
			{ "text": "123.45" }
			"""));
		assertEquals(123.45f, actual);
	}

	public interface Overridden {
		int value();
	}

	public record OverriddenImpl(int value) implements Overridden {}

	@Test
	void testTypeBound() throws IOException {
		scanner.addLast(new Bundle(List.of(), List.of(), List.of(
			new Directive(
				WildcardType.extends_(Overridden.class),
				t -> RepresentAsSpec.asInt(
					t,
					Overridden::value,
					OverriddenImpl::new
				))
		)));
		DataType implType = DataType.of(new TypeReference<List<OverriddenImpl>>() { });
		var typeMap = scanner
			.scan(DataType.INT)
			.scan(implType) // This seems unfortunate
			.build();
		JsonValueSpec spec = typeMap.get(implType);
		Codec codec = CodecBuilder.using(typeMap).build();
		Object actual = codec.parserFor(spec).parse(CharArrayJsonReader.forString(
			"""
			[ 123, 456 ]
			"""));
		assertEquals(List.of(new OverriddenImpl(123), new OverriddenImpl(456)), actual);
	}
}
