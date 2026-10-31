package works.bosk;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;
import java.util.concurrent.Semaphore;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static java.lang.Thread.holdsLock;
import static java.util.Objects.requireNonNull;
import static works.bosk.OverloadController.Mode.CATCH_UP;
import static works.bosk.OverloadController.Mode.OVERLOAD;
import static works.bosk.OverloadController.Mode.PROBATION;
import static works.bosk.OverloadController.Mode.STANDARD;
import static works.bosk.PendingHookSet.PendingPaths;
import static works.bosk.logging.MappedDiagnosticContext.setupMDC;

/**
 * Hook delivery: which calls an update triggers, when they run, and how much work
 * may accumulate before delivery is compacted.
 *
 * <p>
 * The local driver calls {@link #update} as it applies each update, and
 * {@link #drainIfAllowed} afterwards to run the resulting calls. Hook work
 * accumulates in {@link #hookQueue}, which bounds how many state snapshots it
 * holds. If it reaches {@link BoskConfig#hookOverloadThreshold}, the driver enters
 * overload mode and each update is instead compacted into {@link #overloadPending}:
 * at most one pending call per hook registration and reference, run against a later
 * state snapshot. See the user's guide for the guarantees each mode provides.
 *
 * <p>
 * The driver's monitor guards this object, because accepting a trigger has to
 * happen atomically with the state change that produced it: that keeps hook
 * work in update order, and ensures a batch's state snapshot is never older than
 * the notifications it delivers. The driver holds its monitor when calling
 * {@link #update} and {@link #register}; everything else takes the monitor itself.
 */
final class HookRunner<R extends StateTreeNode> {
	private final Bosk<R> bosk;
	private final Object monitor;
	private final List<HookRegistration<?>> hooks = new ArrayList<>();
	private final Deque<HookTrigger<R>> hookQueue = new ArrayDeque<>();
	private final PendingHookSet overloadPending = new PendingHookSet();
	private final OverloadController overloadController;
	private final Semaphore drainPermit = new Semaphore(1);
	private final Thread.Builder hookThreadBuilder = Thread
		.ofVirtual()
		.name("bosk-hook-", 1);

	HookRunner(Bosk<R> bosk, Object monitor, int hookOverloadThreshold) {
		this.bosk = bosk;
		this.monitor = monitor;
		this.overloadController = new OverloadController(hookOverloadThreshold);
	}

	/**
	 * Adds the given hook to the registry and runs it on every existing object
	 * that matches its scope.
	 *
	 * <p>
	 * The caller must hold the monitor, so that the registration is ordered with
	 * respect to updates.
	 */
	<T> void register(String name, Reference<T> scope, BoskHook<T> action) {
		assert holdsLock(monitor);
		HookRegistration<T> reg = new HookRegistration<>(name, scope, action, hooks.size());
		hooks.add(reg);
		accept(new HookTrigger<>(
			null,
			requireNonNull(bosk.currentRoot()),
			bosk.rootRef,
			bosk.context.getAttributes(),
			reg.index(),
			reg.index() + 1));
	}

	/**
	 * Records the hook work for an update that has just been applied, without
	 * running any hooks yet.
	 *
	 * <p>
	 * The caller must hold the monitor, so that hook work stays in update order.
	 *
	 * @param priorRoot the state before the update, for detecting which references
	 *                  changed
	 * @param target    the reference the update was applied to
	 */
	void update(@Nullable R priorRoot, Reference<?> target) {
		assert holdsLock(monitor);
		R rootForHook = bosk.currentRoot();
		int hookEnd = hooks.size();
		boolean anyMatch = hooks.subList(0, hookEnd).stream()
			.anyMatch(hook -> hook.matchesTarget(target));
		if (anyMatch) {
			accept(new HookTrigger<>(
				priorRoot,
				rootForHook,
				target,
				bosk.context.getAttributes(),
				0,
				hookEnd));
		}
	}

