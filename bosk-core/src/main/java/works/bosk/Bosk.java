package works.bosk;

import java.io.IOException;
import java.lang.reflect.Type;
import java.util.Collection;
import java.util.Deque;
import java.util.List;
import java.util.ListIterator;
import java.util.Objects;
import java.util.Optional;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.Value;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import works.bosk.BoskDiagnosticContext.DiagnosticScope;
import works.bosk.ReferenceUtils.CatalogRef;
import works.bosk.ReferenceUtils.ListingRef;
import works.bosk.ReferenceUtils.SideTableRef;
import works.bosk.dereferencers.Dereferencer;
import works.bosk.dereferencers.PathCompiler;
import works.bosk.exceptions.InvalidTypeException;
import works.bosk.exceptions.NoReadContextException;
import works.bosk.exceptions.NonexistentReferenceException;
import works.bosk.exceptions.NotYetImplementedException;
import works.bosk.exceptions.ReferenceBindingException;
import works.bosk.util.Classes;

import static java.lang.Thread.holdsLock;
import static java.util.Collections.unmodifiableCollection;
import static java.util.Objects.requireNonNull;
import static java.util.UUID.randomUUID;
import static lombok.AccessLevel.NONE;
import static works.bosk.Path.parameterNameFromSegment;
import static works.bosk.ReferenceUtils.rawClass;
import static works.bosk.TypeValidation.validateType;

/**
 * A mutable container for an immutable object tree with cross-tree {@link Reference}s,
 * providing snapshot-at-start semantics via {@link ReadContext},
 * managing updates via {@link BoskDriver},
 * and notifying listeners of changes via {@link #registerHook}.
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
 * a {@link ReadContext}, which provides an immutable snapshot of the bosk
 * state to the thread.
 * This object acts as a factory for {@link Reference} objects that
 * traverse the object trees by walking their fields (actually getter methods)
 * according to their {@link Reference#path}.
 *
 * <p>
 * Updates are performed by submitting an update via {@link
 * BoskDriver#submitReplacement(Reference, Object)} and similar,
 * rather than by modifying the in-memory state directly.
 * The driver will apply the changes either immediately or at a later time.
 * Regardless, updates will not be visible in any {@link ReadContext}
 * created before the update occurred.
 *
 * @author pdoyle
 *
 * @param <R> The type of the state tree's root node
 */
public class Bosk<R extends StateTreeNode> implements BoskInfo<R> {
	@Getter private final String name;
	@Getter private final Identifier instanceID = Identifier.from(randomUUID().toString());
	@Getter private final BoskDiagnosticContext diagnosticContext = new BoskDiagnosticContext();

	private final ValidatingDriver driver;
	private final LocalDriver localDriver;
	private final RootRef rootRef;
	private final ThreadLocal<R> rootSnapshot = new ThreadLocal<>();
	private final HookRegistrar hookRegistrar;
	private final Queue<HookRegistration<?>> hooks = new ConcurrentLinkedQueue<>();
	private final ExecutorService hookExecutor = Executors.newVirtualThreadPerTaskExecutor();
	private final PathCompiler pathCompiler;

	// Mutable state
	private volatile R currentRoot;

	/**
	 * @param name A distinctive identifier string. The bosk framework doesn't use this, so there are no requirements on this string: it can be anything that identifies the object.
	 * @param rootType The @{link Type} of the root node of the state tree, whose {@link Reference#path path} is <code>"/"</code>.
	 * @param defaultRootFunction The root object to use if the driver chooses not to supply one,
	 *    and instead delegates {@link BoskDriver#initialRoot} all the way to the local driver.
	 *    Note that this function may or may not be called, so don't use it as a means to initialize
	 *    other state.
	 * @param driverFactory Will be applied to this Bosk's local driver during
	 * the Bosk's constructor, and the resulting {@link BoskDriver} will be the
	 * one returned by {@link #getDriver}.
	 *
	 * @see DriverStack
	 */
	@SuppressWarnings("this-escape")
	public Bosk(String name, Type rootType, DefaultRootFunction<R> defaultRootFunction, DriverFactory<R> driverFactory, RegistrarFactory registrarFactory) {
		this.name = requireNonNull(name);
		this.pathCompiler = PathCompiler.withSourceType(requireNonNull(rootType)); // Required before rootRef
		this.localDriver = new LocalDriver(requireNonNull(defaultRootFunction));
		this.rootRef = new RootRef(rootType);
		try {
			validateType(rootType);
		} catch (InvalidTypeException e) {
			throw new IllegalArgumentException("Invalid root type " + rootType + ": " + e.getMessage(), e);
		}

		UnderConstruction<R> boskInfo = new UnderConstruction<>(
			name, instanceID, rootRef, diagnosticContext, this::registerHooks, new AtomicReference<>());

		// We do this as late as possible because the driver factory is allowed
		// to do such things as create References, so it needs the rest of the
		// initialization to have completed already.
		//
		this.driver = new ValidatingDriver(requireNonNull(driverFactory.build(boskInfo, this.localDriver)));
		this.hookRegistrar = requireNonNull(registrarFactory.build(boskInfo, this::localRegisterHook));

		try {
			this.currentRoot = rootRef.targetClass().cast(requireNonNull(driver.initialRoot(rootType)));
		} catch (InvalidTypeException | IOException | InterruptedException e) {
			throw new IllegalArgumentException("Error computing initial root: " + e.getMessage(), e);
		}

		// Type check
		rawClass(rootType).cast(this.currentRoot);

		// Ok, we're done initializing
		boskInfo.boskRef().set(this); // @SuppressWarnings("this-escape")
	}

