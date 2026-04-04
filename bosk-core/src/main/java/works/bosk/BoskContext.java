package works.bosk;

import java.util.Objects;
import java.util.function.Supplier;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

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

	private final String boskName; // For diagnostics

	BoskContext(Supplier<Context> initialContextSupplier, String boskName) {
		currentContext = ThreadLocal.withInitial(initialContextSupplier);
		this.boskName = boskName;
	}

	ContextScope newContextScope(Context newContext) {
		ContextScope result = new ContextScope(newContext);
		validateTenantTransition(result.oldContext.tenant(), newContext.tenant());
		// ^-- Must validate before this side effect --v
		currentContext.set(newContext);
		return result;
	}

	private void validateTenantTransition(Tenant oldTenant, Tenant newTenant) {
		if (oldTenant.equals(newTenant)) {
			LOGGER.trace("Reestablishing same tenant: {}", newTenant);
		} else if (oldTenant instanceof Tenant.NotEstablished) {
			LOGGER.debug("Establishing tenant: {}", newTenant);
		} else {
			throw new IllegalArgumentException(
				"Tenant for bosk " + boskName + " is already " + oldTenant
					+ "; cannot change to: " + newTenant
					+ " on thread [" + Thread.currentThread() + "]");
		}
	}

	public record Context(
		Tenant tenant,
		MapValue<String> diagnosticAttributes
	) {
		public Context {
			requireNonNull(tenant);
			requireNonNull(diagnosticAttributes);
		}

		public Context withTenant(Tenant tenant) {
			return new Context(tenant, diagnosticAttributes);
		}

		public Context withAttribute(String name, String value) {
			return new Context(tenant, diagnosticAttributes.with(name, value));
		}

		public Context withAttributes(MapValue<String> additionalAttributes) {
			return new Context(tenant, diagnosticAttributes.withAll(additionalAttributes));
		}

		public Context withOnlyAttributes(MapValue<String> attributes) {
			return new Context(tenant, attributes);
		}

		public static Context empty() {
			return new Context(Tenant.NOT_ESTABLISHED, MapValue.empty());
		}

		public static Context emptyWithNoTenant() {
			return new Context(Tenant.NONE, MapValue.empty());
		}
	}

	public sealed interface Tenant {
		/**
		 * Tenant information has not yet been set on this thread.
		 * In this state, a new {@link ContextScope} can set the tenant ID
		 * to an {@link Established} value.
		 */
		record NotEstablished() implements Tenant {}

		/**
		 * Tenant information has been set on this thread by a {@link ContextScope}.
		 */
		sealed interface Established extends Tenant { }

		/**
		 * For a bosk configured without multi-tenancy, this is the only valid value,
		 * and it is automatically established on all threads.
		 * <p>
		 * For a multi-tenant bosk, this value is not allowed.
		 */
		record None() implements Established {}

		/**
		 * For a multi-tenant bosk, this indicates the current thread's tenant ID.
		 * <p>
		 * For a bosk configured without multi-tenancy, this value is not allowed.
		 */
		record SetTo(Identifier tenant) implements Established {}

		/**
		 * @see NotEstablished
		 */
		NotEstablished NOT_ESTABLISHED = new NotEstablished();

		/**
		 * @see None
		 */
		None NONE = new None();

		static SetTo setTo(Identifier tenant) {
			return new SetTo(tenant);
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

	public Tenant getTenant() {
		return currentContext.get().tenant();
	}

	/**
	 * @return {@link #getTenant()}
	 * @throws IllegalStateException if the tenant is not established on this thread
	 */
	public Tenant.Established getEstablishedTenant() {
		Tenant tenant = getTenant();
		if (tenant instanceof Tenant.Established established) {
			return established;
		} else {
			throw new IllegalStateException("Tenant is not established on thread " + Thread.currentThread());
		}
	}

	/**
	 * @return the current thread's value of the attribute with the given <code>name</code>,
	 * or <code>null</code> if no such attribute has been defined.
	 */
	public @Nullable String getAttribute(String name) {
		return currentContext.get().diagnosticAttributes().get(name);
	}

	public @NotNull MapValue<String> getAttributes() {
		return currentContext.get().diagnosticAttributes();
	}

	/**
	 * Adds tenant information to the current thread's context.
	 * This is idempotent: it's always valid to reestablish the same tenant information on a thread.
	 *
	 * @param tenant the tenant information to be established on this thread
	 * @throws IllegalArgumentException if tenant information is already established on this thread and is different from the given tenant
	 * @return an {@link AutoCloseable} that will restore the previous tenant information when closed
	 */
	public ContextScope withTenant(Tenant.Established tenant) {
		return newContextScope(currentContext.get().withTenant(tenant));
	}

	/**
	 * Like {@link #withTenant(Tenant.Established)}, but allows {@link Tenant#NOT_ESTABLISHED}
	 * Useful in situations where you want to mimic the tenant context of
	 * some other operation without asserting that it must be established.
	 * <p>
	 * The "maybe" in the name indicates the tenant can be not established,
	 * not that the method might choose to ignore the tenant argument.
	 *
	 * @see #withTenant(Tenant.Established)
	 */
	public ContextScope withMaybeTenant(Tenant tenant) {
		return newContextScope(currentContext.get().withTenant(tenant));
	}

	/**
	 * Removes any tenant information from the current thread's context.
	 * <p>
	 * Package-private because this is only intended for use by Bosk itself.
	 * All outside code must be bound by the constraints of the tenant already established.
	 */
	ContextScope withTenantTemporarilyIgnored() {
		ContextScope contextScope = new ContextScope(currentContext.get().withTenant(Tenant.NOT_ESTABLISHED));
		// No validation required; we're specifically forcing the tenant to be ignored here
		currentContext.set(contextScope.newContext);
		return contextScope;
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
	public ContextScope withAttributes(@NotNull MapValue<String> additionalAttributes) {
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
		return newContextScope(new Context(current.tenant(), current.diagnosticAttributes().withOnly(
			not(k -> k.startsWith(prefix))
		).withAll(prefixedAttributes)));
	}

	private static final Logger LOGGER = LoggerFactory.getLogger(BoskContext.class);
}