	OverloadController.Mode mode() {
		return overloadController.mode();
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
	 * Note: don't call while holding the monitor. Running hooks means running arbitrary
	 * user code, which can take an arbitrary amount of time, and if the monitor is held,
	 * that blocks other threads from submitting updates.
	 */
	void drainIfAllowed() {
		do {
			if (drainPermit.tryAcquire()) {
				try {
					LOGGER.debug("Draining hook work");
					drain:
					while (true) {
						// An interrupt means "stop", not "pause": we do not
						// resume the backlog on some later, unrelated update.
						if (Thread.currentThread().isInterrupted()) {
							LOGGER.debug("Interrupted; clearing the remaining queued hooks");
							clearHookWork();
							return;
						}
						try {
							switch (overloadController.mode()) {
								case OVERLOAD -> {
									CompactedBatch<R> batch = buildBatch();
									if (batch.pending().isEmpty()) {
										LOGGER.debug("No compacted hook work left");
										break drain;
									}
									LOGGER.debug("Hook: overload batch with {} paths", batch.pending().size());
									runPendingBatch(batch);
								}
								case CATCH_UP -> runCatchUpBatch(buildBatch());
								case STANDARD, PROBATION -> {
									HookTrigger<R> work;
									synchronized (monitor) {
										// A concurrent overflow may have switched modes
										// since the switch read the mode; leave this work
										// for the overload batch rather than running it
										// after earlier work has been compacted.
										if (overloadController.mode().isCompacting()) {
											continue;
										}
										work = hookQueue.pollFirst();
										if (work != null) {
											overloadController.recordDequeuedTrigger();
										}
									}
									if (work == null) {
										LOGGER.debug("Hook queue is empty");
										break drain;
									}
									execute(work);
									overloadController.recordCompletedTrigger();
								}
							}
						} catch (InterruptedException e) {
							clearHookWork();
							Thread.currentThread().interrupt();
							LOGGER.warn("Interrupted while running hooks; the running hook was interrupted and terminated, and the remaining queued hooks were discarded", e);
							return;
						}
					}
					reportCaughtUp();
				} finally {
					drainPermit.release();
				}
			} else {
				LOGGER.debug("Not draining the hook queue");
				return;
			}

			// The do-while loop here needs an explanation. At this location in the code,
			// we need to check again whether there is hook work. Here's why.
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

		} while (hasHookWork());
	}

	/**
	 * Adds a trigger, either to the queue for standard-style delivery or expanded
	 * into {@link #overloadPending} immediately.
	 *
	 * <p>
	 * Expanding here rather than at delivery time is what keeps compacted work
	 * from waiting in a queue: the notifications are already recorded, and they
	 * can be delivered whenever a batch next runs. It is also what makes
	 * {@link #hookQueue} bounded, because a compacting mode never adds to it.
	 *
	 * <p>
	 * Doing the expansion under the monitor, where the update's state change
	 * also happens, keeps the notifications in update order. The first
	 * notification for a reference records whether it existed beforehand, and
	 * that must come from the earliest update that touched it. Expanding runs no
	 * user code: it walks the two snapshots and records notifications.
	 */
	private void accept(HookTrigger<R> trigger) {
		assert holdsLock(monitor);
		if (overloadController.mode().isCompacting()) {
			compact(trigger, overloadPending);
		} else {
			hookQueue.addLast(trigger);
			overloadController.recordQueuedTrigger();
		}
	}

	/**
	 * Reports to the controller when the drain has nothing left to do, so that it
	 * can decide whether to try standard delivery again.
	 *
	 * <p>
	 * Reporting here, rather than when a particular update arrives, means the mode
	 * changes only when the drain is quiet: a hook that submits updates during a
	 * batch cannot change the mode under the batch's feet.
	 */
	private void reportCaughtUp() {
		synchronized (monitor) {
			if (hookQueue.isEmpty() && overloadPending.isEmpty()) {
				overloadController.hookWorkDrained();
			}
		}
	}

	private void execute(HookTrigger<R> trigger) throws InterruptedException {
		for (int i = trigger.hookStart(); i < trigger.hookEnd(); i++) {
			executeHook(hookAt(i), trigger);
		}
	}

	private <S> void executeHook(HookRegistration<S> reg, HookTrigger<R> trigger) throws InterruptedException {
		reg.triggerAction(trigger.priorRoot(), trigger.rootForHook(), trigger.target(), (changedRef, _, _) -> {
			LOGGER.debug("Hook: trigger {}({}) due to {}", reg.name(), changedRef, trigger.target());
			run(reg.name(), reg.hook(), changedRef, trigger.rootForHook(), trigger.diagnosticAttributes());
		});
	}

	/**
	 * Records the trigger's pending notifications into the given set, without
	 * running any hooks.
	 */
	private void compact(HookTrigger<R> trigger, PendingHookSet into) {
		for (int i = trigger.hookStart(); i < trigger.hookEnd(); i++) {
			compactHook(hookAt(i), trigger, into);
		}
	}

	private <S> void compactHook(HookRegistration<S> reg, HookTrigger<R> trigger, PendingHookSet into) {
		reg.triggerAction(trigger.priorRoot(), trigger.rootForHook(), trigger.target(),
			(changedRef, existedBefore, existsAfter) -> into.add(reg.index(), changedRef.path(), existedBefore, existsAfter));
	}

	private HookRegistration<?> hookAt(int index) {
		synchronized (monitor) {
			HookRegistration<?> registration = hooks.get(index);
			assert registration.index() == index;
			return registration;
		}
	}

	private List<HookTrigger<R>> drainQueuedTriggers() {
		assert holdsLock(monitor);
		List<HookTrigger<R>> result = new ArrayList<>(hookQueue);
		hookQueue.clear();
		overloadController.clearQueuedTriggers();
		return result;
	}

	/**
	 * One batch: the pending notifications to run, grouped by hook registration,
	 * and the state they run against.
	 */
	private record CompactedBatch<RR>(Map<Integer, PendingPaths> pending, RR snapshot) {}

	/**
	 * Builds the next batch: the queue's leftovers, if there are any, otherwise the
	 * compacted backlog.
	 *
	 * <p>
	 * The queue only holds work from before the driver entered overload mode,
	 * because compacting modes expand triggers as they arrive rather than
	 * queueing them. That work is flushed into its own generation, ahead of any
	 * compacted work, because it is older and its baselines are the earlier ones.
	 */
	private CompactedBatch<R> buildBatch() throws InterruptedException {
		List<HookTrigger<R>> queued;
		R queueSnapshot;
		synchronized (monitor) {
			queued = drainQueuedTriggers();
			queueSnapshot = bosk.currentRoot();
		}
		if (!queued.isEmpty()) {
			LOGGER.debug("Flushing {} queued triggers into a batch", queued.size());
			PendingHookSet flushed = new PendingHookSet();
			for (HookTrigger<R> trigger : queued) {
				compact(trigger, flushed);
			}
			Map<Integer, PendingPaths> pending = flushed.detach();
			if (!pending.isEmpty()) {
				return new CompactedBatch<>(pending, queueSnapshot);
			}
			// Everything in the queue cancelled itself; fall through to the backlog.
		}
		synchronized (monitor) {
			return new CompactedBatch<>(overloadPending.detach(), bosk.currentRoot());
		}
	}

	/**
	 * Runs one catch-up batch, or finishes catch-up if the backlog is empty.
	 */
	private void runCatchUpBatch(CompactedBatch<R> batch) throws InterruptedException {
		if (batch.pending().isEmpty()) {
			synchronized (monitor) {
				if (!overloadPending.isEmpty() || !hookQueue.isEmpty()) {
					// Work arrived while this batch was being prepared; it will
					// be run by the next catch-up batch.
					return;
				}
				overloadController.catchUpFinished();
			}
			LOGGER.info("Hook backlog returned to normal; leaving overload mode");
			return;
		}
		LOGGER.debug("Hook: final catch-up batch with {} paths", batch.pending().size());
		runPendingBatch(batch);
	}

	/**
	 * Runs the notifications in the batch against its snapshot. Each notification is
	 * removed from the batch as it starts, so a failure leaves only the work that did
	 * not run.
	 *
	 * <p>
	 * A {@link RuntimeException} puts those unrun notifications back into the backlog,
	 * so a later drain can run them. An interrupt or an {@link Error} discards them:
	 * an interrupt abandons all hook work, and bosk does not try to continue after an
	 * error.
	 */
	private void runPendingBatch(CompactedBatch<R> batch) throws InterruptedException {
		try {
			for (var hookEntry : batch.pending().entrySet()) {
				HookRegistration<?> reg = hookAt(hookEntry.getKey());
				for (var iterator = hookEntry.getValue().entrySet().iterator(); iterator.hasNext(); ) {
					var entry = iterator.next();
					if (Thread.currentThread().isInterrupted()) {
						throw new InterruptedException();
					}
					boolean existedAtBaseline = entry.getValue();
					iterator.remove();
					runPendingHook(reg, entry.getKey(), batch.snapshot(), existedAtBaseline);
				}
			}
		} catch (RuntimeException e) {
			LOGGER.debug("Hook batch failed; requeueing the notifications that did not run", e);
			overloadPending.mergeEarlier(batch.pending());
			throw e;
		} catch (Error e) {
			LOGGER.warn("Hook batch aborted by an error; discarding the notifications that did not run", e);
			throw e;
		}
	}

	private <S> void runPendingHook(HookRegistration<S> reg, Path path, R snapshot, boolean existedAtBaseline) throws InterruptedException {
		Reference<S> changedRef = reg.scope().boundBy(path);
		// A requeued batch can contain a baseline-absent pair whose reference was deleted while the batch was detached.
		if (!existedAtBaseline && refValueIfExists(changedRef, snapshot) == null) {
			return;
		}
		// Compacted notifications have no single originating update, so they do not carry diagnostic attributes.
		run(reg.name(), reg.hook(), changedRef, snapshot, MapValue.empty());
	}

	private void clearHookWork() {
		synchronized (monitor) {
			hookQueue.clear();
			overloadPending.clear();
			overloadController.reset();
		}
	}

	private boolean hasHookWork() {
		synchronized (monitor) {
			return !hookQueue.isEmpty() || !overloadPending.isEmpty();
		}
	}

	/**
	 * Runs the given hook, and does not return until it has finished.
	 *
	 * @param hookName             the registration's name, for diagnostics
	 * @param changedRef           the reference the hook is being told about
	 * @param rootForHook          the state the hook's read session will see
	 * @param diagnosticAttributes the context attributes of the triggering update
	 */
	private <S> void run(String hookName, BoskHook<S> hook, Reference<S> changedRef, R rootForHook, MapValue<String> diagnosticAttributes) throws InterruptedException {
		FutureTask<Void> task = new FutureTask<>(() -> {
			// We use two nested try statements here so that the "finally" clause runs within the diagnostic scope
			try (
				var _ = setupMDC(bosk.name, bosk.instanceID);
				var _ = bosk.context.withOnly(diagnosticAttributes)
			) {
				try (var _ = bosk.newReadSession(rootForHook)) {
					LOGGER.debug("Hook: RUN {}({})", hookName, changedRef);
					hook.onChanged(changedRef);
				} catch (InterruptedException e) {
					LOGGER.warn("Bosk hook \"{}\" was interrupted; proceeding", hookName, e);
				} catch (RuntimeException e) {
					LOGGER.error("Bosk hook \"{}\" terminated with an exception, which usually indicates a bug. State updates may have been lost", hookName, e);

					// Note that we don't catch Error. The practical reason is to allow users to write
					// unit tests that throw AssertionError from hooks, but the bigger reason is that
					// Errors indicate that something has gone dreadfully wrong, and we probably should
					// not attempt to continue.
				} finally {
					LOGGER.debug("Hook: end {}({})", hookName, changedRef);
				}
			}
		}, null);
		// A fresh virtual thread keeps thread locals from the triggering thread
		// out of user code. This is slightly tragic, because usually thread-local
		// propagation works just the way we'd want it, but not always. Given the
		// choices "always, sometimes, never", if we can't achieve "always", then
		// the bosk philosophy prefers "never" over "sometimes".
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
			// The interrupt is intended for the hook work in flight here. Deliver it
			// to the running hook, per the BoskHook contract, and await its termination
			// before proceeding. This mimics structured concurrency: cancel the
			// in-flight subtask and wait for it to finish.
			hookThread.interrupt();
			awaitTermination(hookThread);
			throw e;
		}
	}

