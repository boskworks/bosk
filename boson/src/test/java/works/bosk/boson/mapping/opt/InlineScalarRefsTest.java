package works.bosk.boson.mapping.opt;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import works.bosk.boson.codec.PrimitiveInjector;
import works.bosk.boson.codec.PrimitiveInjector.PrimitiveNumber;
import works.bosk.boson.mapping.TypeMap;
import works.bosk.boson.mapping.TypeScanner;
import works.bosk.boson.mapping.spec.ArrayNode;
import works.bosk.boson.mapping.spec.BoxedPrimitiveSpec;
import works.bosk.boson.mapping.spec.FixedMapMember;
import works.bosk.boson.mapping.spec.FixedMapNode;
import works.bosk.boson.mapping.spec.PrimitiveNumberNode;
import works.bosk.boson.mapping.spec.StringNode;
import works.bosk.boson.mapping.spec.TypeRefNode;
import works.bosk.boson.mapping.spec.UniformMapNode;
import works.bosk.boson.mapping.spec.handles.TypedHandle;
import works.bosk.boson.mapping.spec.handles.TypedHandles;
import works.bosk.boson.types.BoundType;
import works.bosk.boson.types.DataType;
import works.bosk.boson.types.KnownType;
import works.bosk.boson.types.TypeReference;
import works.bosk.junit.InjectFrom;
import works.bosk.junit.InjectedTest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static works.bosk.boson.mapping.TypeMap.Settings.SHALLOW;
import static works.bosk.boson.types.DataType.BOOLEAN;
import static works.bosk.boson.types.DataType.STRING;

@InjectFrom(PrimitiveInjector.class)
class InlineScalarRefsTest {
	TypeMap typeMap;

	@BeforeEach
	void setup() {
		typeMap = new TypeMap(SHALLOW);
		typeMap.put(STRING, new StringNode());
	}

	@Test
	void referenceToReference() {
		typeMap.put(BOOLEAN, new TypeRefNode(STRING)); // zany
		var optimized = new InlineScalarRefs(typeMap)
			.optimize(new TypeRefNode(BOOLEAN));
		assertEquals(new StringNode(), optimized);
	}

	@InjectedTest
	void primitiveNumber(PrimitiveNumber prim) {
		var dataType = DataType.known(prim.type());
		var node = new PrimitiveNumberNode(prim.type());
		typeMap.put(dataType, node);
		var optimized = new InlineScalarRefs(typeMap)
			.optimize(new TypeRefNode(dataType));
		assertEquals(node, optimized);
	}

	@Test
	void array() {
		BoundType listType = (BoundType) DataType.of(new TypeReference<List<String>>() { });
		typeMap.put(listType, new ArrayNode(
			new StringNode(),
			TypeScanner.listAccumulator(listType),
			TypeScanner.listEmitter(listType)
		));
		var optimized = new InlineScalarRefs(typeMap)
			.optimize(new TypeRefNode(listType));
		assertEquals(new TypeRefNode(listType), optimized,
			"Array types are not inlined");
	}

	@Test
	void arrayElement() {
		BoundType listType = (BoundType) DataType.of(new TypeReference<List<String>>() { });
		var accumulator = TypeScanner.listAccumulator(listType);
		var emitter = TypeScanner.listEmitter(listType);
		var original = new ArrayNode(
			new TypeRefNode(STRING), // The type reference to be inlined
			accumulator,
			emitter
		);
		var expected = new ArrayNode(
			new StringNode(), // Inlined element node
			accumulator,
			emitter
		);
		var actual = new InlineScalarRefs(typeMap).optimize(original);
		assertEquals(expected, actual);
	}

	@Test
	void map() {
		BoundType mapType = (BoundType) DataType.of(new TypeReference<Map<String, Integer>>() { });
		var accumulator = TypeScanner.mapAccumulator(mapType);
		var emitter = TypeScanner.mapEmitter(mapType);
		typeMap.put(mapType, new UniformMapNode(
			new StringNode(),
			new BoxedPrimitiveSpec(new PrimitiveNumberNode(int.class)),
			accumulator,
			emitter
		));
		var optimized = new InlineScalarRefs(typeMap)
			.optimize(new TypeRefNode(mapType));
		assertEquals(new TypeRefNode(mapType), optimized,
			"Object types are not inlined");
	}

	@Test
	void mapEntry() {
		BoundType mapType = (BoundType) DataType.of(new TypeReference<Map<String, Integer>>() { });
		KnownType integerType = DataType.known(Integer.class);
		BoxedPrimitiveSpec boxedIntegerSpec = new BoxedPrimitiveSpec(new PrimitiveNumberNode(int.class));
		typeMap.put(integerType, boxedIntegerSpec);
		var accumulator = TypeScanner.mapAccumulator(mapType);
		var emitter = TypeScanner.mapEmitter(mapType);
		var original = new UniformMapNode(
			new TypeRefNode(STRING),      // Should get inlined
			new TypeRefNode(integerType), // Should also get inlined
			accumulator,
			emitter
		);
		var expected = new UniformMapNode(
			new StringNode(), // Inlined key node
			boxedIntegerSpec, // Inlined value node
			accumulator,
			emitter
		);
		var actual = new InlineScalarRefs(typeMap).optimize(original);
		assertEquals(expected, actual);
	}

	@Test
	void fixedMap() {
		var memberSpecs = new LinkedHashMap<String, FixedMapMember>();
		var stringIdentity = TypedHandles.function(STRING, STRING, Function.identity());
		memberSpecs.put("theField", new FixedMapMember(
			new StringNode(),
			stringIdentity
		));
		var original = new FixedMapNode(memberSpecs, stringIdentity);
		var optimized = new InlineScalarRefs(typeMap).optimize(original);
		assertEquals(original, optimized,
			"FixedMapNode member TypeRefs are not inlined");
	}

	@Test
	void fixedMapMember() {
		var memberSpecs = new LinkedHashMap<String, FixedMapMember>();
		TypedHandle stringIdentity = TypedHandles.function(STRING, STRING, Function.identity());
		memberSpecs.put("theField", new FixedMapMember(
			new TypeRefNode(STRING), // Should get inlined
			stringIdentity
		));
		var original = new FixedMapNode(memberSpecs, stringIdentity);
		var expectedMemberSpecs = new LinkedHashMap<String, FixedMapMember>();
		expectedMemberSpecs.put("theField", new FixedMapMember(
			new StringNode(), // Inlined node
			stringIdentity
		));
		var expected = new FixedMapNode(expectedMemberSpecs, stringIdentity);
		var actual = new InlineScalarRefs(typeMap).optimize(original);
		assertEquals(expected, actual);
	}
}
