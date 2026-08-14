package works.bosk;

import java.io.IOException;
import java.lang.invoke.MethodHandles;
import java.lang.reflect.Type;
import java.util.Collection;
import java.util.Objects;
import java.util.function.Consumer;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import works.bosk.annotations.Hook;
import works.bosk.exceptions.InvalidTypeException;
import works.bosk.exceptions.NonexistentReferenceException;
import works.bosk.exceptions.NotYetImplementedException;

import static java.util.Collections.unmodifiableCollection;
import static java.util.Objects.requireNonNull;

/**
 * A mutable container for an immutable object tree with cross-tree {@link Reference}s,
 * providing snapshot-at-start semantics via {@link ReadSession ReadSession},
 * managing updates via {@link BoskDriver},
 * and notifying listeners of changes via {@link #hookRegistrar() hookRegistrar}.
 *
 * <p>
 * The intent is that there would be one of these injected into your
 * application using something like Guice or Spring beans,
 * managing state in a way that abstracts the differences between
 * a standalone server and a replica set.
 * Typically, you make a subclass that supplies the {@link R} parameter
 * and provides a variety of handy pre-built {@link Reference}s.
 *
 * <p>
 * Reads are performed by calling {@link Reference#value()} in the context of
 * a {@code ReadSession}, which provides an immutable snapshot of the bosk
 * state to the thread.
 * This object acts as a factory for {@link Reference} objects that
 * traverse the object trees by walking their fields (actually getter methods)
 * according to their {@link Reference#path}.
 *
 * <p>
 * Updates are performed by submitting an update via
 * {@link BoskDriver#submitReplacement(Reference, Object) submitReplacement} and similar,
 * rather than by modifying the in-memory state directly.
 * The driver will apply the changes either immediately or at a later time.
 * Regardless, updates will not be visible in any {@code ReadSession}
 * created before the update occurred.
 *
 * @param <R> The type of the state tree's root node
 * @author pdoyle
 */