	/**
	 * @deprecated This constructor is going to be removed. You can inline it into your code to get its replacement.
	 */
	@Deprecated(forRemoval = true)
	public Bosk(String name, Type rootType, DefaultRootFunction<R> defaultRootFunction, DriverFactory<R> driverFactory) {
		this(name, rootType, defaultRootFunction, driverFactory, Bosk.simpleRegistrar());
	}

	/**
	 * Convenience method to create a bosk with only the basic functionality,
	 * to get going quickly.
	 * To customize the bosk behaviour later,
	 * you can inline this into your call site and modify it as desired.
	 * @param name A distinctive identifier string. The bosk framework doesn't use this, so there are no requirements on this string: it can be anything that identifies the object.
	 * @param initialRoot The starting value of the bosk state tree, before any updates.
	 */
	public static <RR extends StateTreeNode> Bosk<RR> simple(String name, RR initialRoot) {
		return new Bosk<>(requireNonNull(name), initialRoot.getClass(), b -> initialRoot, simpleDriver(), simpleRegistrar());
	}

	public interface DefaultRootFunction<RR extends StateTreeNode> {
		RR apply(Bosk<RR> bosk) throws InvalidTypeException;
	}

	record UnderConstruction<RR extends StateTreeNode>(
		String name,
		Identifier instanceID,
		RootReference<RR> rootReference,
		BoskDiagnosticContext diagnosticContext,
		RegisterHooksMethod m,
		AtomicReference<Bosk<RR>> boskRef
	) implements BoskInfo<RR> {
		@Override
		public void registerHooks(Object receiver) throws InvalidTypeException {
			m.registerHooks(receiver);
		}

		@Override
		public Bosk<RR> bosk() {
			var result = boskRef.get();
			if (result == null) {
				throw new IllegalStateException("Bosk is not yet initialized");
			} else {
				return result;
			}
		}
	}

	private interface RegisterHooksMethod {
		void registerHooks(Object receiver) throws InvalidTypeException;
	}

	/**
	 * @return a {@link DriverFactory} with only the basic functionality.
	 */
	public static <RR extends StateTreeNode> DriverFactory<RR> simpleDriver() {
		return (b,d) -> d;
	}

	/**
	 * @return a {@link RegistrarFactory} with only the basic functionality.
	 */
	public static RegistrarFactory simpleRegistrar() {
		return (b,d) -> d;
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
		return driver;
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
		var userSuppliedDriver = driver.downstream;
		if (driverType.isInstance(userSuppliedDriver)) {
			return (D)driverType.cast(userSuppliedDriver);
		} else {
			throw new NotYetImplementedException("Can't look up driver of type " + driverType);
		}
	}

	/**
	 * We wrap the user-supplied driver with one of these to ensure the error-checking
	 * requirements of the {@link BoskDriver} are enforced.
	 */
	@RequiredArgsConstructor
	final class ValidatingDriver implements BoskDriver {
		final BoskDriver downstream;

		@Override
		public <T> void submitReplacement(Reference<T> target, T newValue) {
			assertCorrectBosk(target);
			downstream.submitReplacement(target, newValue);
		}

		@Override
		public <T> void submitConditionalReplacement(Reference<T> target, T newValue, Reference<Identifier> precondition, Identifier requiredValue) {
			assertCorrectBosk(target);
			assertCorrectBosk(precondition);
			downstream.submitConditionalReplacement(target, newValue, precondition, requiredValue);
		}

		@Override
		public <T> void submitConditionalCreation(Reference<T> target, T newValue) {
			assertCorrectBosk(target);
			downstream.submitConditionalCreation(target, newValue);
		}

		@Override
		public <T> void submitDeletion(Reference<T> target) {
			if (target.path().isEmpty()) {
				// TODO: Augment dereferencer so it can tell us this for all references, not just the root
				throw new IllegalArgumentException("Cannot delete root object");
			}
			assertCorrectBosk(target);
			downstream.submitDeletion(target);
		}

		@Override
		public <T> void submitConditionalDeletion(Reference<T> target, Reference<Identifier> precondition, Identifier requiredValue) {
			assertCorrectBosk(target);
			assertCorrectBosk(precondition);
			downstream.submitConditionalDeletion(target, precondition, requiredValue);
		}

		@Override
		public StateTreeNode initialRoot(Type rootType) throws InvalidTypeException, IOException, InterruptedException {
			return rootRef.targetClass().cast(downstream.initialRoot(rootType));
		}

		@Override
		public void flush() throws IOException, InterruptedException {
			downstream.flush();
		}

		private <T> void assertCorrectBosk(Reference<T> target) {
			// TODO: Do we need to be this strict?
			// On the one hand, we could write conditional updates in a way that don't require the
			// reference to point to the right bosk.
			// On the other hand, there's a certain symmetry to requiring the references to have the right
			// bosk for both reads and writes, and forcing this discipline on users might help them avoid
			// some pretty confusing mistakes.
			assert ((Bosk<?>.RootRef)target.root()).bosk() == Bosk.this: "Reference supplied to driver operation must refer to the correct bosk";
		}

	}

	/**
	 * {@link BoskDriver} that writes directly to this {@link Bosk}.
	 *
	 * <p>
	 * Acts as the gatekeeper for state changes. This object is what provides thread safety.
	 *
	 * <p>
	 * When it comes to hooks, this provides three guarantees:
	 *
	 * <ol><li>
	 * Updates submitted to this driver are applied to the Bosk state in the order they were submitted.
	 * </li><li>
	 * Hooks are run sequentially: no hook begins until the previous one finishes.
	 * </li><li>
	 * Hooks are run in <em>breadth-first</em> fashion:
	 * hooks triggered by one update run before any hooks triggered by subsequent updates,
	 * even if those hooks themselves submit more updates.
	 * </li></ol>
	 *
	 * Satisfying all of these simultaneously is tricky, especially because we can't just put
	 * "synchronized" on the submit methods because that could cause deadlock. We also don't
	 * want to require a background thread for hook processing, partly on principle: if our
	 * execution model is so complex that it requires a background thread just to make updates
	 * to objects in memory, it feels like we've taken a step in the wrong direction.
	 *
	 * @see #drainQueueIfAllowed() for algorithm details
	 *
	 * @author pdoyle
	 */
	@RequiredArgsConstructor
	private final class LocalDriver implements BoskDriver {
		final DefaultRootFunction<R> initialRootFunction;
		final Deque<Runnable> hookExecutionQueue = new ConcurrentLinkedDeque<>();
		final Semaphore hookExecutionPermit = new Semaphore(1);

