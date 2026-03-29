package works.bosk.junit;

import java.lang.reflect.AnnotatedElement;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer.OrderAnnotation;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInfo;
import org.junit.jupiter.api.TestMethodOrder;
import works.bosk.junit.InjectFromHappyPathTests.MethodParametersTest.IndependentIntInjector;
import works.bosk.junit.InjectFromHappyPathTests.MethodParametersTest.IndependentStringInjector;

import static java.util.stream.Collectors.joining;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Tests for {@link InjectFrom} that actually use JUnit.
 * <p>
 * These can only test happy paths, since we can't include tests that fail.
 * <p>
 * We generally need to record all the combinations of injected values that we see,
 * and check them in an {@code @AfterAll} method, which is pretty unusual.
 * This means that if the test fails, then JUnit will report that it passed,
 * and then the {@code @AfterAll} method will fail, so we make some effort
 * to make it clear which test method the failure came from.
 * It also means we need to collect output in static fields,
 * since there can be multiple instances of the test class,
 * so we need to take some care to make those thread-safe;
 */
class InjectFromHappyPathTests {

	enum BaseValue { FIRST, SECOND }
	record DependentValue(BaseValue base) { @Override public String toString() { return "based-on-" + base; } }
	record Dependent2Value(DependentValue base) { @Override public String toString() { return "based-on-" + base; } }
	enum IndependentValue { X, Y }

	@Nested
	@InjectFields
	@InjectFrom({BaseInjector.class, IndependentInjector.class})
	class IndependentFieldsTest {
		static final Set<String> observations = new HashSet<>();

		@Injected BaseValue baseValue;
		@Injected IndependentValue independentValue;

		@Test
		void fieldInjection_works() {
			observations.add(baseValue + ":" + independentValue);
		}

		@AfterAll
		static void checkObservations() {
			assertEquals(Set.of(
				"FIRST:X",
				"FIRST:Y",
				"SECOND:X",
				"SECOND:Y"
			), observations);
		}
	}

	@Nested
	@InjectFields
	@InjectFrom({BaseInjector.class, DependentInjector.class})
	class DependentFieldsTest {
		static final Set<String> observations = new HashSet<>();

		@Injected BaseValue baseValue;
		@Injected DependentValue dependentValue;

		@Test
		void fieldInjection_works() {
			observations.add(baseValue + ":" + dependentValue);
		}

		@AfterAll
		static void checkObservations() {
			assertEquals(Set.of("FIRST:based-on-FIRST", "SECOND:based-on-SECOND"), observations);
		}

	}

	@InjectFields
	@InjectFrom({BaseInjector.class, DependentInjector.class})
	abstract static class DependentFieldsBase {
		@Injected BaseValue inheritedBaseValue;
		@Injected DependentValue inheritedDependentValue;
	}

	@Nested
	@InjectFields
	@InjectFrom({Dependent2Injector.class, IndependentInjector.class})
	class InheritedDependentFieldsTest extends DependentFieldsBase {
		static List<String> observations;

		@Injected BaseValue baseValue; // Same injector as inheritedBaseValue
		@Injected Dependent2Value dependent2Value;
		@Injected IndependentValue independentValue;

		@BeforeAll
		static void setup() {
			observations = new ArrayList<>();
		}

		@Test
		void fieldInjection_works() {
			observations.add(Stream.of(
				inheritedBaseValue,
				baseValue,
				inheritedDependentValue,
				dependent2Value,
				independentValue
			).map(Object::toString).collect(joining(",")));
		}

		@AfterAll
		static void checkObservations(TestInfo testInfo) {
			var expected = List.of(
				"FIRST,FIRST,based-on-FIRST,based-on-based-on-FIRST,X",
				"FIRST,FIRST,based-on-FIRST,based-on-based-on-FIRST,Y",
				"SECOND,SECOND,based-on-SECOND,based-on-based-on-SECOND,X",
				"SECOND,SECOND,based-on-SECOND,based-on-based-on-SECOND,Y"
			);
			assertEquals(expected, observations,
				"Unexpected observations in " + testInfo.getDisplayName());
		}
	}

	@Nested
	@TestMethodOrder(OrderAnnotation.class)
	@InjectFrom({Dependent2Injector.class, IndependentInjector.class, IndependentStringInjector.class, IndependentIntInjector.class})
	class MethodParametersTest extends DependentFieldsBase {
		static final Map<String, List<String>> observations = new ConcurrentHashMap<>();
		static final Map<String, List<String>> expected = new ConcurrentHashMap<>();

		@Injected IndependentValue independentValue; // Independent of superclass

		@InjectedTest
		void independentInjector_cartesianProduct(String string, int integer) {
			observations.computeIfAbsent("independentInjector_cartesianProduct", _ -> new ArrayList<>()).add(Stream.of(
				inheritedBaseValue,
				independentValue,
				string,
				integer
			).map(Object::toString).collect(joining(",")));
			expected.put("independentInjector_cartesianProduct", List.of(
				"FIRST,X,a,1",
				"FIRST,X,a,2",
				"FIRST,X,b,1",
				"FIRST,X,b,2",
				"FIRST,Y,a,1",
				"FIRST,Y,a,2",
				"FIRST,Y,b,1",
				"FIRST,Y,b,2",
				"SECOND,X,a,1",
				"SECOND,X,a,2",
				"SECOND,X,b,1",
				"SECOND,X,b,2",
				"SECOND,Y,a,1",
				"SECOND,Y,a,2",
				"SECOND,Y,b,1",
				"SECOND,Y,b,2"
			));
		}

