package works.bosk.junit;

import java.lang.reflect.AnnotatedElement;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Field injection must work when an injected class itself contains
 * {@link Nested @Nested} test classes, possibly several levels deep.
 * <p>
 * This used to break because JUnit invokes an enclosing class template's
 * injected-field post-processor with the innermost test instance, so the
 * enclosing class's fields were being written to the nested instance.
 * <p>
 * The injected classes here deliberately declare more than one kind of
 * dimension (an {@link Injector} class, an enum, an
 * {@link InjectorMethod @InjectorMethod}, a dependent injector, and named
 * dimensions) so the enclosing branch is threaded through the whole injection
 * machinery rather than just the simple case.
 * <p>
 * Observations are collected in static sets and checked once, in this
 * container's {@link AfterAll} method, because a nested class template is
 * invoked once per enclosing invocation.
 */
class NestedInjectionTests {

	static final Set<String> outerObservations = ConcurrentHashMap.newKeySet();
	static final Set<String> innerObservations = ConcurrentHashMap.newKeySet();
	static final Set<String> doublyNestedObservations = ConcurrentHashMap.newKeySet();
	static final Set<String> sharedDimensionObservations = ConcurrentHashMap.newKeySet();
	static final Set<String> dependentInnerObservations = ConcurrentHashMap.newKeySet();
	static final Set<String> namedDimensionObservations = ConcurrentHashMap.newKeySet();
	static final Set<String> injectorMethodObservations = ConcurrentHashMap.newKeySet();
	static final Set<String> enclosingSharedFieldObservations = ConcurrentHashMap.newKeySet();
	static final Set<String> nestedSharedFieldObservations = ConcurrentHashMap.newKeySet();

	@Nested
	@InjectFields
	@InjectFrom({OuterInjector.class, BaseValue.class})
	class Outer {
		@Injected OuterValue outerValue;
		@Injected BaseValue baseValue;

		@Test
		void outerSeesItsOwnDimensions() {
			outerObservations.add(outerValue + ":" + baseValue);
		}

		/**
		 * A nested class that introduces its own dimension. Its enclosing class's
		 * fields are of types the nested class does not itself use, which is
		 * exactly the case that used to throw.
		 */
		@Nested
		@InjectFields
		@InjectFrom({InnerInjector.class})
		class InnerWithOwnDimension {
			@Injected InnerValue innerValue;

			@Test
			void nestedSeesOwnAndEnclosingDimensions() {
				innerObservations.add(Outer.this.outerValue + ":" + Outer.this.baseValue + ":" + innerValue);
			}

			/**
			 * Two levels below the injected enclosing class.
			 */
			@Nested
			@InjectFields
			@InjectFrom({DeepInjector.class})
			class DoublyNested {
				@Injected DeepValue deepValue;

				@Test
				void doublyNestedSeesEveryLevel() {
					doublyNestedObservations.add(Outer.this.outerValue + ":" + Outer.this.baseValue + ":" + innerValue + ":" + deepValue);
				}
			}
		}

		/**
		 * A nested class that declares the same injector as its enclosing class.
		 * Both fields are on the same dimension, so the nested field receives the
		 * enclosing value instead of expanding a second, independent one.
		 */
		@Nested
		@InjectFields
		@InjectFrom({OuterInjector.class})
		class InnerSharingDimension {
			@Injected OuterValue nestedOuterValue;

			@Test
			void nestedSharesEnclosingDimension() {
				sharedDimensionObservations.add(Outer.this.outerValue + ":" + Outer.this.baseValue + ":" + nestedOuterValue);
			}
		}

		/**
		 * A nested class whose injector's constructor takes a value from the
		 * enclosing class's dimension, so the nested injector depends on the
		 * enclosing branch.
		 */
		@Nested
		@InjectFields
		@InjectFrom({DerivedInnerInjector.class})
		class InnerDependentOnEnclosing {
			@Injected DerivedInnerValue derivedInnerValue;

			@Test
			void nestedInjectorCanDependOnEnclosingDimension() {
				dependentInnerObservations.add(Outer.this.outerValue + ":" + derivedInnerValue);
			}
		}

		/**
		 * Named dimensions inside a nested class: two fields of the same type but
		 * different dimension names are expanded independently.
		 */
		@Nested
		@InjectFields
		@InjectFrom({BaseValue.class})
		class InnerWithNamedDimensions {
			@Injected("left") BaseValue left;
			@Injected("right") BaseValue right;

			@Test
			void nestedNamedDimensionsAreIndependent() {
				namedDimensionObservations.add(left + ":" + right);
			}
		}

		/**
		 * A nested class using an {@link InjectorMethod @InjectorMethod}.
		 */
		@Nested
		@InjectFields
		class InnerWithInjectorMethod {
			@Injected MethodValue methodValue;

			@InjectorMethod
			static Stream<MethodValue> methodValues() {
				return Stream.of(MethodValue.M1, MethodValue.M2);
			}

			@Test
			void nestedInjectorMethodWorks() {
				injectorMethodObservations.add(Outer.this.outerValue + ":" + methodValue);
			}
		}
	}

	/**
	 * The enclosing and nested classes both inherit an {@code @Injected} field
	 * from a shared base, but resolve it from different injectors. Each instance
	 * must keep its own value: writing the enclosing value to the nested instance
	 * would clobber (or, if the field were not inherited, throw).
	 */
	abstract static class SharedFieldBase {
		@Injected SharedFieldValue sharedFieldValue;
	}