		@Override
		public StateTreeNode initialRoot(Type rootType) throws InvalidTypeException {
			R initialRoot = requireNonNull(initialRootFunction.apply(Bosk.this));
			rawClass(rootType).cast(initialRoot);
			return initialRoot;
		}

		@Override
		public <T> void submitReplacement(Reference<T> target, T newValue) {
			synchronized (this) {
				R priorRoot = currentRoot;
				if (!tryGraftReplacement(target, newValue)) {
					return;
				}
				queueHooks(target, priorRoot);
			}
			drainQueueIfAllowed();
		}

		@Override
		public <T> void submitConditionalCreation(Reference<T> target, T newValue) {
			synchronized (this) {
				boolean preconditionsSatisfied;
				try (@SuppressWarnings("unused") ReadContext executionContext = supersedingReadContext()) {
					preconditionsSatisfied = !target.exists();
				}
				if (preconditionsSatisfied) {
					R priorRoot = currentRoot;
					if (!tryGraftReplacement(target, newValue)) {
						return;
					}
					queueHooks(target, priorRoot);
				}
			}
			drainQueueIfAllowed();
		}

		@Override
		public <T> void submitDeletion(Reference<T> target) {
			synchronized (this) {
				R priorRoot = currentRoot;
				if (!tryGraftDeletion(target)) {
					return;
				}
				queueHooks(target, priorRoot);
			}
			drainQueueIfAllowed();
		}

		@Override
		public void flush() {
			// Nothing to do here. Updates are applied to the current state immediately as they arrive.
			// No need to drain the hook queue because `flush` makes no guarantees about hooks.
		}

		@Override
		public <T> void submitConditionalReplacement(Reference<T> target, T newValue, Reference<Identifier> precondition, Identifier requiredValue) {
			synchronized (this) {
				boolean preconditionsSatisfied;
				try (@SuppressWarnings("unused") ReadContext executionContext = supersedingReadContext()) {
					preconditionsSatisfied = Objects.equals(precondition.valueIfExists(), requiredValue);
				}
				if (preconditionsSatisfied) {
					R priorRoot = currentRoot;
					if (!tryGraftReplacement(target, newValue)) {
						return;
					}
					queueHooks(target, priorRoot);
				}
			}
			drainQueueIfAllowed();
		}

		@Override
		public <T> void submitConditionalDeletion(Reference<T> target, Reference<Identifier> precondition, Identifier requiredValue) {
			synchronized (this) {
				boolean preconditionsSatisfied;
				try (@SuppressWarnings("unused") ReadContext executionContext = supersedingReadContext()) {
					preconditionsSatisfied = Objects.equals(precondition.value(), requiredValue);
				}
				if (preconditionsSatisfied) {
					R priorRoot = currentRoot;
					if (!tryGraftDeletion(target)) {
						return;
					}
					queueHooks(target, priorRoot);
				}
			}
			drainQueueIfAllowed();
		}

		/**
		 * Run the given hook on every existing object that matches its scope.
		 */
		void triggerEverywhere(HookRegistration<?> reg) {
			synchronized (this) {
				triggerQueueingOfHooks(rootReference(), null, currentRoot, reg);
			}
			drainQueueIfAllowed();
		}

		/**
		 * @return false if the update was ignored
		 */
		private <T> boolean tryGraftReplacement(Reference<T> target, T newValue) {
			assert holdsLock(this);
			Dereferencer dereferencer = dereferencerFor(target);
			try {
				LOGGER.debug("Applying replacement at {}", target);
				R oldRoot = currentRoot;
				@SuppressWarnings("unchecked")
				R newRoot = (R) requireNonNull(dereferencer.with(oldRoot, target, requireNonNull(newValue)));
				currentRoot = newRoot;
				if (LOGGER.isTraceEnabled()) {
					LOGGER.trace("Replacement at {} changed root from {} to {}",
						target,
						System.identityHashCode(oldRoot),
						System.identityHashCode(newRoot));
				}
				return true;
			} catch (NonexistentEntryException e) {
				LOGGER.debug("Ignoring replacement of {}", target, e);
				return false;
			}
		}

		/**
		 * @return false if the update was ignored
		 */
		private <T> boolean tryGraftDeletion(Reference<T> target) {
			assert holdsLock(this);
			Path targetPath = target.path();
			assert !targetPath.isEmpty();
			Dereferencer dereferencer = dereferencerFor(target);
			try {
				LOGGER.debug("Applying deletion at {}", target);
				R oldRoot = currentRoot;
				@SuppressWarnings("unchecked")
				R newRoot = (R) requireNonNull(dereferencer.without(oldRoot, target));
				currentRoot = newRoot;
				if (LOGGER.isTraceEnabled()) {
					LOGGER.trace("Deletion at {} changed root from {} to {}",
						target,
						System.identityHashCode(oldRoot),
						System.identityHashCode(newRoot));
				}
				return true;
			} catch (NonexistentEntryException e) {
				LOGGER.debug("Ignoring deletion of {}", target, e);
				return false;
			}
		}

		private Dereferencer dereferencerFor(Reference<?> ref) {
			// We could just pull it out of ref, if it's a ReferenceImpl, but we can't assume that
			return compileVettedPath(ref.path());
		}

