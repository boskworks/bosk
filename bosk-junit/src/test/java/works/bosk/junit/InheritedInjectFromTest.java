package works.bosk.junit;

import java.lang.reflect.AnnotatedElement;
import java.lang.reflect.Field;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;

import static org.junit.jupiter.api.Assertions.assertEquals;

@InjectFrom(InheritedInjectFromTestParent.TestInjector.class)
abstract class InheritedInjectFromTestParent {
	static final Set<Observation> observations = new HashSet<>();
	static final Set<Observation> beforeEachObservations = new HashSet<>();
	record Observation(String fieldValue, int methodValue) {}

	@InjectedTest
	void testMethod(@SuppressWarnings("unused") int methodValue) {
	}

	record TestInjector() implements Injector {
		@Override
		public boolean supports(AnnotatedElement element, Class<?> elementType) {
			return elementType == int.class;
		}

		@Override
		public List<Integer> values() {
			return List.of(1, 2, 3);
		}
	}
}

@InjectFields
@InjectFrom(InheritedInjectFromTestChild.FieldInjector.class)
class InheritedInjectFromTestChild extends InheritedInjectFromTestParent {
	@Injected String fieldValue;

	@BeforeEach
	void init() {
		beforeEachObservations.add(new Observation(fieldValue, 0));
	}

	@InjectedTest
	void childTestMethod(@SuppressWarnings("unused") int methodValue) {
		observations.add(new Observation(fieldValue, methodValue));
	}

	@AfterAll
	static void checkAllObservations() {
		Set<Observation> expectedMethod = Set.of(
			new Observation("A", 1),
			new Observation("A", 2),
			new Observation("A", 3),
			new Observation("B", 1),
			new Observation("B", 2),
			new Observation("B", 3)
		);
		assertEquals(expectedMethod, observations);

		Set<Observation> expectedBeforeEach = Set.of(
			new Observation("A", 0),
			new Observation("B", 0)
		);
		assertEquals(expectedBeforeEach, beforeEachObservations);
	}

	record FieldInjector() implements Injector {
		@Override
		public boolean supportsField(Field field) {
			return field.getType() == String.class;
		}

		@Override
		public boolean supports(AnnotatedElement element, Class<?> elementType) {
			return false;
		}

		@Override
		public List<String> values() {
			return List.of("A", "B");
		}
	}
}
