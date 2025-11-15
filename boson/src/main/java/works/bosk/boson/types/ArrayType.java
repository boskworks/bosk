package works.bosk.boson.types;

import java.lang.reflect.Array;
import java.util.Map;

public record ArrayType(KnownType elementType) implements KnownType {
	@Override
	public Class<?> rawClass() {
		return elementType.rawClass().arrayType();
	}

	@Override
	public boolean isBindableFrom(DataType other, BindableOptions options, Map<String, DataType> bindingsSoFar) {
		return arrayIsBindableFrom(this, elementType, other, options, bindingsSoFar);
	}

	static boolean arrayIsBindableFrom(DataType arrayType, DataType elementType, DataType other, BindableOptions options, Map<String, DataType> bindingsSoFar) {
		return switch (other) {
			case ArrayType(var otherElementType) -> elementType.isBindableFrom(otherElementType, options, bindingsSoFar);
			case UnknownArrayType(var otherElementType) -> elementType.isBindableFrom(otherElementType, options, bindingsSoFar);
			case KnownType _ -> false;
			case TypeVariable typeVariable -> options.allowSubtypes()
				&& typeVariable.bounds().stream().allMatch(bound ->
				arrayType.isBindableFrom(DataType.of(bound), options, bindingsSoFar));
			case WildcardType w -> options.allowSubtypes()
				&& arrayType.isBindableFrom(w.capture(), options, bindingsSoFar);
			case NullType _ -> options.allowSubtypes();
			case CapturedType capturedType -> options.allowSubtypes()
				&& arrayType.isBindableFrom(capturedType.upperBound(), options, bindingsSoFar);
		};
	}

	@Override
	public ArrayType substitute(Map<String, DataType> actualArguments) {
		return new ArrayType(elementType.substitute(actualArguments));
	}

	@Override
	public boolean hasWildcards() {
		return elementType.hasWildcards();
	}

	@Override
	public Map<String, DataType> bindingsFor(DataType other) {
		assert this.isAssignableFrom(other);
		return switch (other) {
			case ArrayType(var e) -> elementType.bindingsFor(e);
			default -> throw new IllegalArgumentException("wat");
		};
	}

	public Object[] zeroLengthInstance() {
		return (Object[]) Array.newInstance(elementType.rawClass(), 0);
	}

	@Override
	public String toString() {
		return elementType + "[]";
	}
}