		private <T> void queueHooks(Reference<T> target, @Nullable R priorRoot) {
			R rootForHook = currentRoot;
			for (HookRegistration<?> reg: hooks) {
				triggerQueueingOfHooks(target, priorRoot, rootForHook, reg);
			}
		}

		/**
		 * For a given {@link HookRegistration}, queues up a call to {@link BoskHook#onChanged}
		 * for each matching object that changed between <code>priorRoot</code> and <code>rootForHook</code>
		 * when <code>target</code> was updated. If <code>priorRoot</code> is null, the hook is called
		 * on every matching object that exists in <code>rootForHook</code>.
		 */
		private <T,S> void triggerQueueingOfHooks(Reference<T> target, @Nullable R priorRoot, R rootForHook, HookRegistration<S> reg) {
			MapValue<String> attributes = diagnosticContext.getAttributes();
			reg.triggerAction(priorRoot, rootForHook, target, changedRef -> {
				LOGGER.debug("Hook: queue {}({}) due to {}", reg.name, changedRef, target);
				hookExecutionQueue.addLast(() -> {
					// We use two nested try statements here so that the "finally" clause runs within the diagnostic scope
					try(
						@SuppressWarnings("unused") DiagnosticScope foo = diagnosticContext.withOnly(attributes)
					) {
						try (@SuppressWarnings("unused") ReadContext executionContext = new ReadContext(rootForHook)) {
							LOGGER.debug("Hook: RUN {}({})", reg.name, changedRef);
							reg.hook.onChanged(changedRef);
						} catch (Exception e) {
							LOGGER.error("Bosk hook \"{}\" terminated with an exception, which usually indicates a bug. State updates may have been lost", reg.name(), e);

							// Note that we don't catch Error. The practical reason is to allow users to write
							// unit tests that throw AssertionError from hooks, but the bigger reason is that
							// Errors indicate that something has gone dreadfully wrong, and we probably should
							// not attempt to continue.
						} finally {
							LOGGER.debug("Hook: end {}({})", reg.name, changedRef);
						}
					}
				});
			});
		}

		/**
		 * Runs queued hooks in a "breadth-first" fashion: all hooks "H" triggered by
		 * any single hook "G" will run before any consequent hooks triggered by "H".
		 *
		 * <p>
		 * The <a href="https://en.wikipedia.org/w/index.php?title=Breadth-first_search&oldid=1059916234#Pseudocode">classic BFS algorithm</a>
		 * has an outer loop that dequeues nodes for processing; however, we have an
		 * "inversion of control" situation here, where the bosk is not in control of
		 * the outermost loop.
		 *
		 * <p>
		 * Instead, we maintain a semaphore to distinguish "outermost calls" from
		 * "recursive calls", and dequeue nodes only at the outermost level, thereby
		 * effectively implementing the classic BFS algorithm despite not having access
		 * to the outermost loop of the application. The dequeuing is "allowed" only
		 * at the outermost level.
		 *
		 * <p>
		 * As a side-benefit, this also provides thread safety, as well as intuitive behaviour
		 * in the presence of parallelism.
		 *
		 * <p>
		 * Note: don't call while holding this object's monitor (ie. from a synchronized
		 * block). Running hooks means running arbitrary user code, which can take an
		 * arbitrary amount of time, and if the monitor is held, that blocks other
		 * threads from submitting updates.
		 */
		private void drainQueueIfAllowed() {
			do {
				if (hookExecutionPermit.tryAcquire()) {
					try {
						for (Runnable ex = hookExecutionQueue.pollFirst(); ex != null; ex = hookExecutionQueue.pollFirst()) {
							// Run the task in a separate virtual thread to prevent ThreadLocals from propagating.
							// This is slightly tragic, because usually ThreadLocal propagation works just the
							// way we'd want, but not always. Given the choices "always, sometimes, never", if
							// we can't achieve "always", then the bosk philosophy prefers "never" over "sometimes".
							hookExecutor.submit(ex).get();
						}
					} catch (ExecutionException e) {
						if (e.getCause() instanceof RuntimeException r) {
							throw r;
						} else if (e.getCause() instanceof Error error) {
							throw error;
						} else {
							throw new AssertionError("Hook runnable should catch and wrap checked exceptions", e);
						}
					} catch (InterruptedException e) {
						LOGGER.warn("Interrupted while running hooks", e);
						return;
					} finally {
						hookExecutionPermit.release();
					}
				} else {
					LOGGER.debug("Not draining the hook queue");
					return;
				}

				// The do-while loop here needs an explanation. At this location in the code,
				// we need to check again whether the queue is empty. Here's why.
				//
				// Events:
				//  - Q: Queue a hook
				//  - A: Acquire the permit
				//  - D: Drain the queue till it's empty
				//  - R: Release the permit
				//  - F: Try to acquire the permit and fail
				//
				// The two threads:
				//   This thread        Other thread
				//        Q
				//        A
				//        D
				//                         Q
				//                         F
				//        R
				//        * <-- (You are here)
				//
				// At this point, the queue may not be empty, yet this thread thinks it's drained,
				// and the other thread thinks we'll drain it.
				//
				// Fortunately, the solution is simple: just check again. If the queue is empty
				// at this point, we can safely stop running hooks, secure in the knowledge that
				// if another thread queues another hook after this point, that thread will also
				// succeed in acquiring the permit and will itself drain the queue.

			} while (!hookExecutionQueue.isEmpty());
		}

		@Override
		public String toString() {
			return "LocalDriver for " + Bosk.this;
		}
	}

