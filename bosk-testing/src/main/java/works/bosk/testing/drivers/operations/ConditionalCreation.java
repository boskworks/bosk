package works.bosk.testing.drivers.operations;

import java.util.Collection;
import works.bosk.BoskDriver;
import works.bosk.MapValue;
import works.bosk.Reference;

public record ConditionalCreation<T>(
	Reference<T> target,
	T newValue,
	MapValue<String> diagnosticAttributes
) implements ReplacementOperation<T> {

	@Override
	public ConditionalCreation<T> withFilteredAttributes(Collection<String> allowedNames) {
		return new ConditionalCreation<>(target, newValue, MapValue.fromFunction(allowedNames, diagnosticAttributes::get));
	}

	@Override
	public void submitTo(BoskDriver driver) {
		driver.submitConditionalCreation(target, newValue);
	}

}
