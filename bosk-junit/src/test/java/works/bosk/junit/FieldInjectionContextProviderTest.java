package works.bosk.junit;

import java.lang.reflect.AnnotatedElement;
import java.lang.reflect.Field;
import java.lang.reflect.Parameter;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import works.bosk.junit.ClassAndMethodInjectionTest.FieldOnlyInjector;
import works.bosk.junit.ClassAndMethodInjectionTest.ParameterOnlyInjector;

import static org.junit.jupiter.api.Assertions.assertEquals;

@InjectFields
@InjectFrom(FieldInjectionContextProviderTest.StringInjector.class)
class FieldInjectionContextProviderTest {
	static final Set<Observation> observations = new HashSet<>();
	record Observation(String stringValue) {}

	@Injected String stringValue;

	@InjectedTest
	void testSingleInjector() {
		observations.add(new Observation(stringValue));
	}

	@AfterAll
	static void checkObservations() {
		Set<Observation> expected = Set.of(
			new Observation("foo"),
			new Observation("bar")
		);
		assertEquals(expected, observations);
	}

	record StringInjector() implements Injector {
		@Override
		public boolean supports(AnnotatedElement element, Class<?> elementType) {
			return elementType == String.class;
		}

		@Override
		public List<String> values() {
			return List.of("foo", "bar");
		}
	}
}

@InjectFields
@InjectFrom({FieldOnlyInjector.class, ParameterOnlyInjector.class})
class ClassAndMethodInjectionTest {
	static final Set<CombinedObservation> observations = new HashSet<>();
	record CombinedObservation(String classValue, int methodValue) {}

	@Injected String classValue;

	@InjectedTest
	void testComposition(@SuppressWarnings("unused") int methodValue) {
		observations.add(new CombinedObservation(classValue, methodValue));
	}

	@AfterAll
	static void checkObservations() {
		Set<CombinedObservation> expected = Set.of(
			new CombinedObservation("A", 1),
			new CombinedObservation("A", 2),
			new CombinedObservation("B", 1),
			new CombinedObservation("B", 2)
		);
		assertEquals(expected, observations);
	}

	record FieldOnlyInjector() implements Injector {
		@Override
		public boolean supports(AnnotatedElement element, Class<?> elementType) {
			return false;
		}

		@Override
		public boolean supportsField(Field field) {
			return field.getType() == String.class;
		}

		@Override
		public List<String> values() {
			return List.of("A", "B");
		}
	}

	record ParameterOnlyInjector() implements Injector {
		@Override
		public boolean supports(AnnotatedElement element, Class<?> elementType) {
			return false;
		}

		@Override
		public boolean supportsParameter(Parameter parameter) {
			return parameter.getType() == int.class;
		}

		@Override
		public List<Integer> values() {
			return List.of(1, 2);
		}
	}
}

@InjectFields
@InjectFrom({MethodOnlyInjectorDoesNotAffectClassBranchesTest.FieldInjector.class, MethodOnlyInjectorDoesNotAffectClassBranchesTest.MethodOnlyInjector.class})
class MethodOnlyInjectorDoesNotAffectClassBranchesTest {
	static final Set<String> observations = new HashSet<>();

	@Injected String fieldValue;

	@Test
	void test() {
		observations.add(fieldValue);
	}

	@AfterAll
	static void checkObservations() {
		assertEquals(Set.of("X", "Y"), observations);
	}

	record FieldInjector() implements Injector {
		@Override
		public boolean supports(AnnotatedElement element, Class<?> elementType) {
			return false;
		}

		@Override
		public boolean supportsField(Field field) {
			return field.getType() == String.class;
		}

		@Override
		public List<String> values() {
			return List.of("X", "Y");
		}
	}

	record MethodOnlyInjector() implements Injector {
		@Override
		public boolean supports(AnnotatedElement element, Class<?> elementType) {
			return false;
		}

		@Override
		public boolean supportsParameter(Parameter parameter) {
			return parameter.getType() == int.class;
		}

		@Override
		public List<String> values() {
			return List.of("unused1", "unused2");
		}
	}
}

@InjectFields
@InjectFrom({DependentFieldInjectorsTest.BaseInjector.class, DependentFieldInjectorsTest.DependentInjector.class})
class DependentFieldInjectorsTest {
	static final Set<DependentObservation> observations = new HashSet<>();
	record DependentObservation(int baseValue, String dependentValue) {}

