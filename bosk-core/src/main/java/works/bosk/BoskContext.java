package works.bosk;

import java.util.Objects;
import java.util.function.Supplier;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

import static java.util.Objects.requireNonNull;
import static java.util.function.Predicate.not;

/**
 * Thread-local data that propagates all the way from
 * submission of a driver update, through all the driver layers,
 * to the execution of hooks.
 *
 * <p>
 * One single {@code BoskContext} instance is associated with each {@link Bosk}.
 * You can hold on to this object; there's no need to re-fetch it from the {@link Bosk} every time.
 */
public final class BoskContext {
	private final ThreadLocal<Context> currentContext;

	BoskContext(Supplier<Context> initialContextSupplier) {
		currentContext = ThreadLocal.withInitial(initialContextSupplier);
	}

	ContextScope newContextScope(Context newContext) {
		ContextScope result = new ContextScope(newContext);
		currentContext.set(newContext);
		return result;
	}

	public record Context(
		MapValue<String> diagnosticAttributes
	) {
		public Context {
			requireNonNull(diagnosticAttributes);
		}

		public Context withAttribute(String name, String value) {
			return new Context(diagnosticAttributes.with(name, value));
		}

		public Context withAttributes(MapValue<String> additionalAttributes) {
			return new Context(diagnosticAttributes.withAll(additionalAttributes));
		}

		public Context withOnlyAttributes(MapValue<String> attributes) {
			return new Context(attributes);
		}

		public static Context empty() {
			return new Context(MapValue.empty());
		}
	}

	public final class ContextScope implements AutoCloseable {
		final Context oldContext;
		final Context newContext;

		private ContextScope(Context newContext) {
			this.oldContext = currentContext.get();
			this.newContext = newContext;
		}

		@Override
		public void close() {
			if (!Objects.equals(newContext, currentContext.get())) {
				throw new IllegalStateException("ContextScopes closed out of order");
			}
			currentContext.set(oldContext);
		}
	}

	public Context get() {
		return currentContext.get();
	}

	/**
	 * @return the current thread's value of the attribute with the given <code>name</code>,
	 * or <code>null</code> if no such attribute has been defined.
	 */
	public @Nullable String getAttribute(String name) {
		return currentContext.get().diagnosticAttributes().get(name);
	}

	public @NonNull MapValue<String> getAttributes() {
		return currentContext.get().diagnosticAttributes();
	}

	/**
	 * Adds a single diagnostic attribute to the current thread's context.
	 * If the attribute already exists, it will be replaced.
	 */
	public ContextScope withAttribute(String name, String value) {
		return newContextScope(currentContext.get().withAttribute(name, value));
	}

	/**
	 * Adds diagnostic attributes to the current thread's context.
	 * If an attribute already exists, it will be replaced.
	 */
	public ContextScope withAttributes(@NonNull MapValue<String> additionalAttributes) {
		return newContextScope(currentContext.get().withAttributes(additionalAttributes));
	}

	/**
	 * Replaces all diagnostic attributes in the current thread's context.
	 * Existing attributes are removed/replaced.
	 * <p>
	 * This is intended for propagating context from one thread to another.
	 * <p>
	 * If <code>attributes</code> is null, this is a no-op, and any existing attributes on this thread are retained.
	 * If ensuring a clean set of attributes is important, pass an empty map instead of null.
	 */
	public ContextScope withOnly(@Nullable MapValue<String> attributes) {
		if (attributes == null) {
			return newContextScope(currentContext.get());
		} else {
			return newContextScope(currentContext.get().withOnlyAttributes(attributes));
		}
	}

	/**
	 * Removes all diagnostic attributes from the current thread's context that start with the given prefix,
	 * and adds the given attributes after prepending the prefix to each of its keys.
	 *
	 * @param prefix the leftmost part of the keys to be replaced; must end with a dot and be at least two characters long
	 * @param replacementAttributes the attributes to be added, without the prefix
	 */
	public ContextScope withReplacedPrefix(String prefix, MapValue<String> replacementAttributes) {
		assert prefix.endsWith("."): "Prefix must end with a dot: " + prefix;
		assert prefix.length() >= 2: "Prefix must be at least two characters long: " + prefix;
		MapValue<String> prefixedAttributes = MapValue.fromFunctions(replacementAttributes.keySet(), k -> prefix+k, replacementAttributes::get);
		Context current = currentContext.get();
		return newContextScope(new Context(current.diagnosticAttributes().withOnly(
			not(k -> k.startsWith(prefix))
		).withAll(prefixedAttributes)));
	}

}
