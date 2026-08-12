package works.bosk.junit;

import java.lang.reflect.AnnotatedElement;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Tests for {@link InjectorMethod}, exercising the injection machinery for real.
 * <p>
 * As with {@link InjectFromHappyPathTests}, scenarios that need to observe
 * multiple invocations collect their observations in static fields and check
 * them in an {@code @AfterAll} method, since a test class can be instantiated
 * multiple times.
 */
class InjectorMethodTests {
	enum BaseValue { FIRST, SECOND }

	@Nested
	@InjectFields
	class SingleValue {
		@Injected String value;

		@Test
		void injected() {
			assertEquals("only", value);
		}

		@InjectorMethod
		static Stream<String> values() {
			return Stream.of("only");
		}
	}

	@Nested
	class MultiValue {
		static final List<String> observations = new ArrayList<>();

		@InjectedTest
		void injected(String value) {
			observations.add(value);
		}

		@AfterAll
		static void checkObservations() {
			assertEquals(List.of("a", "b", "c"), observations);
		}

		@InjectorMethod
		static Stream<String> values() {
			return Stream.of("a", "b", "c");
		}
	}

	@Nested
	@InjectFields
	class Cartesian {
		static final Set<String> observations = new HashSet<>();

		@Injected String string;
		@Injected Integer integer;

		@Test
		void injected() {
			observations.add(string + ":" + integer);
		}

		@AfterAll
		static void checkObservations() {
			assertEquals(Set.of("a:1", "a:2", "b:1", "b:2"), observations);
		}

		@InjectorMethod
		static Stream<String> strings() {
			return Stream.of("a", "b");
		}

		@InjectorMethod
		static Stream<Integer> integers() {
			return Stream.of(1, 2);
		}
	}

	@Nested
	@InjectFrom(InjectorMethodTests.MethodWithParameters.BaseValueInjector.class)
	class MethodWithParameters {
		static final Set<String> observations = new HashSet<>();

		@InjectedTest
		void injected(String value) {
			observations.add(value);
		}

		@AfterAll
		static void checkObservations() {
			assertEquals(Set.of("value-of-FIRST", "value-of-SECOND"), observations);
		}

		@InjectorMethod
		static Stream<String> strings(BaseValue base) {
			return Stream.of("value-of-" + base);
		}

		record BaseValueInjector() implements Injector {
			@Override
			public boolean supports(AnnotatedElement element, Class<?> elementType) {
				return elementType == BaseValue.class;
			}

			@Override
			public List<BaseValue> values() {
				return List.of(BaseValue.FIRST, BaseValue.SECOND);
			}
		}
	}

	@Nested
	@InjectFields
	class ServesDimensionedSite {
		// Like any injector, an @InjectorMethod serves a site on any dimension.
		@Injected("x") String value;

		@Test
		void injected() {
			assertEquals("only", value);
		}

		@InjectorMethod
		static Stream<String> values() {
			return Stream.of("only");
		}
	}

	@Nested
	@InjectFields
	class ServesPrimitiveSite {
		@Injected boolean value;

		@Test
		void injected() {
			assertEquals(true, value);
		}

		@InjectorMethod(primitive = true)
		static Stream<Boolean> values() {
			return Stream.of(true);
		}
	}

	abstract static class InheritedSource {
		@InjectorMethod
		static Stream<String> inheritedValues() {
			return Stream.of("inherited");
		}
	}

	@Nested
	class InheritedMethod extends InheritedSource {
		@InjectedTest
		void injected(String value) {
			assertEquals("inherited", value);
		}
	}

	abstract static class OverrideSource {
		@InjectorMethod
		static Stream<String> values() {
			return Stream.of("base");
		}
	}

	@Nested
	class CrossClassOverride extends OverrideSource {
		static final List<String> observations = new ArrayList<>();

		@InjectedTest
		void injected(String value) {
			observations.add(value);
		}

		@AfterAll
		static void checkObservations() {
			assertEquals(List.of("subclass"), observations);
		}

		@InjectorMethod
		static Stream<String> values() {
			return Stream.of("subclass");
		}
	}

}
