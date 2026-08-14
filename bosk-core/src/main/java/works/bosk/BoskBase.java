package works.bosk;

import java.io.IOException;
import java.lang.reflect.Type;
import java.util.Deque;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import works.bosk.BoskContext.Context;
import works.bosk.ReferenceUtils.CatalogRef;
import works.bosk.ReferenceUtils.ListingRef;
import works.bosk.ReferenceUtils.SideTableRef;
import works.bosk.dereferencers.Dereferencer;
import works.bosk.dereferencers.PathCompiler;
import works.bosk.exceptions.InvalidTypeException;
import works.bosk.exceptions.NoReadSessionException;
import works.bosk.exceptions.ReferenceBindingException;
import works.bosk.util.Classes;

import static java.lang.Thread.holdsLock;
import static java.util.Objects.requireNonNull;
import static java.util.UUID.randomUUID;
import static works.bosk.Path.parameterNameFromSegment;
import static works.bosk.ReferenceUtils.rawClass;
import static works.bosk.TypeValidation.validateType;
import static works.bosk.logging.MappedDiagnosticContext.setupMDC;

/**
 * The implementation of a {@link Bosk}, held in a superclass so that its final
 * fields are all frozen when this constructor returns. The {@link Bosk} subclass
 * constructor body therefore runs once the bosk's final fields are reliably
 * initialized, even though the {@link Bosk} constructor has not yet returned.
 */
abstract sealed class BoskBase<R extends StateTreeNode> permits Bosk {
	final String name;
	final Identifier instanceID = Identifier.from(randomUUID().toString());
	final BoskContext context;

	final IngressDriver ingressDriver;
	final LocalDriver localDriver;
	final RootRef rootRef;
	final ThreadLocal<R> rootSnapshot = new ThreadLocal<>();
	final HookRegistrar hookRegistrar;
	final Queue<Bosk<R>.HookRegistration<?>> hooks = new ConcurrentLinkedQueue<>();
	final PathCompiler pathCompiler;

	final Thread.Builder hookThreadBuilder = Thread
		.ofVirtual()
		.name("bosk-hook-", 1);

	/**
	 * Set once the bosk is fully initialized, so that {@link BoskInfo#bosk()}
	 * can report readiness; see {@link BoskInfo#bosk()}.
	 */
	final AtomicReference<Bosk<R>> boskRef = new AtomicReference<>();

	/**
	 * Mutable state.
	 * This is null before the constructor finishes.
	 */
	@Nullable volatile R currentState;

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
	@SuppressWarnings("this-escape")
	protected BoskBase(String name, Type rootType, Bosk.DefaultStateFunction<R> defaultStateFunction, BoskConfig<R> boskConfig) {
		this.name = requireNonNull(name);
		this.pathCompiler = PathCompiler.withSourceType(requireNonNull(rootType)); // Required before rootRef
		this.localDriver = new LocalDriver(requireNonNull(defaultStateFunction));
		this.rootRef = new RootRef(rootType);
		try {
			validateType(rootType);
		} catch (InvalidTypeException e) {
			throw new IllegalArgumentException("Invalid root type " + rootType + ": " + e.getMessage(), e);
		}

		context = new BoskContext(Context::empty);
		Info<R> boskInfo = new Info<>(name, instanceID, rootRef, context, boskRef);

		// We do this as late as possible because the driver factory is allowed
		// to do such things as create References, so it needs the rest of the
		// initialization to have completed already.
		//
		this.ingressDriver = new IngressDriver(requireNonNull(boskConfig.driverFactory().build(boskInfo, this.localDriver)));
		this.hookRegistrar = requireNonNull(boskConfig.registrarFactory().build(boskInfo, this::localRegisterHook));

		try {
			this.currentState = ingressDriver.initialState(rootRef.targetClass());
		} catch (InvalidTypeException | IOException | InterruptedException e) {
			throw new IllegalArgumentException("Error computing initial state: " + e.getMessage(), e);
		}

		// Ok, we're done initializing. The cast is safe because BoskBase permits only Bosk.
		boskInfo.boskRef().set((Bosk<R>) this); // @SuppressWarnings("this-escape")
	}

