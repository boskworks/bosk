package works.bosk.boson.types;

/**
 * A {@link DataType} with at least one wildcard or type variable.
 * Also includes {@link ErasedType}.
 */
sealed public interface UnknownType extends DataType permits
	TypeVariable,
	UnknownArrayType,
	WildcardType
{ }
