package works.bosk.boson.mapping.spec.handles;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.reflect.RecordComponent;
import java.util.List;
import java.util.stream.Stream;
import works.bosk.boson.types.DataType;
import works.bosk.boson.types.KnownType;

import static works.bosk.boson.types.DataType.BOOLEAN;
import static works.bosk.boson.types.DataType.OBJECT;

public final class TypedHandles {
	private TypedHandles(){}

	public static TypedHandle canonicalConstructor(Class<? extends Record> recordType, MethodHandles.Lookup lookup) {
		MethodHandle mh;
		try {
			mh = lookup.findConstructor(recordType, MethodType.methodType(void.class, Stream.of(recordType.getRecordComponents())
				.map(RecordComponent::getType).toArray(Class<?>[]::new)
			));
		} catch (NoSuchMethodException e) {
			throw new AssertionError("Canonical constructor must exist for " + recordType);
		} catch (IllegalAccessException e) {
			throw new IllegalArgumentException("Can't access canonical constructor of " + recordType);
		}
		KnownType recordDataType = DataType.known(recordType); // Records can have unknown types! But we don't support that here.
		return new TypedHandle(
			mh,
			recordDataType,
			Stream.of(recordType.getRecordComponents())
				.map(rc -> {
					var dt = DataType.of(rc.getType());
					if (dt instanceof KnownType kt) {
						return kt;
					} else {
						throw new IllegalArgumentException("Record component " + rc + " has unknown type " + dt);
					}
				})
				.toList()
		);
	}

	public static TypedHandle recordComponent(RecordComponent rc, MethodHandles.Lookup lookup) {
		MethodHandle mh;
		try {
			mh = lookup.unreflect(rc.getAccessor());
		} catch (IllegalAccessException e) {
			throw new IllegalArgumentException("Can't access accessor " + rc.getAccessor() + " of " + rc.getDeclaringRecord());
		}
		return new TypedHandle(mh, DataType.known(rc.getType()), List.of(DataType.known(rc.getDeclaringRecord())));
	}

	public static TypedHandle constant(KnownType type, Object value) {
		return new TypedHandle(MethodHandles.constant(type.rawClass(), value), type, List.of());
	}

	public static TypedHandle notEquals() {
		return new TypedHandle(NOT_EQUALS_HANDLE, BOOLEAN, List.of(OBJECT, OBJECT));
	}

	public static TypedHandle notEquals(TypedHandle argument) {
		return notEquals().bind(0, argument);
	}

	private static boolean notEquals(Object a, Object b) {
		return !a.equals(b);
	}

	private static final MethodHandle NOT_EQUALS_HANDLE;

	static {
		try {
			NOT_EQUALS_HANDLE = MethodHandles.lookup().findStatic(
				TypedHandles.class,
				"notEquals",
				MethodType.methodType(boolean.class, Object.class, Object.class)
			);
		} catch (NoSuchMethodException | IllegalAccessException e) {
			throw new ExceptionInInitializerError(e);
		}
	}


}
