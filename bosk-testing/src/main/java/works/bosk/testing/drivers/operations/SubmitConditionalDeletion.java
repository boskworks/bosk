package works.bosk.testing.drivers.operations;

import works.bosk.BoskContext;
import works.bosk.BoskDriver;
import works.bosk.Identifier;
import works.bosk.Reference;

public record SubmitConditionalDeletion<T>(
	Reference<T> target,
	Reference<Identifier> precondition,
	Identifier requiredValue,
	BoskContext.Context boskContext
) implements DeletionOperation<T>, OperationWithPrecondition {

	@Override
	public SubmitDeletion<T> unconditional() {
		return new SubmitDeletion<>(target, boskContext);
	}

	@Override
	public SubmitConditionalDeletion<T> withBoskContext(BoskContext.Context newContext) {
		return new SubmitConditionalDeletion<>(target, precondition, requiredValue, newContext);
	}

	@Override
	public void submitTo(BoskDriver driver) {
		driver.submitConditionalDeletion(target, precondition, requiredValue);
	}

}