	/**
	 * Causes the given {@link BoskHook} to be called when the given scope
	 * object is updated.
	 *
	 * <p>
	 * The <code>scope</code> reference can be parameterized.
	 * Upon any change to any matching node, or any parent or child of a matching node,
	 * the <code>action</code> will be called with a {@link ReadContext} that captures
	 * the state immediately after the update was applied.
	 * The <code>action</code> will receive an argument that is the <code>scope</code> reference
	 * with all its parameters (if any) bound.
	 *
	 * <p>
	 * For a given update, hooks are called in the order they were registered.
	 * Updates performed by the <code>action</code> could themselves trigger hooks.
	 * Such "hook cascades" are performed in breadth-first order, and are queued
	 * as necessary to achieve this; hooks are <em>not</em> called recursively.
	 * Hooks may be called on any thread, including one of the threads that
	 * submitted one of the updates, but they will be called in sequence, such
	 * that each <em>happens-before</em> the next.
	 *
	 * <p>
	 * Before returning, runs the hook on the current bosk state.
	 *
	 */
	public <T> void registerHook(String name, @NotNull Reference<T> scope, @NotNull BoskHook<T> action) {
		hookRegistrar.registerHook(name, scope, action);
	}

	/**
	 * The unadorned version of {@link #registerHook} that simply registers the hook as given.
	 */
	private <T> void localRegisterHook(String name, @NotNull Reference<T> scope, @NotNull BoskHook<T> action) {
		HookRegistration<T> reg = new HookRegistration<>(name, requireNonNull(scope), requireNonNull(action));
		hooks.add(reg);
		localDriver.triggerEverywhere(reg);
	}

	@Override
	public void registerHooks(Object receiver) throws InvalidTypeException {
		HookScanner.registerHooks(receiver, this);
	}

	@Override
	public Bosk<R> bosk() {
		return this;
	}

	public Collection<HookRegistration<?>> allRegisteredHooks() {
		return unmodifiableCollection(hooks);
	}

	@Value // Inner class can't be a record
	public class HookRegistration<S> {
		String name;
		Reference<S> scope;
		@Getter(NONE) BoskHook<S> hook;

		/**
		 * Calls <code>action</code> for every object whose path matches <code>scope</code> that
		 * was changed by a driver event targeting <code>target</code>.
		 *
		 * @param priorRoot The bosk root object before the driver event occurred
		 * @param newRoot The bosk root object after the driver event occurred
		 * @param target The object specified by the driver event
		 * @param action The operation to perform for each matching object that could have changed
		 */
		private void triggerAction(@Nullable R priorRoot, R newRoot, Reference<?> target, Consumer<Reference<S>> action) {
			Reference<S> effectiveScope;
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
	}

	/**
	 * Recursive helper routine that calls the given action for all objects matching <code>effectiveScope</code> that
	 * are different between <code>priorRoot</code> and <code>newRoot</code>.
	 * Each level of recursion fills in one parameter in <code>effectiveScope</code>;
	 * for the base case, this calls <code>action</code> unless the prior and current values are the same object.
	 *
	 * @param effectiveScope The hook scope with zero or more of its parameters filled in
	 * @param priorRoot The root before the change that triggered the hook; or null during initialization when running
	 *                  hooks on the {@link BoskDriver#initialRoot initial root}.
	 * @param newRoot The root after the change that triggered the hook. This will be the root in the {@link ReadContext}
	 *                during hook execution.
	 * @param action The operation to perform for each matching object that is different between the two roots
	 * @param <S> The type of the hook scope object
	 */
	private <S> void triggerCascade(Reference<S> effectiveScope, @Nullable R priorRoot, R newRoot, Consumer<Reference<S>> action) {
		if (effectiveScope.path().numParameters() == 0) {
			// effectiveScope points at a single node that may have changed
			//
			S priorValue = refValueIfExists(effectiveScope, priorRoot);
			S currentValue = refValueIfExists(effectiveScope, newRoot);
			if (priorValue == currentValue) { // Note object identity comparison
				LOGGER.debug("Hook: skip unchanged {}", effectiveScope);
			} else {
				// We've found something that changed
				action.accept(effectiveScope);
			}
		} else {
			try {
				// There's at least one parameter that hasn't been bound yet. This means
				// we need to locate all the matching objects that may have changed.
				// We do so by filling in the first parameter with all possible values that
				// could correspond to changed objects and then recursing.
				//
				Path containerPath = effectiveScope.path().truncatedTo(effectiveScope.path().firstParameterIndex());
				Reference<EnumerableByIdentifier<?>> containerRef = rootReference().then(enumerableByIdentifierClass(), containerPath);
				EnumerableByIdentifier<?> priorContainer = refValueIfExists(containerRef, priorRoot);
				EnumerableByIdentifier<?> newContainer = refValueIfExists(containerRef, newRoot);

				// TODO: If priorContainer == newContainer, can we stop immediately?

				// Process any deleted items first. This can allow the hook to free some memory
				// that can be used by subsequent hooks.
				// We do them in reverse order just because that's likely to be the preferred
				// order for cleanup activities.
				//
				// TODO: Should we actually process the hooks themselves in reverse order for the same reason?
				//
				if (priorContainer != null) {
					List<Identifier> priorIDs = priorContainer.ids();
					for (ListIterator<Identifier> iter = priorIDs.listIterator(priorIDs.size()); iter.hasPrevious(); ) {
						Identifier id = iter.previous();
						if (newContainer == null || newContainer.get(id) == null) {
							triggerCascade(effectiveScope.boundTo(id), priorRoot, newRoot, action);
						}
					}
				}

				// Then process updated items
				//
				if (newContainer != null) {
					for (Identifier id: newContainer.ids()) {
						if (priorContainer == null || priorContainer.get(id) != newContainer.get(id)) {
							triggerCascade(effectiveScope.boundTo(id), priorRoot, newRoot, action);
						}
					}
				}
			} catch (InvalidTypeException e) {
				// TODO: Add truncation methods to Reference so we can refactor this to create
				// the container reference without risking an InvalidTypeException
				throw new AssertionError("Parameterized reference must be truncatable at the location of the parameter", e);
			}
		}
	}

