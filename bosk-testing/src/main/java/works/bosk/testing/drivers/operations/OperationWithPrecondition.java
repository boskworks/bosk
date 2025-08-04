package works.bosk.testing.drivers.operations;

import works.bosk.Identifier;
import works.bosk.Reference;

public sealed interface OperationWithPrecondition extends UpdateOperation permits
	SubmitConditionalDeletion,
	SubmitConditionalReplacement
{
	Reference<Identifier> precondition();
	Identifier requiredValue();
	UpdateOperation unconditional();
}
