package works.bosk.boson.mapping.spec;

import works.bosk.boson.mapping.spec.handles.MemberPresenceCondition;
import works.bosk.boson.types.KnownType;

/**
 * Represents a JSON <em>object member</em> that can be omitted from the JSON representation.
 *
 * @param ifPresent
 * @param ifAbsent
 * @param presenceCondition determines whether JSON generation should include this member
 *
 */
public record MaybeAbsentSpec(
	JsonValueSpec ifPresent,
	ComputedSpec ifAbsent,
	MemberPresenceCondition presenceCondition
) implements SpecNode {
	public MaybeAbsentSpec {
		assert ifPresent.dataType().equals(ifAbsent.dataType()):
			"ifPresent type " + ifPresent.dataType() +
				" does not match ifAbsent type " + ifAbsent.dataType();;
	}

	public KnownType dataType() {
		return this.ifPresent().dataType();
	}

	@Override
	public String briefIdentifier() {
		return "Maybe_" + ifPresent.briefIdentifier();
	}
}
