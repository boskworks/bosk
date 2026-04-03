package works.bosk.testing.drivers.operations;

import works.bosk.BoskContext;
import works.bosk.BoskDriver;
import works.bosk.Reference;

public record SubmitReplacement<T>(
	Reference<T> target,
	T newValue,
	BoskContext.Context boskContext
) implements ReplacementOperation<T> {

	@Override
	public SubmitReplacement<T> withBoskContext(BoskContext.Context newContext) {
		return new SubmitReplacement<>(target, newValue, newContext);
	}

	@Override
	public void submitTo(BoskDriver driver) {
		driver.submitReplacement(target, newValue);
	}

}
