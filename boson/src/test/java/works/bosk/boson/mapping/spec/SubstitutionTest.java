package works.bosk.boson.mapping.spec;

import java.lang.invoke.MethodHandles;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import works.bosk.boson.mapping.TypeMap;
import works.bosk.boson.mapping.TypeScanner;
import works.bosk.boson.mapping.spec.handles.TypedHandle;
import works.bosk.boson.mapping.spec.handles.TypedHandles;
import works.bosk.boson.types.DataType;
import works.bosk.boson.types.KnownType;
import works.bosk.boson.types.TypeReference;
import works.bosk.boson.types.TypeVariable;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static works.bosk.boson.mapping.TypeMap.Settings.SHALLOW;
import static works.bosk.boson.types.DataType.OBJECT;
import static works.bosk.boson.types.DataType.STRING;

public class SubstitutionTest {
	/**
	 * Note: since {@link TypedHandle} requires the types to be {@link KnownType},
	 * we can't make {@code TypedHandle} with type variables directly,
	 * so instead we use {@code List<T>} when we want to put a type
	 * variable into a {@code TypedHandle}.
	 */
	public static final KnownType LIST_OF_STRING = DataType.known(new TypeReference<List<String>>() { });

	/**
	 * Useful for building up spec node trees for testing
	 */
	TypeScanner scanner = new TypeScanner(SHALLOW);

	@Test
	<T> void arrayNode() {
		DataType unknownType = DataType.of(new TypeReference<List<List<T>>>() { });
		DataType knownType   = DataType.of(new TypeReference<List<List<String>>>() { });
		TypeMap typeMap = scanner.scan(unknownType).build();
		var original = typeMap.get(unknownType);
		var actual = (ArrayNode) original.specialize(Map.of("T", STRING));

		// It's tricky to construct the expected node exactly, since its MethodHandles
		// don't implement the kind of equals we'd need, so we check the properties
		// we care about.
		assertEquals(knownType, actual.dataType());
		assertEquals(knownType, actual.accumulator().resultType());
		assertEquals(knownType, actual.emitter().dataType());
		assertEquals(LIST_OF_STRING, actual.elementNode().dataType());
		assertEquals(LIST_OF_STRING, actual.accumulator().elementType());
		assertEquals(LIST_OF_STRING, actual.emitter().elementType());
	}

	@Test
	<T> void fixedMap() {
		record TestRecord<T>(T field) {}
		DataType unknownType = DataType.of(new TypeReference<TestRecord<List<T>>>() { });
		DataType knownType = DataType.of(new TypeReference<TestRecord<List<String>>>() { });
		var original = scanner
			.useLookup(MethodHandles.lookup())
			.scan(unknownType)
			.build()
			.get(unknownType);
		FixedMapNode actual = (FixedMapNode) original.specialize(Map.of("T", STRING));
		assertEquals(knownType, actual.dataType());
		assertEquals(LIST_OF_STRING, actual.finisher().parameterTypes().getFirst());
	}

	@Test
	<T> void uniformMap() {
		DataType unknownType = DataType.of(new TypeReference<Map<String, List<T>>>() { });
		DataType knownType = DataType.of(new TypeReference<Map<String, List<String>>>() { });
		var original = scanner
			.useLookup(MethodHandles.lookup())
			.scan(unknownType)
			.build()
			.get(unknownType);
		UniformMapNode actual = (UniformMapNode) original.specialize(Map.of("T", STRING));
		assertEquals(knownType, actual.dataType());
		assertEquals(knownType, actual.accumulator().resultType());
		assertEquals(knownType, actual.emitter().dataType());
		assertEquals(LIST_OF_STRING, actual.valueNode().dataType());
		assertEquals(LIST_OF_STRING, actual.accumulator().valueType());
		assertEquals(LIST_OF_STRING, actual.emitter().getValue().returnType());
	}

	@Test
	void typedHandleEquality() {
		// We want the property that, if substitution makes no difference,
		// then the resulting TypedHandle is equal to the original.
		// This doesn't necessarily happen naturally because MethodHandle
		// equality is by identity.
		var integerType = DataType.known(Integer.class);
		TypedHandle noTypeVariables = TypedHandles.identity(STRING);
		assertEquals(noTypeVariables, noTypeVariables.substitute(Map.of("X", integerType)),
			"Substituting a type variable not used in the original TypedHandle should yield an equal TypedHandle");

		TypedHandle withTypeVariable = TypedHandles.identity(new TypeVariable("T"));
		assertEquals(withTypeVariable, withTypeVariable.substitute(Map.of()),
			"Substituting nothing should yield an equal TypedHandle");
		assertEquals(withTypeVariable, withTypeVariable.substitute(Map.of("X", integerType)),
			"Substituting a type variable with itself should yield an equal TypedHandle");
		assertEquals(withTypeVariable, withTypeVariable.substitute(Map.of("T", new TypeVariable("T"))),
			"Substituting a type variable with itself should yield an equal TypedHandle");

		assertNotEquals(withTypeVariable, withTypeVariable.substitute(Map.of("T", STRING)),
			"A substitution that changes the type should yield a different TypedHandle");

		// Should reuse MethodHandles where possible
		assertSame(withTypeVariable.handle(), withTypeVariable.substitute(Map.of("T", OBJECT)).handle(),
			"Should use the same MethodHandle when the substitution does not change the class");
	}
}