	/**
	 * Waits for the given hook thread to terminate. If this (the calling) thread is
	 * interrupted while waiting, keep waiting: the interrupt has already been
	 * delivered to the hook, and proceeding without it would leave the hook running
	 * orphaned.
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
	 * @param <X>            The checked exception the action can throw
	 */
	private <S, X extends Exception> void triggerCascade(Reference<S> effectiveScope, @Nullable R priorRoot, R newRoot, HookAction<S, X> action) throws X {
		if (effectiveScope.path().numParameters() == 0) {
			// effectiveScope points at a single node that may have changed
			//
			S priorValue = refValueIfExists(effectiveScope, priorRoot);
			S currentValue = refValueIfExists(effectiveScope, newRoot);
			if (priorValue == currentValue) { // Note object identity comparison
				LOGGER.debug("Hook: skip unchanged {}", effectiveScope);
			} else {
				// We've found something that changed
				action.execute(effectiveScope, priorValue != null, currentValue != null);
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

			if (priorContainer == newContainer) {
				LOGGER.debug("Hook: container {} unchanged; no entries to visit", containerRef);
				return;
			}

			// Process any deleted items first. This can allow the hook to free some memory
			// that can be used by subsequent hooks.
			// We do them in reverse order just because that's likely to be the preferred
			// order for cleanup activities.
			//
			// We collect the deleted IDs so we can visit them in reverse order.
			//
			// TODO: Should we actually process the hooks themselves in reverse order for the same reason?
			//
			List<Identifier> deleted = new ArrayList<>();
			if (priorContainer != null) {
				for (Identifier id : priorContainer.ids()) {
					if (newContainer == null || newContainer.get(id) == null) {
						deleted.add(id);
					}
				}
			}
			for (int i = deleted.size() - 1; i >= 0; i--) {
				triggerCascade(effectiveScope.boundTo(deleted.get(i)), priorRoot, newRoot, action);
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
	private <V> V refValueIfExists(Reference<V> ref, @Nullable R root) {
		if (root == null) {
			return null;
		} else {
			// TODO: This would be less cumbersome if we could apply a Reference to an arbitrary root object.
			// For now, References only apply to the current ReadSession, so we need a new ReadSession every time
			// we want to change roots.
			try (var _ = bosk.newReadSession(root)) {
				return ref.valueIfExists();
			}
		}
	}

	/**
	 * Receives each reference whose value changed, along with its existence
	 * before and after the change.
	 *
	 * @param <X> the checked exception the action can throw. The traversal
	 *            propagates it, so each caller has to handle only what its own
	 *            action can throw: an action that runs hooks can be interrupted,
	 *            while one that merely records notifications cannot.
	 */
	@FunctionalInterface
	interface HookAction<S, X extends Exception> {
		void execute(Reference<S> ref, boolean existedBefore, boolean existsAfter) throws X;
	}

	// Inner class can't be a record
	final class HookRegistration<S> {
		private final String name;
		private final Reference<S> scope;
		private final BoskHook<S> hook;
		private final int index;

		HookRegistration(String name, Reference<S> scope, BoskHook<S> hook, int index) {
			this.name = name;
			this.scope = scope;
			this.hook = hook;
			this.index = index;
		}

		/**
		 * @return whether this hook's scope matches the path of {@code target}
		 */
		boolean matchesTarget(Reference<?> target) {
			int relativeDepth = target.path().length() - scope.path().length();
			if (relativeDepth >= 0) {
				// target may be the scope object or a descendant
				Path candidate = target.path().truncatedBy(relativeDepth);
				return scope.path().matches(candidate);
			} else {
				// target may be an ancestor of the scope object
				Path enclosingScope = scope.path().truncatedBy(-relativeDepth);
				return enclosingScope.matches(target.path());
			}
		}

		/**
		 * Calls <code>action</code> for every object whose path matches <code>scope</code> that
		 * was changed by a driver event targeting <code>target</code>.
		 *
		 * @param priorRoot The bosk root object before the driver event occurred
		 * @param newRoot   The bosk root object after the driver event occurred
		 * @param target    The object specified by the driver event
		 * @param action    The operation to perform for each matching object that could have changed
		 * @param <X>       The checked exception the action can throw
		 */
		<X extends Exception> void triggerAction(@Nullable R priorRoot, R newRoot, Reference<?> target, HookAction<S, X> action) throws X {
			if (matchesTarget(target)) {
				triggerCascade(boundScopeFor(target), priorRoot, newRoot, action);
			}
		}

		/**
		 * @return this hook's scope bound to the path of a target that {@link #matchesTarget matches}
		 */
		private Reference<S> boundScopeFor(Reference<?> target) {
			assert matchesTarget(target);
			int relativeDepth = target.path().length() - scope.path().length();
			return relativeDepth >= 0
				? scope.boundBy(target.path().truncatedBy(relativeDepth))
				: scope.boundBy(target.path());
		}

		String name() {
			return this.name;
		}

		Reference<S> scope() {
			return this.scope;
		}

		BoskHook<S> hook() {
			return this.hook;
		}

		int index() {
			return this.index;
		}

		@Override
		public boolean equals(Object o) {
			if (o == null || getClass() != o.getClass()) return false;
			@SuppressWarnings("unchecked")
			HookRegistration<S> that = (HookRegistration<S>) o;
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
			return "HookRegistration(name=" + this.name() + ", scope=" + this.scope() + ", hook=" + this.hook + ")";
		}
	}

	private static final Logger LOGGER = LoggerFactory.getLogger(Bosk.HOOK_LOGGER_NAME);
}
