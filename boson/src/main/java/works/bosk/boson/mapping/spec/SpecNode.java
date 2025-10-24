package works.bosk.boson.mapping.spec;

import java.util.function.UnaryOperator;
import works.bosk.boson.mapping.TypeMap;
import works.bosk.boson.types.KnownType;

import static java.util.Objects.requireNonNull;

/**
 * A node in the specification tree, describing how some portion of an in-memory data structure
 * corresponds to the serialized JSON text.
 * <p>
 * The specification is bidirectional, in the sense that it describes both serialization
 * and deserialization.
 * <p>
 * (A minor note: you'll notice a node has a known {@link #dataType},
 * but not a known JSON {@link works.bosk.boson.mapping.Token Token} type.
 * The asymmetry is due to {@link TypeRefNode}, for which the datatype
 * is known, but the JSON type is not known without a {@link TypeMap}.)
 */
public sealed interface SpecNode permits JsonValueSpec, ComputedSpec, MaybeAbsentSpec {
	/**
	 * @return the type of in-memory representation for this node
	 */
	KnownType dataType();

	/**
	 * @return a short string suitable for including in a method name.
	 * There are no uniqueness requirements; it's only a troubleshooting hint.
	 */
	String briefIdentifier();

	/**
	 * Helper to produce a modified node of the same type as a given
	 * node but with different field values.
	 * <p>
	 * Note: this method is static to enable generics to express the fact
	 * that the returned value has the same type as the original.
	 *
	 * @return <code>transformation.apply(original)</code>,
	 * unless the result is equal to {@code original},
	 * in which case {@code original} is returned.
	 */
	static <N extends SpecNode> N transform(N original, UnaryOperator<N> transformation) {
		N candidate = transformation.apply(requireNonNull(original));
		if (original.equals(candidate)) {
			return original;
		} else {
			return candidate;
		}
	}

}