	@Nullable
	private <V> V refValueIfExists(Reference<V> containerRef, @Nullable R priorRoot) {
		if (priorRoot == null) {
			return null;
		} else {
			// TODO: This would be less cumbersome if we could apply a Reference to an arbitrary root object.
			// For now, References only apply to the current ReadContext, so we need a new ReadContext every time
			// we want to change roots.
			try (var __ = new ReadContext(priorRoot)) {
				return containerRef.valueIfExists();
			}
		}
	}

	/**
	 * A thread-local region in which {@link Reference#value()} works; outside
	 * of a {@link ReadContext}, {@link Reference#value()} will throw {@link
	 * IllegalStateException}.
	 *
	 * @author pdoyle
	 */
	public final class ReadContext implements AutoCloseable {
		final R originalRoot;
		final R snapshot; // Mostly for adopt()

		/**
		 * Creates a {@link ReadContext} for the current thread. If one is already
		 * active on this thread, the new nested one will be equivalent and has
		 * no effect.
		 */
		private ReadContext() {
			originalRoot = rootSnapshot.get();
			if (originalRoot == null) {
				snapshot = currentRoot;
				if (snapshot == null) {
					throw new IllegalStateException("Bosk constructor has not yet finished; cannot create a ReadContext");
				}
				rootSnapshot.set(snapshot);
				LOGGER.trace("New {}", this);
			} else {
				// Inner scopes use the same snapshot as outer scopes
				snapshot = originalRoot;
				LOGGER.trace("Nested {}", this);
			}
		}

		private ReadContext(ReadContext toAdopt) {
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
				throw new IllegalStateException("Read scope for " + name + " already active in " + Thread.currentThread());
			}
		}

		/**
		 * Internal constructor to use a given root.
		 *
		 * <p>
		 * Unlike the other constructors, this can be used to substitute a new root temporarily,
		 * even if there's already one active on the current thread.
		 */
		ReadContext(@NotNull R root) {
			originalRoot = rootSnapshot.get();
			snapshot = requireNonNull(root);
			rootSnapshot.set(snapshot);
			LOGGER.trace("Using {}", this);
		}

		/**
		 * Creates a {@link ReadContext} for the current thread, inheriting state
		 * from another thread.
		 * Any calls to {@link Reference#value()} on the current thread will return
		 * the same value they would have returned on the thread where
		 * <code>this</code> context was created.
		 *
		 * <p>
		 * Because nested scopes behave like their outer scope, you can always
		 * make another ReadContext at any time on some thread in order to
		 * "capture" whatever scope may be in effect on that thread (or to
		 * create a new one if there is no active scope on that thread).
		 *
		 * <p>
		 * Hence, a recommended idiom for scope inheritance looks like this:
		 *
		 * <blockquote><pre>
try (ReadContext originalThReadContext = bosk.readContext()) {
	workQueue.submit(() -> {
		try (ReadContext workerThReadContext = bosk.adopt(originalThReadContext)) {
			// Code in here can read from the bosk just like the original thread.
		}
	});
}
		 * </pre></blockquote>
		 *
		 * Note, though, that this will prevent the garbage collector from
		 * collecting the ReadContext's state snapshot until the worker thread's
		 * scope is finished. Therefore, if the worker thread is to continue running
		 * after the original thread would have exited its own scope,
		 * then use this idiom only if the worker thread must see
		 * the same state snapshot as the original thread <em>and</em> you're
		 * willing to prevent that snapshot from being garbage-collected until
		 * the worker thread finishes.
		 *
		 * @return a <code>ReadContext</code> representing the new context.
		 */
		public ReadContext adopt() {
			return new ReadContext(this);
		}

		@Override
		public void close() {
			// TODO: Enforce the closing rules described in readContext javadocs?
			LOGGER.trace("Exiting {}; restoring {}", this, System.identityHashCode(originalRoot));
			rootSnapshot.set(originalRoot);
		}

		@Override
		public String toString() {
			return "ReadContext(" + System.identityHashCode(snapshot) + ")";
		}
	}

	/**
	 * Establishes a {@link ReadContext} for the calling thread,
	 * allowing {@link Reference#value()} to return values from this bosk's state tree,
	 * from a snapshot taken at the moment this method was called.
	 * The snapshot is held stable until the returned context is {@link ReadContext#close() closed}.
	 *
	 * <p>
	 * If the calling thread has an active read context already,
	 * the returned <code>ReadContext</code> has no effect:
	 * the state snapshot from the existing context will continue to be used on the calling thread
	 * until both contexts (the returned one and the existing one) are closed.
	 *
	 * <p>
	 * <code>ReadContext</code>s must be closed on the same thread on which they were opened,
	 * and must be closed in reverse order.
	 * We recommend using them in <i>try-with-resources</i> statements;
	 * otherwise, you could end up with some read contexts ending prematurely,
	 * and others persisting for the remainder of the thread's lifetime.
	 */
	public final ReadContext readContext() {
		return new ReadContext();
	}