	record Info<RR extends StateTreeNode>(
		String name,
		Identifier instanceID,
		RootReference<RR> rootReference,
		BoskContext context,
		AtomicReference<Bosk<RR>> boskRef
	) implements BoskInfo<RR> {
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

	/**
	 * We wrap the user-supplied driver with one of these so we're in control
	 * of the incoming driver operations.
	 */
	final class IngressDriver implements BoskDriver {
		final BoskDriver downstream;

		public IngressDriver(BoskDriver downstream) {
			this.downstream = downstream;
		}

		@Override
		public <T> void submitReplacement(Reference<T> target, T newValue) {
			try (var _ = setupMDC(name, instanceID)) {
				assertCorrectBosk(target);
				downstream.submitReplacement(target, newValue);
			}
		}

		@Override
		public <T> void submitConditionalReplacement(Reference<T> target, T newValue, Reference<Identifier> precondition, Identifier requiredValue) {
			try (var _ = setupMDC(name, instanceID)) {
				assertCorrectBosk(target);
				assertCorrectBosk(precondition);
				downstream.submitConditionalReplacement(target, newValue, precondition, requiredValue);
			}
		}

		@Override
		public <T> void submitConditionalCreation(Reference<T> target, T newValue) {
			try (var _ = setupMDC(name, instanceID)) {
				assertCorrectBosk(target);
				downstream.submitConditionalCreation(target, newValue);
			}
		}

		@Override
		public <T> void submitDeletion(Reference<T> target) {
			try (var _ = setupMDC(name, instanceID)) {
				if (target.isRoot()) {
					// TODO: Augment dereferencer so it can tell us this for all references, not just the root
					throw new IllegalArgumentException("Cannot delete root object");
				}
				assertCorrectBosk(target);
				downstream.submitDeletion(target);
			}
		}

		@Override
		public <T> void submitConditionalDeletion(Reference<T> target, Reference<Identifier> precondition, Identifier requiredValue) {
			try (var _ = setupMDC(name, instanceID)) {
				assertCorrectBosk(target);
				assertCorrectBosk(precondition);
				downstream.submitConditionalDeletion(target, precondition, requiredValue);
			}
		}

		@Override
		public <RR extends StateTreeNode> RR initialState(Class<RR> rootType) throws InvalidTypeException, IOException, InterruptedException {
			try (var _ = setupMDC(name, instanceID)) {
				return rootType.cast(rootRef.targetClass().cast(requireNonNull(downstream.initialState(rootType))));
			}
		}

		@Override
		public void flush() throws IOException, InterruptedException {
			try (var _ = setupMDC(name, instanceID)) {
				downstream.flush();
			}
		}

		private <T> void assertCorrectBosk(Reference<T> target) {
			// TODO: Do we need to be this strict?
			// On the one hand, we could write conditional updates in a way that don't require the
			// reference to point to the right bosk.
			// On the other hand, there's a certain symmetry to requiring the references to have the right
			// bosk for both reads and writes, and forcing this discipline on users might help them avoid
			// some pretty confusing mistakes.
			assert ((BoskBase<?>.RootRef) target.root()).bosk() == BoskBase.this : "Reference supplied to driver operation must refer to the correct bosk";
		}

	}

	/**
	 * {@link BoskDriver} that writes directly to this {@link Bosk}.
	 * Always implicitly the last driver on the stack.
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
	 * <p>
	 * Satisfying all of these simultaneously is tricky, especially because we can't just put
	 * "synchronized" on the submit methods because that could cause deadlock. We also don't
	 * want to require a background thread for hook processing, partly on principle: if our
	 * execution model is so complex that it requires a background thread just to make updates
	 * to objects in memory, it feels like we've taken a step in the wrong direction.
	 *
	 * @author pdoyle
	 * @see #drainQueueIfAllowed() for algorithm details
	 */
	private final class LocalDriver implements BoskDriver {
		final Bosk.DefaultStateFunction<R> initialStateFunction;
		final Deque<Runnable> hookExecutionQueue = new ConcurrentLinkedDeque<>();
		final Semaphore hookExecutionPermit = new Semaphore(1);

		public LocalDriver(Bosk.DefaultStateFunction<R> initialStateFunction) {
			this.initialStateFunction = initialStateFunction;
		}

		@Override
		public <RR extends StateTreeNode> RR initialState(Class<RR> rootType) throws InvalidTypeException, IOException, InterruptedException {
			return rootType.cast(requireNonNull(initialStateFunction.apply((Bosk<R>) BoskBase.this)));
		}

		@Override
		public <T> void submitReplacement(Reference<T> target, T newValue) {
			synchronized (this) {
				R priorRoot = currentRoot();
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
				try (var _ = newSupersedingReadSession()) {
					preconditionsSatisfied = !target.exists();
				}
				if (preconditionsSatisfied) {
					R priorRoot = currentRoot();
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
				R priorRoot = currentRoot();
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
				try (var _ = newSupersedingReadSession()) {
					preconditionsSatisfied = Objects.equals(precondition.valueIfExists(), requiredValue);
				}
				if (preconditionsSatisfied) {
					R priorRoot = currentRoot();
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
				try (var _ = newSupersedingReadSession()) {
					preconditionsSatisfied = Objects.equals(precondition.valueIfExists(), requiredValue);
				}
				if (preconditionsSatisfied) {
					R priorRoot = currentRoot();
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
		void triggerEverywhere(Bosk<R>.HookRegistration<?> reg) {
			synchronized (this) {
				triggerQueueingOfHooks(rootRef, null, currentState, reg);
			}
			drainQueueIfAllowed();
		}

		/**
		 * @return false if the update was ignored
		 */
		private <T> boolean tryGraftReplacement(Reference<T> target, T newValue) {
			assert holdsLock(this);
			Path targetPath = target.path();
			if (targetPath.isEmpty()) {
				currentState = rootRef.targetClass().cast(newValue);
				return true;
			}
			Dereferencer dereferencer = dereferencerFor(target);
			try {
				LOGGER.debug("Applying replacement at {}", target);
				R oldRoot = currentRoot();
				if (oldRoot == null) {
					LOGGER.debug("Ignoring replacement of {}: root does not exist", target);
					return false;
				}
				R newRoot = rootRef.targetClass().cast(requireNonNull(dereferencer.with(oldRoot, target, requireNonNull(newValue))));
				currentState = newRoot;
				if (LOGGER.isTraceEnabled()) {
					LOGGER.trace("Replacement at {} changed root from {} to {}",
						target,
						System.identityHashCode(oldRoot),
						System.identityHashCode(newRoot));
				}
				return true;
			} catch (Bosk.NonexistentEntryException e) {
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
			if (targetPath.isEmpty()) {
				throw new IllegalArgumentException("Cannot delete root node");
			}
			Dereferencer dereferencer = dereferencerFor(target);
			try {
				LOGGER.debug("Applying deletion at {}", target);
				R oldRoot = currentRoot();
				if (oldRoot == null) {
					LOGGER.debug("Ignoring deletion of {}: root does not exist", target);
					return false;
				}
				R newRoot = rootRef.targetClass().cast(dereferencer.without(oldRoot, target));
				currentState = newRoot;
				if (LOGGER.isTraceEnabled()) {
					LOGGER.trace("Deletion at {} changed root from {} to {}",
						target,
						System.identityHashCode(oldRoot),
						System.identityHashCode(newRoot));
				}
				return true;
			} catch (Bosk.NonexistentEntryException e) {
				LOGGER.debug("Ignoring deletion of {}", target, e);
				return false;
			}
		}

		private Dereferencer dereferencerFor(Reference<?> ref) {
			// We could just pull it out of ref, if it's a ReferenceImpl, but we can't assume that
			return compileVettedPath(ref.path());
		}

		private <T> void queueHooks(Reference<T> target, @Nullable R priorRoot) {
			R rootForHook = currentRoot();
			for (Bosk<R>.HookRegistration<?> reg : hooks) {
				triggerQueueingOfHooks(target, priorRoot, rootForHook, reg);
			}
		}

		/**
		 * For a given {@link Bosk.HookRegistration}, queues up a call to {@link BoskHook#onChanged}
		 * for each matching object that changed between <code>priorRoot</code> and <code>rootForHook</code>
		 * when <code>target</code> was updated. If <code>priorRoot</code> is null, the hook is called
		 * on every matching object that exists in <code>rootForHook</code>.
		 */
		private <T, S> void triggerQueueingOfHooks(Reference<T> target, @Nullable R priorRoot, R rootForHook, Bosk<R>.HookRegistration<S> reg) {
			MapValue<String> attributes = context.getAttributes();
			reg.triggerAction(priorRoot, rootForHook, target, changedRef -> {
				HOOK_LOGGER.debug("Hook: queue {}({}) due to {}", reg.name(), changedRef, target);
				hookExecutionQueue.addLast(() -> {
					// We use two nested try statements here so that the "finally" clause runs within the diagnostic scope
					try (
						var _ = setupMDC(name, instanceID);
						var _ = context.withOnly(attributes)
					) {
						try (var _ = newReadSession(rootForHook)) {
							HOOK_LOGGER.debug("Hook: RUN {}({})", reg.name(), changedRef);
							reg.hook().onChanged(changedRef);
						} catch (InterruptedException e) {
							HOOK_LOGGER.warn("Bosk hook \"{}\" was interrupted; proceeding", reg.name(), e);
						} catch (RuntimeException e) {
							HOOK_LOGGER.error("Bosk hook \"{}\" terminated with an exception, which usually indicates a bug. State updates may have been lost", reg.name(), e);

							// Note that we don't catch Error. The practical reason is to allow users to write
							// unit tests that throw AssertionError from hooks, but the bigger reason is that
							// Errors indicate that something has gone dreadfully wrong, and we probably should
							// not attempt to continue.
						} finally {
							HOOK_LOGGER.debug("Hook: end {}({})", reg.name(), changedRef);
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
						while (true) {
							// An interrupt means "stop"; quit before starting another hook,
							// leaving the remaining queued hooks for a later update.
							if (Thread.currentThread().isInterrupted()) {
								HOOK_LOGGER.debug("Interrupted; deferring the remaining queued hooks");
								return;
							}
							Runnable ex = hookExecutionQueue.pollFirst();
							if (ex == null) {
								break;
							}
							// Run the task in a separate virtual thread to prevent ThreadLocals from propagating.
							// This is slightly tragic, because usually ThreadLocal propagation works just the
							// way we'd want, but not always. Given the choices "always, sometimes, never", if
							// we can't achieve "always", then the bosk philosophy prefers "never" over "sometimes".
							FutureTask<Void> task = new FutureTask<>(ex, null);
							Thread hookThread = hookThreadBuilder.start(task);
							try {
								task.get();
							} catch (ExecutionException e) {
								try {
									throw e.getCause();
								} catch (RuntimeException | Error cause) {
									throw cause;
								} catch (Throwable t) {
									throw new AssertionError("Hook runnable should catch and wrap checked exceptions", t);
								}
							} catch (InterruptedException e) {
								// The interrupt is intended for the hook work in flight here.
								// Deliver it to the running hook, per the BoskHook contract,
								// and await its termination before proceeding. This is intended
								// to mimic structured concurrency (StructuredTaskScope.close()):
								// cancel the in-flight subtasks and wait for them to terminate.
								hookThread.interrupt();
								awaitTermination(hookThread);
								Thread.currentThread().interrupt();
								HOOK_LOGGER.warn("Interrupted while running hooks; the running hook was interrupted and terminated, and the remaining queued hooks are deferred to the next update", e);
								return;
							}
						}
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

		/**
		 * Wait for the given hook thread to terminate. If this (the draining) thread is
		 * interrupted while waiting, keep waiting: the interrupt has already been delivered
		 * to the hook, and proceeding without it would leave the hook running orphaned.
		 */
		private void awaitTermination(Thread hookThread) {
			while (true) {
				try {
					hookThread.join();
					return;
				} catch (InterruptedException e) {
					// Keep waiting; the interrupt has been delivered to the hook already.
				}
			}
		}

		@Override
		public String toString() {
			return "LocalDriver for " + BoskBase.this;
		}
	}

	/**
	 * The unadorned version of {@code hookRegistrar().}{@link HookRegistrar#registerHook(String, Reference, BoskHook) registerHook}
	 * that simply registers the hook as given.
	 */
	final <T> void localRegisterHook(String name, @NonNull Reference<T> scope, @NonNull BoskHook<T> action) {
		// The cast is safe because BoskBase permits only Bosk as a subclass, and hook
		// registration happens after construction.
		Bosk<R>.HookRegistration<T> reg = ((Bosk<R>) BoskBase.this).new HookRegistration<>(name, requireNonNull(scope), requireNonNull(action));
		hooks.add(reg);
		localDriver.triggerEverywhere(reg);
	}

	/**
	 * Recursive helper routine that calls the given action for all objects matching <code>effectiveScope</code> that
	 * are different between <code>priorRoot</code> and <code>newRoot</code>.
	 * Each level of recursion fills in one parameter in <code>effectiveScope</code>;
	 * for the base case, this calls <code>action</code> unless the prior and current values are the same object.
	 *
	 * @param effectiveScope The hook scope with zero or more of its parameters filled in
	 * @param priorRoot      The root before the change that triggered the hook; or null during initialization when running
	 *                       hooks on the {@link BoskDriver#initialState initial state}.
	 * @param newRoot        The root after the change that triggered the hook. This will be the root in the {@link Bosk.ReadSession}
	 *                       during hook execution.
	 * @param action         The operation to perform for each matching object that is different between the two roots
	 * @param <S>            The type of the hook scope object
	 */
	final <S> void triggerCascade(Reference<S> effectiveScope, @Nullable R priorRoot, R newRoot, Consumer<Reference<S>> action) {
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
			// There's at least one parameter that hasn't been bound yet. This means
			// we need to locate all the matching objects that may have changed.
			// We do so by filling in the first parameter with all possible values that
			// could correspond to changed objects and then recursing.
			//
			Reference<EnumerableByIdentifier<?>> containerRef = effectiveScope.truncatedBeforeFirstParameter();
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
				for (Identifier id : priorIDs.reversed()) {
					if (newContainer == null || newContainer.get(id) == null) {
						triggerCascade(effectiveScope.boundTo(id), priorRoot, newRoot, action);
					}
				}
			}

			// Then process updated items
			//
			if (newContainer != null) {
				for (Identifier id : newContainer.ids()) {
					if (priorContainer == null || priorContainer.get(id) != newContainer.get(id)) {
						triggerCascade(effectiveScope.boundTo(id), priorRoot, newRoot, action);
					}
				}
			}
		}
	}

	@Nullable
	final <V> V refValueIfExists(Reference<V> containerRef, @Nullable R root) {
		if (root == null) {
			return null;
		} else {
			// TODO: This would be less cumbersome if we could apply a Reference to an arbitrary root object.
			// For now, References only apply to the current ReadSession, so we need a new ReadSession every time
			// we want to change roots.
			try (var _ = newReadSession(root)) {
				return containerRef.valueIfExists();
			}
		}
	}

	/**
	 * A path is "vetted" if we've already called {@link #pathCompiler}.{@link PathCompiler#targetTypeOf} on it.
	 */
	final Dereferencer compileVettedPath(Path path) {
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

		BoskBase<?> bosk() {
			return BoskBase.this;
		}

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
			Reference<SideTable<K, V>> ref = this.then(Classes.sideTable(keyClass, valueClass), path);
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

		/**
		 * Build a runtime implementation of a "Refs" interface that provides typed
		 * accessor methods for {@link Reference} objects based on the given <code>bosk</code>.
		 * <p>
		 * Each method of <code>refsClass</code> must be annotated with {@link works.bosk.annotations.ReferencePath}
		 * and must return a subtype of {@link Reference}.
		 * <p>
		 * The path string in {@link works.bosk.annotations.ReferencePath} may contain parameter placeholders (e.g. <code>-id-</code>).
		 * Any parameters in the method signature are used to bind those placeholders
		 * in the order they appear.
		 * There can be one or more {@link Identifier} values to bind individual parameters,
		 * optionally followed by a {@link BindingEnvironment}, {@link Identifier} array,
		 * or {@link Identifier} varargs, to bind any remaining parameters.
		 * The path may contain more placeholders than can be bound by the method parameters,
		 * in which case the returned {@link Reference} will still have unbound parameters.
		 * <p>
		 * An example {@code Refs} interface:
		 * <pre>{@code
		 * public interface Refs {
		 *     // A specialized Catalog reference
		 *     @ReferencePath("/widgets")
		 *     CatalogReference<Widget> widgets();
		 *
		 *     // A parameterized reference
		 *     @ReferencePath("/widgets/-widget-")
		 *     Reference<Widget> widget(Identifier widgetId);
		 *
		 *     // Zero or more of the IDs can be bound.
		 *     // The resulting reference will have unbound parameters if not all are provided.
		 *     @ReferencePath("/users/-user-/widgets/-widget-")
		 *     Reference<UserPref> userWidget(Identifier... ids);
		 *
		 *     // Zero or more of the IDs can be bound by name.
		 *     @ReferencePath("/users/-user-/widgets/-widget-")
		 *     Reference<UserPref> userWidget(BindingEnvironment env);
		 * }</pre>
		 *
		 * @param refsClass interface describing desired reference-accessor methods
		 * @param <T> the type of {@code refsClass}
		 * @return an implementation of <code>refsClass</code> based on this bosk
		 * @throws InvalidTypeException if the interface is missing annotations,
		 * methods return unexpected types, or method parameters use unsupported types
		 */
		@Override
		public <T> T buildReferences(Class<T> refsClass) throws InvalidTypeException {
			return ReferenceBuilder.buildReferences(refsClass, (Bosk<R>) BoskBase.this);
		}
	}

	sealed abstract class ReferenceImpl<T> implements Reference<T> {
		protected final Path path;
		protected final Type targetType;

		public ReferenceImpl(Path path, Type targetType) {
			this.path = path;
			this.targetType = targetType;
		}

		@Override
		public Path path() {
			return this.path;
		}

		@Override
		public Type targetType() {
			return this.targetType;
		}

		@Override
		@SuppressWarnings("unchecked")
		public final Class<T> targetClass() {
			return (Class<T>) rawClass(targetType());
		}

		@Override
		public final Reference<T> boundBy(BindingEnvironment bindings) {
			return newReference(path.boundBy(bindings), targetType);
		}

		@Override
		public RootReference<?> root() {
			return rootRef;
		}

		@Override
		public final <U> Reference<U> then(Class<U> targetClass, String... segments) throws InvalidTypeException {
			return rootRef.then(targetClass, path.then(segments));
		}

		@Override
		public final <U extends Entity> CatalogReference<U> thenCatalog(Class<U> entryClass, String... segments) throws InvalidTypeException {
			return rootRef.thenCatalog(entryClass, path.then(segments));
		}

		@Override
		public final <U extends Entity> ListingReference<U> thenListing(Class<U> entryClass, String... segments) throws InvalidTypeException {
			return rootRef.thenListing(entryClass, path.then(segments));
		}

		@Override
		public final <K extends Entity, V> SideTableReference<K, V> thenSideTable(Class<K> keyClass, Class<V> valueClass, String... segments) throws InvalidTypeException {
			return rootRef.thenSideTable(keyClass, valueClass, path.then(segments));
		}

		@Override
		public final <TT> Reference<Reference<TT>> thenReference(Class<TT> targetClass, String... segments) throws InvalidTypeException {
			return rootRef.thenReference(targetClass, path.then(segments));
		}

		@Override
		public <TT extends VariantCase> Reference<TaggedUnion<TT>> thenTaggedUnion(Class<TT> variantCaseClass, String... segments) throws InvalidTypeException {
			return rootRef.thenTaggedUnion(variantCaseClass, path.then(segments));
		}

		@SuppressWarnings("unchecked")
		@Override
		public final <TT> Reference<TT> enclosingReference(Class<TT> targetClass) {
			if (path.isEmpty()) {
				throw new IllegalArgumentException("Root reference has no enclosing references");
			}
			for (Path p = this.path.truncatedBy(1); !p.isEmpty(); p = p.truncatedBy(1))
				try {
					Type targetType = pathCompiler.targetTypeOf(p);
					if (targetClass.isAssignableFrom(rawClass(targetType))) {
						return rootRef.then(targetClass, p);
					}
				} catch (InvalidTypeException e) {
					throw new IllegalArgumentException("Error looking up enclosing " + targetClass.getSimpleName() + " from " + path);
				}
			// Might be the root
			if (targetClass.isAssignableFrom(rootRef.targetClass())) {
				return (Reference<TT>) rootRef;
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
			return BoskBase.this.rootRef.targetType;
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
			var snapshot = rootSnapshot.get();
			if (snapshot == null) {
				throw new NoReadSessionException("No active read session for " + name + " in " + Thread.currentThread());
			}
			LOGGER.trace("Snapshot is {}", System.identityHashCode(snapshot));
			try {
				return (T) dereferencer().get(snapshot, this);
			} catch (Bosk.NonexistentEntryException e) {
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
				containerRef = rootRef.then(enumerableByIdentifierClass(), containerPath);
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

	@Nullable
	final R currentRoot() {
		return currentState;
	}

	@SuppressWarnings({"unchecked", "rawtypes"})
	private static Class<EnumerableByIdentifier<?>> enumerableByIdentifierClass() {
		return (Class) EnumerableByIdentifier.class;
	}

	/**
	 * Creates a {@link Bosk.ReadSession} over the given state, even if the bosk is
	 * still being constructed. The cast is safe because {@link BoskBase} permits
	 * only {@link Bosk} as a subclass.
	 */
	final Bosk<R>.ReadSession newReadSession(R state) {
		return ((Bosk<R>) this).new ReadSession(state);
	}

	/**
	 * A {@link Bosk.ReadSession} for the very latest state, used by the local driver
	 * to check preconditions. Equivalent to {@code newReadSession(currentState)}.
	 */
	final Bosk<R>.ReadSession newSupersedingReadSession() {
		R snapshot = currentState;
		if (snapshot == null) {
			throw new IllegalStateException("Bosk constructor has not yet finished; cannot create a ReadSession");
		}
		return newReadSession(snapshot);
	}

	private static final Logger LOGGER = LoggerFactory.getLogger(Bosk.class);
	// Referencing Bosk.HOOK_LOGGER_NAME here would trigger Bosk's static initialization
	// while BoskBase is still initializing, so derive the name from the class literal instead.
	private static final Logger HOOK_LOGGER = LoggerFactory.getLogger(Bosk.class.getName() + ".hooks");
}
