package io.vena.bosk.drivers.operations;

import io.vena.bosk.BoskDriver;
import io.vena.bosk.Identifier;
import io.vena.bosk.MapValue;
import io.vena.bosk.Reference;
import lombok.Value;

@Value
public class SubmitConditionalReplacement<T> implements ReplacementOperation<T>, ConditionalOperation {
	Reference<T> target;
	T newValue;
	Reference<Identifier> precondition;
	Identifier requiredValue;
	MapValue<String> diagnosticAttributes;

	@Override
	public SubmitReplacement<T> unconditional() {
		return new SubmitReplacement<>(target, newValue, diagnosticAttributes);
	}

	@Override
	public void submitTo(BoskDriver<?> driver) {
		driver.submitConditionalReplacement(target, newValue, precondition, requiredValue);
	}
}
