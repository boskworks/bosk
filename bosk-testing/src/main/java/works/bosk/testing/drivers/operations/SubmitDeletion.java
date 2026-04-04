package works.bosk.testing.drivers.operations;

import works.bosk.BoskContext;
import works.bosk.BoskDriver;
import works.bosk.Reference;

public record SubmitDeletion<T>(
	Reference<T> target,
	BoskContext.Context boskContext
) implements DeletionOperation<T> {

	@Override
	public SubmitDeletion<T> withBoskContext(BoskContext.Context newContext) {
		return new SubmitDeletion<>(target, newContext);
	}

	@Override
	public void submitTo(BoskDriver driver) {
		driver.submitDeletion(target);
	}

}
