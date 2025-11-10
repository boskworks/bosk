package works.bosk.boson.types;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * <em>Maintenance note</em>: we do most of our testing with interfaces.
 * We have a smattering of cases for superclasses too, to confirm they work,
 * but there's no need for exhaustive coverage there.
 */
class ParameterTypeTest {
	public static final DataType STRING = DataType.of(String.class);
	public static final DataType INTEGER = DataType.of(Integer.class);
	public static final TypeVariable VARIABLE_V = TypeVariable.unbounded("V");

	@Test
	void directSuper_interface_inheritedConcrete() {
		interface C extends List<String> {}
		InstanceType cType = instanceType(new TypeReference<C>() { });
		assertEquals(STRING, cType.parameterType(List.class, 0));
	}

	@Test
	void directSuper_interface_suppliedConcrete() {
		interface C<C0> extends List<C0> {}
		InstanceType cType = instanceType(new TypeReference<C<String>>() { });
		assertEquals(STRING, cType.parameterType(List.class, 0));
	}

	@Test
	void directSuper_interface_suppliedWildcard() {
		interface C<C0> extends List<C0> {}
		InstanceType cType = instanceType(new TypeReference<C<?>>() { });
		assertEquals(WildcardType.unbounded(), cType.parameterType(List.class, 0));
	}

	@Test
	<V> void directSuper_interface_suppliedVariable() {
		interface C<C0> extends List<C0> {}
		InstanceType cType = instanceType(new TypeReference<C<V>>() { });
		assertEquals(VARIABLE_V, cType.parameterType(List.class, 0));
	}

	@Test
	void directSuper_class_inheritedConcrete() {
		class C extends ArrayList<String> {}
		InstanceType cType = instanceType(new TypeReference<C>() { });
		assertEquals(STRING, cType.parameterType(ArrayList.class, 0));
		assertEquals(STRING, cType.parameterType(List.class, 0));
	}

	@Test
	void indirectSuper_interface_inheritedConcrete() {
		interface C extends List<String> {}
		interface D extends C {}
		InstanceType dType = instanceType(new TypeReference<D>() { });
		assertEquals(STRING, dType.parameterType(List.class, 0));
	}

	@Test
	void indirectSuper_interface_intermediateConcrete() {
		interface C<C0> extends List<C0> {}
		interface D extends C<String> {}
		InstanceType dType = instanceType(new TypeReference<D>() { });
		assertEquals(STRING, dType.parameterType(List.class, 0));
	}

	@Test
	void indirectSuper_interface_suppliedConcrete() {
		interface C<C0> extends List<C0> {}
		interface D<D0> extends C<D0> {}
		InstanceType dType = instanceType(new TypeReference<D<String>>() { });
		assertEquals(STRING, dType.parameterType(List.class, 0));
	}

	@Test
	void indirectSuper_interface_suppliedWildcard() {
		interface C<C0> extends List<C0> {}
		interface D<D0> extends C<D0> {}

		InstanceType unbounded = instanceType(new TypeReference<D<?>>() { });
		assertEquals(WildcardType.unbounded(), unbounded.parameterType(List.class, 0));

		InstanceType upperBounded = instanceType(new TypeReference<D<? extends String>>() { });
		assertEquals(WildcardType.extends_(String.class), upperBounded.parameterType(List.class, 0));

		InstanceType lowerBounded = instanceType(new TypeReference<D<? super String>>() { });
		assertEquals(WildcardType.super_(String.class), lowerBounded.parameterType(List.class, 0));
	}

	@Test
	<V> void indirectSuper_interface_suppliedVariable() {
		interface C<C0> extends List<C0> {}
		interface D<D0> extends C<D0> {}
		InstanceType dType = instanceType(new TypeReference<D<V>>() { });
		assertEquals(VARIABLE_V, dType.parameterType(List.class, 0));
	}

	@Test
	void indirectSuper_class_inheritedConcrete() {
		class C extends ArrayList<String> {}
		class D extends C {}
		InstanceType dType = instanceType(new TypeReference<D>() { });
		assertEquals(STRING, dType.parameterType(ArrayList.class, 0));
		assertEquals(STRING, dType.parameterType(List.class, 0));
	}

	@Test
	void switcheroo() {
		interface C<C0, C1> {}
		interface D<D0, D1> extends C<D1, D0> {}
		interface E<E0, E1> extends D<E1, E0> {}
		InstanceType eType = instanceType(new TypeReference<E<String, Integer>>() { });

		assertEquals(STRING, eType.parameterType(E.class, 0));
		assertEquals(INTEGER, eType.parameterType(E.class, 1));
		assertEquals(INTEGER, eType.parameterType(D.class, 0));
		assertEquals(STRING, eType.parameterType(D.class, 1));
		assertEquals(STRING, eType.parameterType(C.class, 0));
		assertEquals(INTEGER, eType.parameterType(C.class, 1));
	}

	public static InstanceType instanceType(TypeReference<?> ref) {
		return (InstanceType) DataType.of(ref);
	}

}