public non-sealed class Bosk<R extends StateTreeNode> extends BoskBase<R> implements BoskInfo<R> {

	/**
	 * @param name                A distinctive identifier string. The bosk framework doesn't use this, so there are no requirements on this string: it can be anything that identifies the object.
	 * @param rootType            The {@link Type} of the root node of the state tree, whose {@link Reference#path path} is <code>"/"</code>.
	 * @param defaultStateFunction The root object to use if the driver chooses not to supply one,
	 *                            and instead delegates {@link BoskDriver#initialState} all the way to the local driver.
	 *                            Note that this function may or may not be called, so don't use it as a means to initialize
	 *                            other state.
	 * @param boskConfig          Customizations for this bosk.
	 * @see DriverStack
	 */
	public Bosk(String name, Type rootType, DefaultStateFunction<R> defaultStateFunction, BoskConfig<R> boskConfig) {
		super(name, rootType, defaultStateFunction, boskConfig);
	}

	@Override
	public String name() {
		return this.name;
	}

	@Override
	public Identifier instanceID() {
		return this.instanceID;
	}

	@Override
	public BoskContext context() {
		return this.context;
	}

	/**
	 * Convenience method to create a bosk with only the basic functionality,
	 * to get going quickly.
	 * To customize the bosk behaviour later,
	 * you can inline this into your call site and modify it as desired.
	 *
	 * @param name        A distinctive identifier string. The bosk framework doesn't use this, so there are no requirements on this string: it can be anything that identifies the object.
	 * @param initialRoot The starting value of the bosk state tree, before any updates.
	 */
	public static <RR extends StateTreeNode> Bosk<RR> simple(String name, RR initialRoot) {
		return new Bosk<>(requireNonNull(name), initialRoot.getClass(), _ -> initialRoot, BoskConfig.simple());
	}

	public interface DefaultStateFunction<RR extends StateTreeNode> {
		RR apply(Bosk<RR> bosk) throws InvalidTypeException, IOException, InterruptedException;
	}

	@Override
	public Bosk<R> bosk() {
		return this;
	}

	/**
	 * Provides access to the {@link BoskDriver} object to use for submitting updates to this bosk's state tree.
	 * <p>
	 * The bosk's driver is fixed for the lifetime of the bosk.
	 * You can hold on to the returned object if that suits you; there's no need to re-fetch it.
	 *
	 * @return the {@link BoskDriver} to use for submitting updates to this bosk's state tree.
	 */
	public BoskDriver driver() {
		return ingressDriver;
	}

	/**
	 * Provides access to the {@link HookRegistrar} object to use for registering hooks on this bosk.
	 * <p>
	 * The bosk's hook registrar is fixed for the lifetime of the bosk.
	 * You can hold on to the returned object if that suits you; there's no need to re-fetch it.
	 *
	 * @return the {@link HookRegistrar} to use for registering hooks on this bosk.
	 */
	public HookRegistrar hookRegistrar() {
		return hookRegistrar;
	}

	/**
	 * <strong>Evolution note</strong>: we need better handling of the driver stack.
	 * For now, we just provide access to the topmost driver, but code should be able
	 * to look up any driver on the stack. We need to think carefully about how we
	 * want this to work.
	 *
	 * @return the driver from the driver stack having the given type.
	 * @throws IllegalArgumentException if there is no unique driver of the given type
	 */
	@SuppressWarnings("unchecked")
	public <D extends BoskDriver> D getDriver(Class<? super D> driverType) {
		var userSuppliedDriver = ingressDriver.downstream;
		if (driverType.isInstance(userSuppliedDriver)) {
			return (D) driverType.cast(userSuppliedDriver);
		} else {
			throw new NotYetImplementedException("Can't look up driver of type " + driverType);
		}
	}

	/**
	 * Finds methods annotated with {@link Hook} in the given {@code receiver} object
	 * and registers them as hooks in this bosk.
	 * <p>
	 * The {@link Hook @Hook} annotation specifies the <em>scope</em> of the hook:
	 * a path string indicating which state tree node whose updates will trigger the hook.
	 * The scope path may contain parameters (e.g., {@code "/widgets/-widget-"}),
	 * in which case the hook will be called when any matching node is updated.
	 * <p>
	 * As always, when hooks are registered, they are immediately triggered
	 * for all existing nodes that match their scope,
	 * allowing the hooks to "get caught up" with all changes that occurred before they were registered.
	 * <p>
	 * Hook methods can accept arguments which will be injected by the framework when the hook is called.
	 * <p>
	 * An argument of type {@link Reference} will receive a reference to the specific object that changed and triggered the hook.
	 * This is useful if the scope is parameterized, since the reference passed to the method will have all its parameters bound.
	 * The target type of a {@link Reference} argument must match that of the hook's scope.
	 * <p>
	 * An argument of type {@link BindingEnvironment} will receive bindings for all parameters in the hook's scope path.
	 * This is useful if the hook implementation wants to access related references.
	 *
	 * <p>
	 * Example:
	 * <pre>{@code
	 * class ExampleHooks {
	 *     @Hook("/widgets/-widget-")
	 *     void onWidgetChanged(Reference<Widget> widgetRef) {
	 *         // Simple hook that just accesses the object that changed
	 *         Widget widget = widgetRef.value();
	 *         ...
	 *     }
	 *
	 *     interface Refs {
	 *         // An example of a related reference, using the same parameter name as the hook scope
	 *         @ReferencePath("/widgetConfigs/-widget-")
	 *         Reference<WidgetConfig> widgetConfig();
	 *     }
	 *
	 *     @Hook("/widgets/-widget-")
	 *     void onWidgetChanged(BindingEnvironment bindings) {
	 *         // Access a related object using the same parameter bindings
	 *         WidgetConfig config = refs.widgetConfig().boundBy(bindings).value();
	 *         ...
	 *     }
	 * }
	 * }</pre>
	 *
	 * Hook methods must not be static or private, but they can be package-private.
	 * Inherited methods are included in the scan.
	 * If a subclass overrides a hook method, only the most-derived override is registered.
	 * <p>
	 * The Hook class must have no methods inaccessible to the given {@code lookup}.
	 * Such methods interfere with our ability to validate that the hook methods are well-formed.
	 *
	 * @param receiver the object whose {@link Hook @Hook}-annotated methods should be registered
	 * @param lookup a {@link MethodHandles.Lookup} object with access to {@code receiver}'s methods
	 * @throws InvalidTypeException if any hook method is invalid (static, private, has unsupported parameters, etc.)
	 * @see Hook
	 */
	public void registerHooks(Object receiver, MethodHandles.Lookup lookup) throws InvalidTypeException {
		HookScanner.registerHooks(receiver, this.rootReference(), this.hookRegistrar(), lookup);
	}

	public Collection<HookRegistration<?>> allRegisteredHooks() {
		return unmodifiableCollection(hooks);
	}

	// Inner class can't be a record
	public final class HookRegistration<S> {
		private final String name;
		private final Reference<S> scope;
		private final BoskHook<S> hook;

		public HookRegistration(String name, Reference<S> scope, BoskHook<S> hook) {
			this.name = name;
			this.scope = scope;
			this.hook = hook;
		}

		/**
		 * Calls <code>action</code> for every object whose path matches <code>scope</code> that
		 * was changed by a driver event targeting <code>target</code>.
		 *
		 * @param priorRoot The bosk root object before the driver event occurred
		 * @param newRoot   The bosk root object after the driver event occurred
		 * @param target    The object specified by the driver event
		 * @param action    The operation to perform for each matching object that could have changed
		 */
		void triggerAction(@Nullable R priorRoot, R newRoot, Reference<?> target, Consumer<Reference<S>> action) {			Reference<S> effectiveScope;
			int relativeDepth = target.path().length() - scope.path().length();
			if (relativeDepth >= 0) {
				// target may be the scope object or a descendant
				Path candidate = target.path().truncatedBy(relativeDepth);
				if (scope.path().matches(candidate)) {
					effectiveScope = scope.boundBy(candidate);
				} else {
					return;
				}
			} else {
				// target may be an ancestor of the scope object
				Path enclosingScope = scope.path().truncatedBy(-relativeDepth);
				if (enclosingScope.matches(target.path())) {
					effectiveScope = scope.boundBy(target.path());
				} else {
					return;
				}
			}
			triggerCascade(effectiveScope, priorRoot, newRoot, action);
		}

		public String name() {
			return this.name;
		}

		public Reference<S> scope() {
			return this.scope;
		}

		public BoskHook<S> hook() {
			return this.hook;
		}

		@Override
		public boolean equals(Object o) {
			if (o == null || getClass() != o.getClass()) return false;
			@SuppressWarnings("unchecked")
			HookRegistration<?> that = (HookRegistration<?>) o;
			return Objects.equals(name, that.name)
				&& Objects.equals(scope, that.scope)
				&& Objects.equals(hook, that.hook);
		}

		@Override
		public int hashCode() {
			return Objects.hash(name, scope, hook);
		}

		@Override
		public String toString() {
			return "Bosk.HookRegistration(name=" + this.name() + ", scope=" + this.scope() + ", hook=" + this.hook + ")";
		}
	}

	/**
	 * A thread-local region in which {@link Reference#value()} works; outside
	 * of a {@code ReadSession}, {@link Reference#value()} will throw {@link
	 * IllegalStateException}.
	 *
	 * @author pdoyle
	 */
	public final class ReadSession implements AutoCloseable {
		final R originalRoot;
		final R snapshot; // Mostly for adopt()

		/**
		 * Creates a {@link ReadSession} for the current thread. If one is already
		 * active on this thread, the new nested one will be equivalent and has
		 * no effect.
		 */
		private ReadSession() {
			originalRoot = rootSnapshot.get();
			if (originalRoot == null) {
				snapshot = currentState;
				if (snapshot == null) {
					throw new IllegalStateException("Bosk constructor has not yet finished; cannot create a ReadSession");
				}
				rootSnapshot.set(snapshot);
				LOGGER.trace("New {}", this);
			} else {
				// Inner sessions use the same snapshot as outer sessions
				snapshot = originalRoot;
				LOGGER.trace("Nested {}", this);
			}
		}

		private ReadSession(ReadSession toAdopt) {
			R snapshotToInherit = requireNonNull(toAdopt.snapshot);
			originalRoot = rootSnapshot.get();
			if (originalRoot == null) {
				rootSnapshot.set(this.snapshot = snapshotToInherit);
				LOGGER.trace("Sharing {}", this);
			} else if (originalRoot == snapshotToInherit) {
				// Some thread pools recruit the calling thread itself; don't want to disallow this.
				this.snapshot = originalRoot;
				LOGGER.trace("Re-sharing {}", this);
			} else {
				throw new IllegalStateException("Read session for " + name + " already active in " + Thread.currentThread());
			}
		}

		/**
		 * Internal constructor to use a given state.
		 *
		 * <p>
		 * Unlike the other constructors, this can be used to substitute a new state temporarily,
		 * even if there's already one active on the current thread.
		 */
		ReadSession(@NonNull R state) {
			originalRoot = rootSnapshot.get();
			snapshot = requireNonNull(state);
			rootSnapshot.set(snapshot);
			LOGGER.trace("Using {}", this);
		}

		/**
		 * Creates a {@link ReadSession} for the current thread, inheriting state
		 * from another thread.
		 * Any calls to {@link Reference#value()} on the current thread will return
		 * the same value they would have returned on the thread where
		 * <code>this</code> session was created.
		 *
		 * <p>
		 * Because nested sessions behave like their outer session, you can always
		 * make another ReadSession at any time on some thread to
		 * "capture" whatever session may be in effect on that thread (or to
		 * create a new one if there is no active session on that thread).
		 *
		 * <p>
		 * Hence, a recommended idiom for session inheritance looks like this:
		 *
		 * <blockquote><pre>
		 * try (ReadSession originalThReadSession = bosk.readSession()) {
		 *     workQueue.submit(() -> {
		 *         try (ReadSession workerThReadSession = bosk.adopt(originalThReadSession)) {
		 *             // Code in here can read from the bosk just like the original thread.
		 *         }
		 *     });
		 * }
		 * </pre></blockquote>
		 *
		 * Note, though, that this will prevent the garbage collector from
		 * collecting the ReadSession's state snapshot until the worker thread's
		 * session is finished. Therefore, if the worker thread is to continue running
		 * after the original thread would have exited its own session,
		 * then use this idiom only if the worker thread must see
		 * the same state snapshot as the original thread <em>and</em> you're
		 * willing to prevent that snapshot from being garbage-collected until
		 * the worker thread finishes.
		 *
		 * @return a <code>ReadSession</code> representing the new session.
		 */
		public ReadSession adopt() {
			return new ReadSession(this);
		}

		@Override
		public void close() {
			// TODO: Enforce the closing rules described in readSession javadocs?
			LOGGER.trace("Exiting {}; restoring {}", this, System.identityHashCode(originalRoot));
			rootSnapshot.set(originalRoot);
		}

		@Override
		public String toString() {
			return "ReadSession(" + System.identityHashCode(snapshot) + ")";
		}
	}

	/**
	 * Establishes a {@link ReadSession} for the calling thread,
	 * allowing {@link Reference#value()} to return values from this bosk's state tree,
	 * from a snapshot taken at the moment this method was called.
	 * The snapshot is held stable until the returned session is {@link ReadSession#close() closed}.
	 *
	 * <p>
	 * If the calling thread has an active session already,
	 * the returned <code>ReadSession</code> has no effect:
	 * the state snapshot from the existing session will continue to be used on the calling thread
	 * until both sessions (the returned one and the existing one) are closed.
	 *
	 * <p>
	 * <code>ReadSession</code>s must be closed on the same thread on which they were opened,
	 * and must be closed in reverse order.
	 * We recommend using them in <i>try-with-resources</i> statements;
	 * otherwise, you could end up with some sessions ending prematurely,
	 * and others persisting for the remainder of the thread's lifetime.
	 */
	public final ReadSession readSession() {
		return new ReadSession();
	}

	/**
	 * Establishes a new {@link ReadSession} for the calling thread, similar to {@link #readSession()}, except that
	 * if the calling thread already has a session, it will be ignored,
	 * and the newly created session will have a fresh snapshot of the bosk's state tree;
	 * then, when the returned session is {@link ReadSession#close closed},
	 * the previous session will be restored.
	 * <p>
	 * This is intended to support coordination of distributed logic among multiple threads (or servers) using the same bosk.
	 * Threads can submit an update, call {@link BoskDriver#flush}, and then use this method
	 * to inspect the bosk state and determine what effect the update had.
	 * <p>
	 * Use this method when it's important to observe the bosk state after a {@link BoskDriver#flush flush}
	 * performed by the same thread.
	 * When in doubt, you probably want {@link #readSession()} instead of this.
	 * This method opens the possibility that the same thread can see two different revisions of the bosk state,
	 * which can lead to confusing bugs in application code.
	 * In addition, when the returned session is {@link ReadSession#close closed},
	 * the bosk state can appear to revert to a prior state, which can be confusing.
	 *
	 * @see #readSession()
	 */
	public final ReadSession supersedingReadSession() {
		return newSupersedingReadSession();
	}

	/**
	 * An {@link Optional#empty()}, or missing {@link Catalog} or
	 * {@link SideTable} entry, was encountered when walking along
	 * object fields, indicating that the desired item is absent.
	 *
	 * <p>
	 * This is an internal exception used in the implementation of Bosk.
	 * It differs from {@link NonexistentReferenceException},
	 * which is a user-facing exception that is part of the contract of {@link Reference#value()}.
	 */
	public static final class NonexistentEntryException extends Exception {
		final Path path;

		public NonexistentEntryException(Path path) {
			super("No object at path \"" + path.toString() + "\"");
			this.path = path;
		}

		public Path path() {
			return this.path;
		}
	}

	/**
	 * Equivalent to {@code rootReference().buildReferences(refsClass)}.
	 *
	 * @see RootReference#buildReferences
	 */
	public final <T> T buildReferences(Class<T> refsClass) throws InvalidTypeException {
		return rootReference().buildReferences(refsClass);
	}

	/**
	 * @return a {@link RootReference} whose {@link Reference#path path} is {@link Path#isEmpty empty}.
	 */
	public final RootReference<R> rootReference() {
		return rootRef;
	}

	@Override
	public final String toString() {
		return instanceID() + " \"" + name + "\"::" + rootRef.targetClass().getSimpleName();
	}

	/**
	 * Logger name for hook execution specifically, so that hook-execution warnings can be
	 * selectively suppressed (for example in tests) without affecting other bosk logs.
	 */
	public static final String HOOK_LOGGER_NAME = Bosk.class.getName() + ".hooks";

	private static final Logger LOGGER = LoggerFactory.getLogger(Bosk.class);
}