	@Nested
	@InjectFields
	@InjectFrom({EnclosingSharedInjector.class})
	class EnclosingSharedField extends SharedFieldBase {
		@Test
		void enclosingUsesItsOwnInjector() {
			enclosingSharedFieldObservations.add(sharedFieldValue.toString());
		}

		@Nested
		@InjectFields
		@InjectFrom({NestedSharedInjector.class})
		class NestedSharedField extends SharedFieldBase {
			@Test
			void nestedKeepsItsOwnValue() {
				nestedSharedFieldObservations.add(EnclosingSharedField.this.sharedFieldValue + ":" + sharedFieldValue);
			}
		}
	}

	@AfterAll
	static void checkAllObservations() {
		assertEquals(Set.of(
			"O1:B1", "O1:B2",
			"O2:B1", "O2:B2"
		), outerObservations, "Outer should see its own dimensions");

		assertEquals(Set.of(
			"O1:B1:I1", "O1:B1:I2", "O1:B2:I1", "O1:B2:I2",
			"O2:B1:I1", "O2:B1:I2", "O2:B2:I1", "O2:B2:I2"
		), innerObservations, "Nested class should see its own and the enclosing dimensions");

		assertEquals(Set.of(
			"O1:B1:I1:D1", "O1:B1:I1:D2", "O1:B1:I2:D1", "O1:B1:I2:D2",
			"O1:B2:I1:D1", "O1:B2:I1:D2", "O1:B2:I2:D1", "O1:B2:I2:D2",
			"O2:B1:I1:D1", "O2:B1:I1:D2", "O2:B1:I2:D1", "O2:B1:I2:D2",
			"O2:B2:I1:D1", "O2:B2:I1:D2", "O2:B2:I2:D1", "O2:B2:I2:D2"
		), doublyNestedObservations, "Doubly nested class should see every level");

		assertEquals(Set.of(
			"O1:B1:O1", "O1:B2:O1",
			"O2:B1:O2", "O2:B2:O2"
		), sharedDimensionObservations, "A shared dimension should have the same value at both levels");

		assertEquals(Set.of(
			"O1:derived-from-O1",
			"O2:derived-from-O2"
		), dependentInnerObservations, "A nested injector should be able to depend on the enclosing dimension");

		assertEquals(Set.of(
			"B1:B1", "B1:B2",
			"B2:B1", "B2:B2"
		), namedDimensionObservations, "Named dimensions should be independent");

		assertEquals(Set.of(
			"O1:M1", "O1:M2",
			"O2:M1", "O2:M2"
		), injectorMethodObservations, "A nested @InjectorMethod should work");

		assertEquals(Set.of("E1", "E2"), enclosingSharedFieldObservations,
			"The enclosing class should use its own injector");

		assertEquals(Set.of(
			"E1:N1", "E1:N2",
			"E2:N1", "E2:N2"
		), nestedSharedFieldObservations,
			"A nested class sharing an inherited field should keep its own value, not the enclosing class's");
	}

	enum OuterValue { O1, O2 }
	enum InnerValue { I1, I2 }
	enum DeepValue { D1, D2 }
	enum MethodValue { M1, M2 }
	enum BaseValue { B1, B2 }

	record DerivedInnerValue(OuterValue base) {
		@Override
		public String toString() {
			return "derived-from-" + base;
		}
	}

	record SharedFieldValue(String name) {
		@Override
		public String toString() {
			return name;
		}
	}

	record OuterInjector() implements Injector {
		@Override
		public boolean supports(AnnotatedElement element, Class<?> elementType) {
			return elementType == OuterValue.class;
		}

		@Override
		public List<OuterValue> values() {
			return List.of(OuterValue.O1, OuterValue.O2);
		}
	}

	record InnerInjector() implements Injector {
		@Override
		public boolean supports(AnnotatedElement element, Class<?> elementType) {
			return elementType == InnerValue.class;
		}

		@Override
		public List<InnerValue> values() {
			return List.of(InnerValue.I1, InnerValue.I2);
		}
	}

	record DeepInjector() implements Injector {
		@Override
		public boolean supports(AnnotatedElement element, Class<?> elementType) {
			return elementType == DeepValue.class;
		}

		@Override
		public List<DeepValue> values() {
			return List.of(DeepValue.D1, DeepValue.D2);
		}
	}

	record DerivedInnerInjector(OuterValue outerValue) implements Injector {
		@Override
		public boolean supports(AnnotatedElement element, Class<?> elementType) {
			return elementType == DerivedInnerValue.class;
		}

		@Override
		public List<DerivedInnerValue> values() {
			return List.of(new DerivedInnerValue(outerValue));
		}
	}

	record EnclosingSharedInjector() implements Injector {
		@Override
		public boolean supports(AnnotatedElement element, Class<?> elementType) {
			return elementType == SharedFieldValue.class;
		}

		@Override
		public List<SharedFieldValue> values() {
			return List.of(new SharedFieldValue("E1"), new SharedFieldValue("E2"));
		}
	}

	record NestedSharedInjector() implements Injector {
		@Override
		public boolean supports(AnnotatedElement element, Class<?> elementType) {
			return elementType == SharedFieldValue.class;
		}

		@Override
		public List<SharedFieldValue> values() {
			return List.of(new SharedFieldValue("N1"), new SharedFieldValue("N2"));
		}
	}

}
