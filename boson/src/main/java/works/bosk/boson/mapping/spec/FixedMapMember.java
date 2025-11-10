package works.bosk.boson.mapping.spec;

import java.lang.invoke.MethodHandles;
import java.lang.reflect.RecordComponent;
import java.util.Map;
import works.bosk.boson.mapping.spec.handles.TypedHandle;
import works.bosk.boson.mapping.spec.handles.TypedHandles;
import works.bosk.boson.types.DataType;

/**
 * Specifies a member of a {@link FixedMapNode}.
 * @param valueSpec specifies the value of the member
 * @param accessor given an instance of the object, returns the value of the member
 */
public record FixedMapMember(
	SpecNode valueSpec,
	TypedHandle accessor
) {
	public FixedMapMember {
		assert valueSpec.dataType().isAssignableFrom(accessor.returnType()):
			"emitter must supply values of type " + valueSpec.dataType() + ", not " + accessor.returnType();
	}

	public static FixedMapMember forComponent(RecordComponent rc, MethodHandles.Lookup lookup) {
		return new FixedMapMember(
			new TypeRefNode(DataType.known(rc.getType())),
			TypedHandles.componentAccessor(rc, lookup)
		);
	}

	public DataType dataType() {
		return valueSpec.dataType();
	}

	public FixedMapMember substitute(Map<String, DataType> actualArguments) {
		SpecNode valueSpec = (this.valueSpec instanceof JsonValueSpec j)? j.substitute(actualArguments) : this.valueSpec;
		return new FixedMapMember(
			valueSpec,
			this.accessor.substitute(actualArguments)
		);
	}
}