	/**
	 * Establishes a new {@link ReadContext} for the calling thread, similar to {@link #readContext()}, except that
	 * if the calling thread already has a context, it will be ignored,
	 * and the newly created context will have a fresh snapshot of the bosk's state tree;
	 * then, when the returned context is {@link ReadContext#close closed},
	 * the previous context will be restored.
	 * <p>
	 * This is intended to support coordination of distributed logic among multiple threads (or servers) using the same bosk.
	 * Threads can submit an update, call {@link BoskDriver#flush}, and then use this method
	 * to inspect the bosk state and determine what effect the update had.
	 * <p>
	 * Use this method when it's important to observe the bosk state after a {@link BoskDriver#flush flush}
	 * performed by the same thread.
	 * When in doubt, you probably want {@link #readContext()} instead of this.
	 * This method opens the possibility that the same thread can see two different revisions of the bosk state,
	 * which can lead to confusing bugs in application code.
	 * In addition, when the returned context is {@link ReadContext#close closed},
	 * the bosk state can appear to revert to a prior state, which can be confusing.
	 *
	 * @see #readContext()
	 */
	public final ReadContext supersedingReadContext() {
		R snapshot = currentRoot;
		if (snapshot == null) {
			throw new IllegalStateException("Bosk constructor has not yet finished; cannot create a ReadContext");
		}
		return new ReadContext(snapshot);
	}

	/**
	 * A path is "vetted" if we've already called {@link #pathCompiler}.{@link PathCompiler#targetTypeOf} on it.
	 */
	private Dereferencer compileVettedPath(Path path) {
		try {
			return pathCompiler.compiled(path);
		} catch (InvalidTypeException e) {
			throw new AssertionError("Compiling a vetted path should not throw InvalidTypeException: " + path, e);
		}
	}

	final class RootRef extends DefiniteReference<R> implements RootReference<R> {
		public RootRef(Type targetType) {
			super(Path.empty(), targetType);
		}

		Bosk<?> bosk() { return Bosk.this; }

		@Override
		public <U> Reference<U> then(Class<U> requestedClass, Path path) throws InvalidTypeException {
			Type targetType;
			try {
				targetType = pathCompiler.targetTypeOf(path);
			} catch (InvalidTypeException e) {
				throw new InvalidTypeException("Invalid path from " + targetClass().getSimpleName() + ": " + path, e);
			}
			Class<?> targetClass = rawClass(targetType);
			if (Optional.class.isAssignableFrom(requestedClass)) {
				throw new InvalidTypeException("Reference<Optional<T>> not supported; create a Reference<T> instead and use Reference.optionalValue()");
			} else if (!requestedClass.isAssignableFrom(targetClass)) {
				throw new InvalidTypeException("Path from " + targetClass().getSimpleName()
					+ " returns " + targetClass.getSimpleName()
					+ ", not " + requestedClass.getSimpleName()
					+ ": " + path);
			} else if (Reference.class.isAssignableFrom(requestedClass)) {
				// TODO: Disallow references to implicit references {Self and Enclosing}
			}
			return newReference(path, targetType);
		}

		@Override
		public <E extends Entity> CatalogReference<E> thenCatalog(Class<E> entryClass, Path path) throws InvalidTypeException {
			Reference<Catalog<E>> ref = this.then(Classes.catalog(entryClass), path);
			return new CatalogRef<>(ref, entryClass);
		}

		@Override
		public <E extends Entity> ListingReference<E> thenListing(Class<E> entryClass, Path path) throws InvalidTypeException {
			Reference<Listing<E>> ref = this.then(Classes.listing(entryClass), path);
			return new ListingRef<>(ref);
		}

		@Override
		public <K extends Entity, V> SideTableReference<K, V> thenSideTable(Class<K> keyClass, Class<V> valueClass, Path path) throws InvalidTypeException {
			Reference<SideTable<K,V>> ref = this.then(Classes.sideTable(keyClass, valueClass), path);
			return new SideTableRef<>(ref, keyClass, valueClass);
		}

		@Override
		public <TT> Reference<Reference<TT>> thenReference(Class<TT> targetClass, Path path) throws InvalidTypeException {
			return this.then(Classes.reference(targetClass), path);
		}

		@Override
		public <TT extends VariantCase> Reference<TaggedUnion<TT>> thenTaggedUnion(Class<TT> variantCaseClass, Path path) throws InvalidTypeException {
			return this.then(Classes.taggedUnion(variantCaseClass), path);
		}

		@Override
		public <T> T buildReferences(Class<T> refsClass) throws InvalidTypeException {
			return ReferenceBuilder.buildReferences(refsClass, Bosk.this);
		}
	}

	@Getter
	@RequiredArgsConstructor
	sealed abstract class ReferenceImpl<T> implements Reference<T> {
		protected final Path path;
		protected final Type targetType;

		@Override
		@SuppressWarnings("unchecked")
		public final Class<T> targetClass() {
			return (Class<T>)rawClass(targetType());
		}

		@Override
		public final Reference<T> boundBy(BindingEnvironment bindings) {
			return newReference(path.boundBy(bindings), targetType);
		}

		@Override
		public RootReference<?> root() {
			return rootReference();
		}

		@Override
		public final <U> Reference<U> then(Class<U> targetClass, String... segments) throws InvalidTypeException {
			return rootReference().then(targetClass, path.then(segments));
		}

		@Override
		public final <U extends Entity> CatalogReference<U> thenCatalog(Class<U> entryClass, String... segments) throws InvalidTypeException {
			return rootReference().thenCatalog(entryClass, path.then(segments));
		}

		@Override
		public final <U extends Entity> ListingReference<U> thenListing(Class<U> entryClass, String... segments) throws InvalidTypeException {
			return rootReference().thenListing(entryClass, path.then(segments));
		}

		@Override
		public final <K extends Entity, V> SideTableReference<K, V> thenSideTable(Class<K> keyClass, Class<V> valueClass, String... segments) throws InvalidTypeException {
			return rootReference().thenSideTable(keyClass, valueClass, path.then(segments));
		}

		@Override
		public final <TT> Reference<Reference<TT>> thenReference(Class<TT> targetClass, String... segments) throws InvalidTypeException {
			return rootReference().thenReference(targetClass, path.then(segments));
		}

