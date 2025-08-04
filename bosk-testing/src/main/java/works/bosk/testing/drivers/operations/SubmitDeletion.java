package works.bosk.testing.drivers.operations;

import java.util.Collection;
import works.bosk.BoskDriver;
import works.bosk.MapValue;
import works.bosk.Reference;

public record SubmitDeletion<T>(
	Reference<T> target,
	MapValue<String> diagnosticAttributes
) implements DeletionOperation<T> {

	@Override
	public SubmitDeletion<T> withFilteredAttributes(Collection<String> allowedNames) {
		return new SubmitDeletion<>(target, MapValue.fromFunction(allowedNames, diagnosticAttributes::get));
	}

	@Override
	public void submitTo(BoskDriver driver) {
		driver.submitDeletion(target);
	}

}
