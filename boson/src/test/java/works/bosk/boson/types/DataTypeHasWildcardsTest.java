package works.bosk.boson.types;

import java.lang.reflect.Parameter;
import java.util.List;
import works.bosk.boson.codec.PrimitiveTypeInjector;
import works.bosk.junit.InjectFrom;
import works.bosk.junit.InjectedTest;
import works.bosk.junit.ParameterInjector;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * Tests properties of {@link DataType} including any child types it may have.
 */
@InjectFrom({
	PrimitiveTypeInjector.class,
	DataTypeHasWildcardsTest.InstanceTypeInjector.class,
	DataTypeHasWildcardsTest.UnknownTypeInjector.class
})
public class DataTypeHasWildcardsTest {

	@InjectedTest
	void primitiveType(PrimitiveType dataType) {
		assertFalse(dataType.hasWildcards());
	}

	@InjectedTest
	void instanceType(InstanceTypeInjector.Case _case) {
		assertEquals(_case.hasWildcards(), _case.type.hasWildcards());
	}

	@InjectedTest
	void arrayType(InstanceTypeInjector.Case _case) {
		assertEquals(_case.hasWildcards(), new ArrayType(_case.type).hasWildcards());
	}

	@InjectedTest
	void primitiveArrayType(PrimitiveType primitiveType) {
		assertFalse(new ArrayType(primitiveType).hasWildcards());
	}

	@InjectedTest
	void unknownType(UnknownTypeInjector.Case _case) {
		assertEquals(_case.hasWildcards(), _case.type.hasWildcards());
	}

	@InjectedTest
	void unknownArrayType(UnknownTypeInjector.Case _case) {
		assertEquals(_case.hasWildcards(), new UnknownArrayType(_case.type).hasWildcards());
	}

	static class InstanceTypeInjector implements ParameterInjector {
		record Case(
			InstanceType type,
			boolean hasWildcards
		) {}

		@Override
		public boolean supportsParameter(Parameter parameter) {
			return parameter.getType() == Case.class;
		}

		@Override
		public List<Case> values() {
			return getCases();
		}

		@SuppressWarnings("rawtypes")
		private static <T> List<Case> getCases() {
			return List.of(
				new Case(
					DataType.STRING,
					false
				),
				new Case(
					DataType.OBJECT,
					false
				),
				new Case(
					(InstanceType) DataType.of(new TypeReference<List<?>>() { }),
					true
				),
				new Case(
					(InstanceType) DataType.of(new TypeReference<List<String>>() { }),
					false
				),
				new Case(
					(InstanceType) DataType.of(new TypeReference<List<T>>() { }),
					false
				),
				new Case(
					(InstanceType) DataType.of(new TypeReference<List>() { }),
					true // Erased type is considered to have wildcards
				)
			);
		}
	}

	static class UnknownTypeInjector implements ParameterInjector {
		record Case(
			UnknownType type,
			boolean hasWildcards
		) {}

		@Override
		public boolean supportsParameter(Parameter parameter) {
			return parameter.getType() == Case.class;
		}

		@Override
		public List<Object> values() {
			return values2();
		}

		private List<Object> values2() {
			return List.of(
				new Case(
					TypeVariable.unbounded("T"),
					false
				),
				new Case(
					new UnboundedWildcardType(),
					true
				),
				new Case(
					new UpperBoundedWildcardType(DataType.STRING),
					true
				),
				new Case(
					new LowerBoundedWildcardType(DataType.STRING),
					true
				)
			);
		}
	}
}
