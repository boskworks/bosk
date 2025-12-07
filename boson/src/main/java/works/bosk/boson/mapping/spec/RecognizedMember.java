package works.bosk.boson.mapping.spec;

import java.lang.invoke.MethodHandles;
import java.lang.reflect.RecordComponent;
import java.util.Map;
import works.bosk.boson.mapping.spec.handles.TypedHandle;
import works.bosk.boson.mapping.spec.handles.TypedHandles;
import works.bosk.boson.types.DataType;

/**
 * Specifies a member that is expected to be present in an object,
 * identified by some means (for example, by its name).
 * @param valueSpec specifies the value of the member;
 *                  not necessarily a {@link JsonValueSpec} because some things that
 *                  are conceptually members do not actually appear in the JSON.
 * @param accessor given an instance of the object, returns the value of the member
 */
public record RecognizedMember(
	SpecNode valueSpec,
	TypedHandle accessor
) {
	public RecognizedMember {
		assert valueSpec.dataType().isAssignableFrom(accessor.returnType()):
			"emitter must supply values of type " + valueSpec.dataType() + ", not " + accessor.returnType();
	}

	public static RecognizedMember forComponent(RecordComponent rc, MethodHandles.Lookup lookup) {
		return new RecognizedMember(
			new TypeRefNode(DataType.known(rc.getType())),
			TypedHandles.componentAccessor(rc, lookup)
		);
	}

	public DataType dataType() {
		return valueSpec.dataType();
	}

	public RecognizedMember substitute(Map<String, DataType> actualArguments) {
		SpecNode valueSpec = (this.valueSpec instanceof JsonValueSpec j)? j.specialize(actualArguments) : this.valueSpec;
		return new RecognizedMember(
			valueSpec,
			this.accessor.substitute(actualArguments)
		);
	}
}
