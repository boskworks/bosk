package works.bosk.boson.mapping.spec;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.reflect.RecordComponent;
import java.util.List;
import works.bosk.boson.mapping.spec.handles.TypedHandle;
import works.bosk.boson.types.DataType;
import works.bosk.boson.types.KnownType;

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
		MethodHandle handle;
		try {
			handle = lookup.unreflect(rc.getAccessor());
		} catch (IllegalAccessException e) {
			throw new IllegalStateException(e);
		}
		KnownType componentType = DataType.known(rc.getType());
		KnownType recordType = DataType.known(rc.getDeclaringRecord());
		return new FixedMapMember(
			new TypeRefNode(componentType),
			new TypedHandle(handle, componentType, List.of(recordType))
		);
	}

	public KnownType dataType() {
		return valueSpec.dataType();
	}
}
