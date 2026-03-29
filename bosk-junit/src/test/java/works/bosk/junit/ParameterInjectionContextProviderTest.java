package works.bosk.junit;

import java.lang.reflect.AnnotatedElement;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.TestInfo;
import org.junit.jupiter.api.TestInstance;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.TestInstance.Lifecycle.PER_CLASS;

@InjectFrom({
	ParameterInjectionContextProviderTest.StringInjector.class,
	ParameterInjectionContextProviderTest.IntInjector1.class,
	ParameterInjectionContextProviderTest.IntInjector2.class,
	ParameterInjectionContextProviderTest.BooleanInjector.class
})
@TestInstance(PER_CLASS)
class ParameterInjectionContextProviderTest {
	record Observation(String where, List<Object> parameters) {}

	final Map<String, Set<Observation>> actual = new HashMap<>();
	final Map<String, Set<Observation>> expected = new HashMap<>();

	private void addObservation(String testName, Object... args) {
		actual
			.computeIfAbsent(testName, _ -> new java.util.LinkedHashSet<>())
			.add(new Observation(testName, List.of(args)));
	}

	@AfterAll
	void checkAll() {
		// TODO: When this fails, the message is super hard to read
		assertEquals(expected, actual);
	}

	@InjectedTest
	void testEverything(String s, int n, boolean b, TestInfo info) {
		addObservation("testEverything", s, n, b);
		assertEquals("testEverything", info.getTestMethod().get().getName());
		expected.computeIfAbsent("testEverything", _ -> Set.of(
			new Observation("testEverything", List.of("foo", 101, true)),
			new Observation("testEverything", List.of("foo", 101, false)),
			new Observation("testEverything", List.of("foo", 102, true)),
			new Observation("testEverything", List.of("foo", 102, false)),
			new Observation("testEverything", List.of("foo", 103, true)),
			new Observation("testEverything", List.of("foo", 103, false)),
			new Observation("testEverything", List.of("bar", 101, true)),
			new Observation("testEverything", List.of("bar", 101, false)),
			new Observation("testEverything", List.of("bar", 102, true)),
			new Observation("testEverything", List.of("bar", 102, false)),
			new Observation("testEverything", List.of("bar", 103, true)),
			new Observation("testEverything", List.of("bar", 103, false))
		));
	}

	@InjectedTest
	void testBooleanOnly(boolean b, TestInfo info) {
		addObservation("testBooleanOnly", b);
		assertEquals("testBooleanOnly", info.getTestMethod().get().getName());
		expected.computeIfAbsent("testBooleanOnly", _ -> Set.of(
			new Observation("testBooleanOnly", List.of(true)),
			new Observation("testBooleanOnly", List.of(false))
		));
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

	record IntInjector1() implements Injector {
		@Override
		public boolean supports(AnnotatedElement element, Class<?> elementType) {
			return elementType == int.class;
		}

		@Override
		public List<Integer> values() {
			return List.of(1, 2, 3);
		}
	}

	record IntInjector2(int incomingValue) implements Injector {
		@Override
		public boolean supports(AnnotatedElement element, Class<?> elementType) {
			return elementType == int.class;
		}

		@Override
		public List<Integer> values() {
			return List.of(incomingValue + 100); // overrides IntInjector1
		}
	}

	record BooleanInjector() implements Injector {
		@Override
		public boolean supports(AnnotatedElement element, Class<?> elementType) {
			return elementType == boolean.class;
		}

		@Override
		public List<Boolean> values() {
			return List.of(true, false);
		}
	}

}

@InjectFrom({DependentMethodInjectorsTest.BaseInjector.class, DependentMethodInjectorsTest.DependentInjector.class})
class DependentMethodInjectorsTest {
	static final Set<DependentObservation> observations = new HashSet<>();
	record DependentObservation(int baseValue, String dependentValue) {}

	@InjectedTest
	void testDependentInjectors(int baseValue, String dependentValue) {
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
			return elementType == String.class;
		}

		@Override
		public List<String> values() {
			return List.of("based-on-" + baseValue);
		}
	}
}
