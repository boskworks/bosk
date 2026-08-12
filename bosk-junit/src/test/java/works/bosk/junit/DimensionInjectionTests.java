package works.bosk.junit;

import java.lang.reflect.AnnotatedElement;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import works.bosk.junit.InjectFromHappyPathTests.BaseValue;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Tests the use of {@link Injected @Injected} dimensions: sites with the same
 * dimension share a value stream, and sites with different dimensions receive
 * the Cartesian product of value combinations.
 */
class DimensionInjectionTests {

	// Simple injector producing two string values
	record StringInjector() implements Injector {
		@Override
		public boolean supports(AnnotatedElement element, Class<?> elementType) {
			return elementType == String.class;
		}

		@Override
		public List<String> values() {
			return List.of("A", "B");
		}
	}

	@Nested
	@InjectFrom({StringInjector.class})
	class ParameterParameterTest {
		static Set<String> differentDimensionObservations = new HashSet<>();
		static Set<String> sameDimensionObservations = new HashSet<>();

		@InjectedTest
		void sameDimension(@Injected("x") String a, @Injected("x") String b) {
			sameDimensionObservations.add(a + ":" + b);
		}

		@InjectedTest
		void differentDimension(@Injected("x") String a, @Injected("y") String b) {
			differentDimensionObservations.add(a + ":" + b);
		}

		@AfterAll
		static void check() {
			assertEquals(Set.of("A:A", "B:B"), sameDimensionObservations);
			assertEquals(Set.of("A:A", "A:B", "B:A", "B:B"), differentDimensionObservations);
		}
	}

	@Nested
	@InjectFrom({BaseValue.class})
	class EnumDimensionParameterTest {
		static Set<String> differentDimensionObservations = new HashSet<>();
		static Set<String> sameDimensionObservations = new HashSet<>();

		@InjectedTest
		void sameDimension(@Injected("x") BaseValue a, @Injected("x") BaseValue b) {
			sameDimensionObservations.add(a + ":" + b);
		}

		@InjectedTest
		void differentDimension(@Injected("x") BaseValue a, @Injected("y") BaseValue b) {
			differentDimensionObservations.add(a + ":" + b);
		}

		@AfterAll
		static void check() {
			assertEquals(Set.of("FIRST:FIRST", "SECOND:SECOND"), sameDimensionObservations);
			assertEquals(Set.of("FIRST:FIRST", "FIRST:SECOND", "SECOND:FIRST", "SECOND:SECOND"), differentDimensionObservations);
		}
	}

	@Nested
	@InjectFields
	@InjectFrom({StringInjector.class})
	class FieldFieldTest {
		static Set<String> observations = new HashSet<>();

		@Injected("x") String f0;
		@Injected("y") String f1;
		@Injected("y") String f2;

		@Test
		void test() {
			observations.add(f0 + ":" + f1 + ":" + f2);
		}

		@AfterAll
		static void checkDefaults() {
			// f0 independent, f1 and f2 linked -> cartesian product where second and third are equal
			assertEquals(Set.of("A:A:A", "A:B:B", "B:A:A", "B:B:B"), observations);
		}

	}

	@Nested
	@InjectFields
	@InjectFrom({StringInjector.class})
	class FieldParameterTest {
		static Set<String> differentDimensionObservations = new HashSet<>();
		static Set<String> sameDimensionObservations = new HashSet<>();

		@Injected("x") String fieldX;

		@InjectedTest
		void sameDimension(@Injected("x") String paramX) {
			sameDimensionObservations.add(fieldX + ":" + paramX);
		}

		@InjectedTest
		void differentDimension(@Injected("y") String paramY) {
			differentDimensionObservations.add(fieldX + ":" + paramY);
		}

		@AfterAll
		static void check() {
			assertEquals(Set.of("A:A", "B:B"), sameDimensionObservations);
			assertEquals(Set.of("A:A", "A:B", "B:A", "B:B"), differentDimensionObservations);
		}
	}
}
