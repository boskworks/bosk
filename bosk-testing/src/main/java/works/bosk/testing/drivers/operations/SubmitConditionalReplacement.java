package works.bosk.testing.drivers.operations;

import works.bosk.BoskContext;
import works.bosk.BoskDriver;
import works.bosk.Identifier;
import works.bosk.Reference;

public record SubmitConditionalReplacement<T>(
	Reference<T> target,
	T newValue,
	Reference<Identifier> precondition,
	Identifier requiredValue,
	BoskContext.Context boskContext
) implements ReplacementOperation<T>, OperationWithPrecondition {

	@Override
	public SubmitReplacement<T> unconditional() {
		return new SubmitReplacement<>(target, newValue, boskContext);
	}

	@Override
	public SubmitConditionalReplacement<T> withBoskContext(BoskContext.Context newContext) {
		return new SubmitConditionalReplacement<>(target, newValue, precondition, requiredValue, newContext);
	}

	@Override
	public void submitTo(BoskDriver driver) {
		driver.submitConditionalReplacement(target, newValue, precondition, requiredValue);
	}

}
