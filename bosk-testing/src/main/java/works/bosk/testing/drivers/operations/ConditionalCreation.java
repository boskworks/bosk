package works.bosk.testing.drivers.operations;

import works.bosk.BoskContext;
import works.bosk.BoskDriver;
import works.bosk.Reference;

public record ConditionalCreation<T>(
	Reference<T> target,
	T newValue,
	BoskContext.Context boskContext
) implements ReplacementOperation<T> {

	@Override
	public ConditionalCreation<T> withBoskContext(BoskContext.Context newContext) {
		return new ConditionalCreation<>(target, newValue, newContext);
	}

	@Override
	public void submitTo(BoskDriver driver) {
		driver.submitConditionalCreation(target, newValue);
	}

}
