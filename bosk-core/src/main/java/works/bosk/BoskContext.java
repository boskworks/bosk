package works.bosk;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

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
	private final ThreadLocal<MapValue<String>> diagnosticAttributes = ThreadLocal.withInitial(MapValue::empty);

	public final class DiagnosticScope implements AutoCloseable {
		final MapValue<String> oldAttributes = diagnosticAttributes.get();

		DiagnosticScope(MapValue<String> attributes) {
			diagnosticAttributes.set(attributes);
		}

		@Override
		public void close() {
			diagnosticAttributes.set(oldAttributes);
		}
	}

	/**
	 * @return the current thread's value of the attribute with the given <code>name</code>,
	 * or <code>null</code> if no such attribute has been defined.
	 */
	public @Nullable String getAttribute(String name) {
		return diagnosticAttributes.get().get(name);
	}

	public @NotNull MapValue<String> getAttributes() {
		return diagnosticAttributes.get();
	}

	/**
	 * Adds a single diagnostic attribute to the current thread's context.
	 * If the attribute already exists, it will be replaced.
	 */
	public DiagnosticScope withAttribute(String name, String value) {
		return new DiagnosticScope(diagnosticAttributes.get().with(name, value));
	}

	/**
	 * Adds diagnostic attributes to the current thread's context.
	 * If an attribute already exists, it will be replaced.
	 */
	public DiagnosticScope withAttributes(@NotNull MapValue<String> additionalAttributes) {
		return new DiagnosticScope(diagnosticAttributes.get().withAll(additionalAttributes));
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
	public DiagnosticScope withOnly(@Nullable MapValue<String> attributes) {
		if (attributes == null) {
			return new DiagnosticScope(diagnosticAttributes.get());
		} else {
			return new DiagnosticScope(attributes);
		}
	}

	/**
	 * Removes all diagnostic attributes from the current thread's context that start with the given prefix,
	 * and adds the given attributes after prepending the prefix to each of its keys.
	 *
	 * @param prefix the leftmost part of the keys to be replaced; must end with a dot and be at least two characters long
	 * @param replacementAttributes the attributes to be added, without the prefix
	 */
	public DiagnosticScope withReplacedPrefix(String prefix, MapValue<String> replacementAttributes) {
		assert prefix.endsWith("."): "Prefix must end with a dot: " + prefix;
		assert prefix.length() >= 2: "Prefix must be at least two characters long: " + prefix;
		MapValue<String> prefixedAttributes = MapValue.fromFunctions(replacementAttributes.keySet(), k -> prefix+k, replacementAttributes::get);
		return new DiagnosticScope(diagnosticAttributes.get().withOnly(
			not(k -> k.startsWith(prefix))
		).withAll(prefixedAttributes));
	}
}