		@InjectedTest
		void sameInjector_sameValue(IndependentValue param) {
			observations.computeIfAbsent("sameInjector_sameValue", _ -> new ArrayList<>()).add(Stream.of(
				inheritedBaseValue,
				independentValue,
				param
			).map(Object::toString).collect(joining(",")));
			expected.put("sameInjector_sameValue", List.of(
				"FIRST,X,X",
				"FIRST,Y,Y",
				"SECOND,X,X",
				"SECOND,Y,Y"
			));
		}

		@InjectedTest
		void sameInjectorAsInherited_sameValue(DependentValue param) {
			observations.computeIfAbsent("sameInjectorAsInherited_sameValue", _ -> new ArrayList<>()).add(Stream.of(
				inheritedBaseValue,
				inheritedDependentValue,
				independentValue,
				param
			).map(Object::toString).collect(joining(",")));
			expected.put("sameInjectorAsInherited_sameValue", List.of(
				"FIRST,based-on-FIRST,X,based-on-FIRST",
				"FIRST,based-on-FIRST,Y,based-on-FIRST",
				"SECOND,based-on-SECOND,X,based-on-SECOND",
				"SECOND,based-on-SECOND,Y,based-on-SECOND"
			));
		}

		@InjectedTest
		void dependentInjector_appropriateCombinations(Dependent2Value param) {
			observations.computeIfAbsent("dependentInjector_appropriateCombinations", _ -> new ArrayList<>()).add(Stream.of(
				inheritedBaseValue,
				inheritedDependentValue,
				independentValue,
				param
			).map(Object::toString).collect(joining(",")));
			expected.put("dependentInjector_appropriateCombinations", List.of(
				"FIRST,based-on-FIRST,X,based-on-based-on-FIRST",
				"FIRST,based-on-FIRST,Y,based-on-based-on-FIRST",
				"SECOND,based-on-SECOND,X,based-on-based-on-SECOND",
				"SECOND,based-on-SECOND,Y,based-on-based-on-SECOND"
			));
		}

		@AfterAll
		static void checkObservations() {
			observations.forEach((test, observations) -> {
				var expected = InjectFromHappyPathTests.MethodParametersTest.expected.get(test);
				assertEquals(expected, observations,
					"Unexpected observations for " + test);
			});
		}

		record IndependentStringInjector() implements Injector {
			@Override
			public boolean supports(AnnotatedElement element, Class<?> elementType) {
				return elementType == String.class;
			}

			@Override
			public List<String> values() {
				return List.of("a", "b");
			}
		}

		record IndependentIntInjector() implements Injector {
			@Override
			public boolean supports(AnnotatedElement element, Class<?> elementType) {
				return elementType == int.class;
			}

			@Override
			public List<Integer> values() {
				return List.of(1, 2);
			}
		}
	}

	@Nested
	@InjectFields
	@InjectFrom({IntInjector1.class, IntInjector2.class})
	class InjectorOverrideTest {
		static final List<Integer> observations = new ArrayList<>();

		@Injected int intValue;

		@InjectedTest
		void testInjectorOverride() {
			observations.add(intValue);
		}

		@AfterAll
		static void checkObservations() {
			assertEquals(List.of(
				110,
				120
			), observations);
		}
	}

	record IntInjector1() implements Injector {
		@Override
		public boolean supports(AnnotatedElement element, Class<?> elementType) {
			return elementType == int.class;
		}

		@Override
		public List<Integer> values() {
			return List.of(10, 20);
		}
	}

	record IntInjector2(int baseValue) implements Injector {
		@Override
		public boolean supports(AnnotatedElement element, Class<?> elementType) {
			return elementType == int.class;
		}

		@Override
		public List<Integer> values() {
			return List.of(baseValue + 100);
		}
	}

	record BaseInjector() implements Injector {
		@Override
		public boolean supports(AnnotatedElement element, Class<?> elementType) {
			return elementType == BaseValue.class;
		}

		@Override
		public List<BaseValue> values() {
			return List.of(BaseValue.FIRST, BaseValue.SECOND);
		}
	}

	record DependentInjector(BaseValue baseValue) implements Injector {
		@Override
		public boolean supports(AnnotatedElement element, Class<?> elementType) {
			return elementType == DependentValue.class;
		}

		@Override
		public List<DependentValue> values() {
			return List.of(new DependentValue(baseValue));
		}
	}

	record Dependent2Injector(DependentValue base) implements Injector {
		@Override
		public boolean supports(AnnotatedElement element, Class<?> elementType) {
			return elementType == Dependent2Value.class;
		}

		@Override
		public List<Dependent2Value> values() {
			return List.of(new Dependent2Value(base));
		}
	}

	record IndependentInjector() implements Injector {
		@Override
		public boolean supports(AnnotatedElement element, Class<?> elementType) {
			return elementType == IndependentValue.class;
		}

		@Override
		public List<IndependentValue> values() {
			return List.of(IndependentValue.X, IndependentValue.Y);
		}
	}
}
