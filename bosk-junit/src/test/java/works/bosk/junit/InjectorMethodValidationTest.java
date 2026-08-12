package works.bosk.junit;

import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ParameterResolutionException;
import works.bosk.junit.InjectionSupport.Branch;
import works.bosk.junit.InjectionSupport.InjectionKey;
import works.bosk.junit.InjectionSupport.Superposition;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Tests the failure cases of {@link InjectorMethod} that can't be expressed as
 * a happy-path injection scenario.
 */
class InjectorMethodValidationTest {

	@Test
	void nonStaticMethod_rejected() throws Exception {
		Method method = NonStatic.class.getDeclaredMethod("values");
		assertThrows(ParameterResolutionException.class, () -> InjectionSupport.validateInjectorMethod(method));
	}

	@Test
	void genericReturnType_rejected() throws Exception {
		Method method = GenericReturn.class.getDeclaredMethod("values");
		assertThrows(ParameterResolutionException.class, () -> InjectionSupport.validateInjectorMethod(method));
	}

	@Test
	void rawStreamReturnType_rejected() throws Exception {
		Method method = RawReturn.class.getDeclaredMethod("values");
		assertThrows(ParameterResolutionException.class, () -> InjectionSupport.validateInjectorMethod(method));
	}

	@Test
	void nonStreamReturnType_rejected() throws Exception {
		Method method = NonStreamReturn.class.getDeclaredMethod("values");
		assertThrows(ParameterResolutionException.class, () -> InjectionSupport.validateInjectorMethod(method));
	}

	@Test
	void duplicateSameClassMethods_rejected() throws Exception {
		List<Method> methods = List.of(
			Duplicate.class.getDeclaredMethod("values1"),
			Duplicate.class.getDeclaredMethod("values2"));
		assertThrows(ParameterResolutionException.class, () -> InjectionSupport.validateNoDuplicateMethods(methods));
	}

	@Test
	void sameClassMethodExcluded_fromMethodParameterResolution() throws Exception {
		Parameter parameter = Sibling.class.getDeclaredMethod("consume", String.class).getParameters()[0];
		Method excludedMethod = Excluded.class.getDeclaredMethod("values");
		Method siblingMethod = Sibling.class.getDeclaredMethod("values");

		var excludedKey = new InjectionKey(new MethodValueInjector(excludedMethod, String.class, new Object[0]), "");
		var siblingKey = new InjectionKey(new MethodValueInjector(siblingMethod, String.class, new Object[0]), "");

		// A param whose only possible source is the same-class method is unresolvable.
		var onlySameClassSource = branchWith(excludedKey);
		assertNull(onlySameClassSource.keyForParameterExcluding(parameter, Excluded.class),
			"Same-class methods must not feed each other's parameters");

		// Excluding the class of the "excluded" method leaves the sibling, and vice versa.
		var bothSources = branchWith(excludedKey, siblingKey);
		assertEquals(siblingKey, bothSources.keyForParameterExcluding(parameter, Excluded.class));
		assertEquals(excludedKey, bothSources.keyForParameterExcluding(parameter, Sibling.class));
	}

	private static Branch branchWith(InjectionKey... keys) {
		var toInject = new LinkedHashMap<InjectionKey, Superposition>();
		for (var key : keys) {
			toInject.put(key, new Superposition(List.of("value"), Set.of()));
		}
		return new Branch(toInject);
	}

	class NonStatic {
		@InjectorMethod
		Stream<String> values() {
			return Stream.of("x");
		}
	}

	class GenericReturn {
		@InjectorMethod
		<T> Stream<T> values() {
			return null;
		}
	}

	@SuppressWarnings("rawtypes")
	class RawReturn {
		@InjectorMethod
		Stream values() {
			return null;
		}
	}

	class NonStreamReturn {
		@InjectorMethod
		String values() {
			return "x";
		}
	}

	class Duplicate {
		@InjectorMethod
		static Stream<String> values1() {
			return Stream.of("a");
		}

		@InjectorMethod
		static Stream<String> values2() {
			return Stream.of("b");
		}
	}

	class Excluded {
		@InjectorMethod
		static Stream<String> values() {
			return Stream.of("excluded");
		}
	}

	class Sibling {
		@InjectorMethod
		static Stream<String> values() {
			return Stream.of("sibling");
		}

		@SuppressWarnings("unused")
		static void consume(String value) {
		}
	}

}
