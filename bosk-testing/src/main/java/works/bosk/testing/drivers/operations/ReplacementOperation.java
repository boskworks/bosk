package works.bosk.testing.drivers.operations;

public sealed interface ReplacementOperation<T> extends UpdateOperation permits
	SubmitConditionalReplacement,
	ConditionalCreation,
	SubmitReplacement
{
	T newValue();

	@Override
	default boolean matchesIfApplied(UpdateOperation other) {
		if (other instanceof ReplacementOperation<?> r) {
			return this.target().equals(other.target())
				&& this.newValue().equals(r.newValue());
		} else {
			return false;
		}
	}
}
