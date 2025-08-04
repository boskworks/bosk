package works.bosk.testing.drivers.operations;

import java.util.Collection;
import works.bosk.BoskDriver;
import works.bosk.Identifier;
import works.bosk.MapValue;
import works.bosk.Reference;

public record SubmitConditionalReplacement<T>(
	Reference<T> target,
	T newValue,
	Reference<Identifier> precondition,
	Identifier requiredValue,
	MapValue<String> diagnosticAttributes
) implements ReplacementOperation<T>, OperationWithPrecondition {

	@Override
	public SubmitReplacement<T> unconditional() {
		return new SubmitReplacement<>(target, newValue, diagnosticAttributes);
	}

	@Override
	public SubmitConditionalReplacement<T> withFilteredAttributes(Collection<String> allowedNames) {
		return new SubmitConditionalReplacement<>(target, newValue, precondition, requiredValue, MapValue.fromFunction(allowedNames, diagnosticAttributes::get));
	}

	@Override
	public void submitTo(BoskDriver driver) {
		driver.submitConditionalReplacement(target, newValue, precondition, requiredValue);
	}

}