		@Override
		public <TT extends VariantCase> Reference<TaggedUnion<TT>> thenTaggedUnion(Class<TT> variantCaseClass, String... segments) throws InvalidTypeException {
			return rootReference().thenTaggedUnion(variantCaseClass, path.then(segments));
		}

		@SuppressWarnings("unchecked")
		@Override
		public final <TT> Reference<TT> enclosingReference(Class<TT> targetClass) {
			if (path.isEmpty()) {
				throw new IllegalArgumentException("Root reference has no enclosing references");
			}
			for (Path p = this.path.truncatedBy(1); !p.isEmpty(); p = p.truncatedBy(1)) try {
				Type targetType = pathCompiler.targetTypeOf(p);
				if (targetClass.isAssignableFrom(rawClass(targetType))) {
					return rootReference().then(targetClass, p);
				}
			} catch (InvalidTypeException e) {
				throw new IllegalArgumentException("Error looking up enclosing " + targetClass.getSimpleName() + " from " + path);
			}
			// Might be the root
			if (targetClass.isAssignableFrom(rootRef.targetClass())) {
				return (Reference<TT>) rootReference();
			} else {
				throw new IllegalArgumentException("No enclosing " + targetClass.getSimpleName() + " from " + path);
			}
		}

		@Override
		public final int hashCode() {
			return Objects.hash(rootType(), path);
		}

		@Override
		public final boolean equals(Object obj) {
			if (this == obj) {
				return true;
			}
			if (obj == null) {
				return false;
			}
			if (!(obj instanceof Reference<?> other)) {
				return false;
			}

			// Two references are equal if they have the same root type and path.
			// Note that they are not required to come from the same Bosk.
			// That means we can compare references from one Bosk to the other
			// if they both have the same root type.

			return Objects.equals(this.rootType(), other.root().targetType())
				&& Objects.equals(path, other.path());
		}

		private Type rootType() {
			return Bosk.this.rootRef.targetType;
		}

		@Override
		public final String toString() {
			return path.toString();
		}

	}

	/**
	 * A {@link Reference} with no unbound parameters.
	 */
	private sealed class DefiniteReference<T> extends ReferenceImpl<T> {
		private final Dereferencer dereferencer = compileVettedPath(path);

		public DefiniteReference(Path path, Type targetType) {
			super(path, targetType);
			assert path.numParameters() == 0;
		}

		@Override
		@SuppressWarnings("unchecked")
		public T valueIfExists() {
			R snapshot = rootSnapshot.get();
			LOGGER.trace("Snapshot is {}", System.identityHashCode(snapshot));
			if (snapshot == null) {
				throw new NoReadContextException("No active read context for " + name + " in " + Thread.currentThread());
			} else try {
				return (T) dereferencer().get(snapshot, this);
			} catch (NonexistentEntryException e) {
				return null;
			}
		}

		@Override
		public void forEachValue(BiConsumer<T, BindingEnvironment> action, BindingEnvironment existingEnvironment) {
			T value = valueIfExists();
			if (value != null) {
				action.accept(value, existingEnvironment);
			}
		}

		public Dereferencer dereferencer() {
			return this.dereferencer;
		}
	}

	/**
	 * A {@link Reference} with at least one unbound parameter.
	 * All parameters must be bound before the Reference can be used for {@link #value()} etc.
	 *
	 * <p>
	 * It is an error to have a parameter in a position that does not
	 * correspond to an {@link Identifier} that can be looked up in an
	 * object that implements {@link EnumerableByIdentifier}. (We are
	 * not offering to use reflection to look up object fields by name here.)
	 * <p>
	 * TODO: This is not currently checked or enforced; it will just cause confusing crashes.
	 * It should throw {@link InvalidTypeException} at the time the Reference is created.
	 */
	private final class IndefiniteReference<T> extends ReferenceImpl<T> {
		public IndefiniteReference(Path path, Type targetType) {
			super(path, targetType);
			assert path.numParameters() >= 1;
		}

		@Override
		public T valueIfExists() {
			throw new ReferenceBindingException("Reference has unbound parameters: " + this);
		}

		@Override
		public void forEachValue(BiConsumer<T, BindingEnvironment> action, BindingEnvironment existingEnvironment) {
			int firstParameterIndex = path.firstParameterIndex();
			String parameterName = parameterNameFromSegment(path.segment(firstParameterIndex));
			Path containerPath = path.truncatedTo(firstParameterIndex);
			Reference<EnumerableByIdentifier<?>> containerRef;
			try {
				containerRef = rootReference().then(enumerableByIdentifierClass(), containerPath);
			} catch (InvalidTypeException e) {
				throw new AssertionError("Parameter reference must come after a " + EnumerableByIdentifier.class, e);
			}
			EnumerableByIdentifier<?> container = containerRef.valueIfExists();
			if (container != null) {
				container.ids().forEach(id ->
					this.boundTo(id).forEachValue(action,
						existingEnvironment.builder()
							.bind(parameterName, id)
							.build()
					));
			}
		}
	}

	private <T> Reference<T> newReference(Path path, Type targetType) {
		if (path.numParameters() == 0) {
			return new DefiniteReference<>(path, targetType);
		} else {
			return new IndefiniteReference<>(path, targetType);
		}
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
	@Getter
	public static final class NonexistentEntryException extends Exception {
		final Path path;

		public NonexistentEntryException(Path path) {
			super("No object at path \"" + path.toString() + "\"");
			this.path = path;
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
	 * FOR UNIT TESTING
	 */
	final R currentRoot() {
		return currentRoot;
	}

	@SuppressWarnings({"unchecked","rawtypes"})
	private static Class<EnumerableByIdentifier<?>> enumerableByIdentifierClass() {
		return (Class) EnumerableByIdentifier.class;
	}
	private static final Logger LOGGER = LoggerFactory.getLogger(Bosk.class);
}
