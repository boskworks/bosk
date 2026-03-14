package works.bosk.drivers;

import java.io.IOException;
import java.util.function.Function;
import works.bosk.BoskContext;
import works.bosk.BoskContext.ContextScope;
import works.bosk.BoskDriver;
import works.bosk.DriverFactory;
import works.bosk.Identifier;
import works.bosk.Reference;
import works.bosk.StateTreeNode;
import works.bosk.exceptions.InvalidTypeException;

/**
 * Automatically sets a {@link ContextScope} around each driver operation
 * based on a user-supplied function.
 * Allows diagnostic attributes to be supplied automatically to every operation.
 */
public final class ContextScopeDriver implements BoskDriver {
	final BoskDriver downstream;
	final BoskContext context;
	final Function<BoskContext, ContextScope> scopeSupplier;

	private ContextScopeDriver(BoskDriver downstream, BoskContext context, Function<BoskContext, ContextScope> scopeSupplier) {
		this.downstream = downstream;
		this.context = context;
		this.scopeSupplier = scopeSupplier;
	}

	public static <RR extends StateTreeNode> DriverFactory<RR> factory(Function<BoskContext, ContextScope> scopeSupplier) {
		return (b, d) -> new ContextScopeDriver(d, b.context(), scopeSupplier);
	}

	@Override
	public <R extends StateTreeNode> InitialState<R> initialState(Class<R> rootType) throws InvalidTypeException, IOException, InterruptedException {
		try (var _ = scopeSupplier.apply(context)) {
			return downstream.initialState(rootType);
		}
	}

	@Override
	public <T> void submitReplacement(Reference<T> target, T newValue) {
		try (var _ = scopeSupplier.apply(context)) {
			downstream.submitReplacement(target, newValue);
		}
	}

	@Override
	public <T> void submitConditionalReplacement(Reference<T> target, T newValue, Reference<Identifier> precondition, Identifier requiredValue) {
		try (var _ = scopeSupplier.apply(context)) {
			downstream.submitConditionalReplacement(target, newValue, precondition, requiredValue);
		}
	}

	@Override
	public <T> void submitConditionalCreation(Reference<T> target, T newValue) {
		try (var _ = scopeSupplier.apply(context)) {
			downstream.submitConditionalCreation(target, newValue);
		}
	}

	@Override
	public <T> void submitDeletion(Reference<T> target) {
		try (var _ = scopeSupplier.apply(context)) {
			downstream.submitDeletion(target);
		}
	}

	@Override
	public <T> void submitConditionalDeletion(Reference<T> target, Reference<Identifier> precondition, Identifier requiredValue) {
		try (var _ = scopeSupplier.apply(context)) {
			downstream.submitConditionalDeletion(target, precondition, requiredValue);
		}
	}

	@Override
	public void flush() throws IOException, InterruptedException {
		try (var _ = scopeSupplier.apply(context)) {
			downstream.flush();
		}
	}
}
