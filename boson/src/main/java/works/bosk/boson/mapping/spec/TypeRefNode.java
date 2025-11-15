package works.bosk.boson.mapping.spec;

import java.lang.reflect.Type;
import java.util.Map;
import works.bosk.boson.types.DataType;

/**
 * Specifies only that a JSON value should be represented by the given {@code type},
 * where that type's own parsing and generation is specified separately.
 * <p>
 * This offers a level of indirection that enables specification of recursive types,
 * which would otherwise be impossible.
 * It also allows types with shared substructures to be specified without duplication.
 * <p>
 * The means by which the type is parsed and generated is not specified here,
 * though in reality it's generally handled by {@link works.bosk.boson.mapping.TypeMap TypeMap}.
 */
public record TypeRefNode (
	DataType type
) implements JsonValueSpec {
	public static TypeRefNode of(Type type) {
		return new TypeRefNode(DataType.known(type));
	}

	@Override
	public DataType dataType() {
		return type();
	}

	@Override
	public String briefIdentifier() {
		return "Ref_" + type().leastUpperBoundClass().getSimpleName();
	}

	@Override
	public TypeRefNode specialize(Map<String, DataType> actualArguments) {
		return new TypeRefNode(type.substitute(actualArguments));
	}

	@Override
	public String toString() {
		return "@" + type;
	}
}
