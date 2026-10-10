package works.bosk;

import java.lang.reflect.Type;
import java.util.List;
import org.junit.jupiter.api.Test;
import works.bosk.util.Types;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ReferenceUtils_resolveTypeVariablesTest {

	@Test
	void concreteTypeIsUnchanged() {
		assertEquals(String.class,
			ReferenceUtils.resolveTypeVariables(String.class, Types.parameterizedType(Class1.class, String.class)));
	}

	@Test
	void substitutesTypeVariable() {
		Type typeVariable = Class1.class.getTypeParameters()[0];
		assertEquals(String.class,
			ReferenceUtils.resolveTypeVariables(typeVariable, Types.parameterizedType(Class1.class, String.class)));
	}

	@Test
	void substitutesInsideParameterizedType() throws NoSuchFieldException {
		Type listOfTypeVariable = Container.class.getDeclaredField("elements").getGenericType();
		assertEquals(Types.parameterizedType(List.class, String.class),
			ReferenceUtils.resolveTypeVariables(listOfTypeVariable, Types.parameterizedType(Container.class, String.class)));
	}

	private static class Class1<T> {}

	@SuppressWarnings("unused") // The field exists so its generic type can be inspected
	private static class Container<T> {
		List<T> elements;
	}
}