	@Injected int baseValue;
	@Injected String dependentValue;

	@InjectedTest
	void testDependentInjectors() {
		observations.add(new DependentObservation(baseValue, dependentValue));
	}

	@AfterAll
	static void checkObservations() {
		Set<DependentObservation> expected = Set.of(
			new DependentObservation(10, "based-on-10"),
			new DependentObservation(20, "based-on-20")
		);
		assertEquals(expected, observations);
	}

	record BaseInjector() implements Injector {
		@Override
		public boolean supports(AnnotatedElement element, Class<?> elementType) {
			return elementType == int.class;
		}

		@Override
		public List<Integer> values() {
			return List.of(10, 20);
		}
	}

	record DependentInjector(int baseValue) implements Injector {
		@Override
		public boolean supports(AnnotatedElement element, Class<?> elementType) {
			return false;
		}

		@Override
		public boolean supportsField(Field field) {
			return field.getType() == String.class;
		}

		@Override
		public List<String> values() {
			return List.of("based-on-" + baseValue);
		}
	}
}

@InjectFields
@InjectFrom({DependentFieldInjectorsWithMultipleValuesTest.BaseInjector.class, DependentFieldInjectorsWithMultipleValuesTest.DependentInjector.class})
class DependentFieldInjectorsWithMultipleValuesTest {
	static final Set<MultiValueObservation> observations = new HashSet<>();
	record MultiValueObservation(int baseValue, String dependentValue) {}

	@Injected int baseValue;
	@Injected String dependentValue;

	@InjectedTest
	void testDependentInjectorsWithMultipleValues() {
		observations.add(new MultiValueObservation(baseValue, dependentValue));
	}

	@AfterAll
	static void checkObservations() {
		// Should get cartesian product: 2 base values × 2 dependent values per base = 4 total
		Set<MultiValueObservation> expected = Set.of(
			new MultiValueObservation(10, "based-on-10-a"),
			new MultiValueObservation(10, "based-on-10-b"),
			new MultiValueObservation(20, "based-on-20-a"),
			new MultiValueObservation(20, "based-on-20-b")
		);
		assertEquals(expected, observations);
	}

	record BaseInjector() implements Injector {
		@Override
		public boolean supports(AnnotatedElement element, Class<?> elementType) {
			return elementType == int.class;
		}

		@Override
		public List<Integer> values() {
			return List.of(10, 20);
		}
	}

	record DependentInjector(int baseValue) implements Injector {
		@Override
		public boolean supports(AnnotatedElement element, Class<?> elementType) {
			return false;
		}

		@Override
		public boolean supportsField(Field field) {
			return field.getType() == String.class;
		}

		@Override
		public List<String> values() {
			return List.of("based-on-" + baseValue + "-a", "based-on-" + baseValue + "-b");
		}
	}
}

@InjectFields
@InjectFrom({FieldAndParameterTest.BaseInjector.class, FieldAndParameterTest.DependentInjector.class})
class FieldAndParameterTest {
	static final Set<FieldAndParameterObservation> observations = new HashSet<>();
	record FieldAndParameterObservation(int fieldValue, String paramValue) {}

	@Injected int fieldValue;

	@InjectedTest
	void testFieldAndParameter(String paramValue) {
		observations.add(new FieldAndParameterObservation(fieldValue, paramValue));
	}

	@AfterAll
	static void checkObservations() {
		Set<FieldAndParameterObservation> expected = Set.of(
			new FieldAndParameterObservation(10, "based-on-10"),
			new FieldAndParameterObservation(20, "based-on-20")
		);
		assertEquals(expected, observations);
	}

	record BaseInjector() implements Injector {
		@Override
		public boolean supports(AnnotatedElement element, Class<?> elementType) {
			return elementType == int.class;
		}

		@Override
		public boolean supportsField(Field field) {
			return field.getType() == int.class;
		}

		@Override
		public List<Integer> values() {
			return List.of(10, 20);
		}
	}

	record DependentInjector(int baseValue) implements Injector {
		@Override
		public boolean supports(AnnotatedElement element, Class<?> elementType) {
			return elementType == String.class;
		}

		@Override
		public boolean supportsField(Field field) {
			return false;
		}

		@Override
		public List<String> values() {
			return List.of("based-on-" + baseValue);
		}
	}
}
