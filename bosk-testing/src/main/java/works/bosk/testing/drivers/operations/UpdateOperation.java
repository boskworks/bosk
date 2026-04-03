package works.bosk.testing.drivers.operations;

import java.util.Collection;
import works.bosk.BoskContext;
import works.bosk.BoskDriver;
import works.bosk.MapValue;
import works.bosk.Reference;

public sealed interface UpdateOperation extends DriverOperation permits
	OperationWithPrecondition,
	DeletionOperation,
	ReplacementOperation
{
	Reference<?> target();

	/**
	 * @return true if this operation matches <code>other</code> ignoring any preconditions.
	 */
	boolean matchesIfApplied(UpdateOperation other);

	default UpdateOperation withFilteredAttributes(Collection<String> allowedNames) {
		return withBoskContext(
			boskContext().withOnlyAttributes(MapValue.fromFunction(
				allowedNames,
				boskContext().diagnosticAttributes()::get
			))
		);
	}

	UpdateOperation withBoskContext(BoskContext.Context newContext);

	@Override void submitTo(BoskDriver driver); // No checked exceptions

}
