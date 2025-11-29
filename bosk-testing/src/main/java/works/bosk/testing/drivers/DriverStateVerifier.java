package works.bosk.testing.drivers;

import java.io.IOException;
import java.lang.reflect.Type;
import java.util.Deque;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.LinkedBlockingDeque;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import works.bosk.Bosk;
import works.bosk.BoskDriver;
import works.bosk.DriverFactory;
import works.bosk.DriverStack;
import works.bosk.Reference;
import works.bosk.StateTreeNode;
import works.bosk.drivers.BufferingDriver;
import works.bosk.drivers.DiagnosticScopeDriver;
import works.bosk.drivers.ReplicaSet;
import works.bosk.exceptions.InvalidTypeException;
import works.bosk.exceptions.NotYetImplementedException;
import works.bosk.testing.drivers.operations.FlushOperation;
import works.bosk.testing.drivers.operations.UpdateOperation;

import static java.lang.Thread.currentThread;
import static lombok.AccessLevel.PRIVATE;
import static works.bosk.testing.BoskTestUtils.boskName;

/**
 * Watches the updates entering and leaving a particular {@link BoskDriver} and ensures
 * that they have the same effect on the bosk state. If a mismatch is found, throws
 * {@link AssertionError}.
 *
 * <p>
 * Note: this verifier uses {@link Object#equals} to compare parts of the state tree,
 * expecting value-based equality. If used with state tree nodes having different equality
 * semantics, the resulting verifier could be more or less strict than expected.
 */
@RequiredArgsConstructor(access = PRIVATE)
public final class DriverStateVerifier<R extends StateTreeNode> {
	/**
	 * Used to model the effect of each operation on the bosk state
	 */
	final Bosk<R> stateTrackingBosk;

	/**
	 * Unlike {@link #stateTrackingBosk}.{@link Bosk#driver() driver()},
	 * this driver can accept updates with references pointing to a different bosk with the same root type.
	 */
	final BoskDriver stateTrackingDriver;

	final Map<String, Deque<UpdateOperation>> pendingOperationsByThreadID = new ConcurrentHashMap<>();
	static final String THREAD_ID = "testing.thread.id";

	/**
	 * @param subject factory whose behaviour is to be verified
	 * @param rootType the type of {@link Bosk#rootReference()}
	 * @param defaultRootFunction returns the initial root used to set up an internal bosk
	 *                            that will track the cumulative effect of the updates coming from {@code subject} driver
	 */
	public static <RR extends StateTreeNode> DriverFactory<RR> wrap(DriverFactory<RR> subject, Type rootType, Bosk.DefaultRootFunction<RR> defaultRootFunction) {
		Bosk<RR> stateTrackingBosk = new Bosk<>(
			boskName(),
			rootType,
			defaultRootFunction,
			Bosk.simpleDriver(),
			Bosk.simpleRegistrar());
		DriverStateVerifier<RR> verifier = new DriverStateVerifier<>(
			stateTrackingBosk,
			ReplicaSet.redirectingTo(stateTrackingBosk)
		);
		return DriverStack.of(
			// Tag the updates with a thread ID so we can demultiplex them properly after they go through the subject driver
			DiagnosticScopeDriver.factory(dc -> dc.withAttribute(THREAD_ID, Long.toString(currentThread().threadId()))),
			// Report the updates as they appear on their way into the subject driver
			ReportingDriver.factory(verifier::incomingUpdate, verifier::incomingFlush),
			// Send to the subject driver
			subject,
			// Delay as long as possible to catch missing flushes
			BufferingDriver.factory(),
			// Report the updates as they appear on their way out of the subject driver
			ReportingDriver.factory(verifier::outgoingUpdate, verifier::outgoingFlush)
		);
	}

	/**
	 * Called when an update is about to be sent to the subject driver.
	 * Thread-safe and non-blocking.
	 */
	private void incomingUpdate(UpdateOperation updateOperation) {
		LOGGER.debug("---> IN: {}", updateOperation);
		// Note: because we have a separate queue for each thread, this isn't actually blocking
		pendingOperationsByThreadID
			.computeIfAbsent(updateOperation.diagnosticAttributes().get(THREAD_ID), _ -> new LinkedBlockingDeque<>())
			.addLast(updateOperation);
	}

	private void incomingFlush(FlushOperation __) {
		LOGGER.debug("incomingFlush()");
	}

	/**
	 * Called when an update has been sent downstream from the subject driver.
	 * Synchronized not only to protect the integrity of data structures,
	 * but also to establish the canonical order in which updates are applied.
	 */
	private synchronized void outgoingUpdate(UpdateOperation op) {
		LOGGER.debug("---> OUT: {}", op);
		try {
			Object before = currentStateBefore(op);
			Object after = hypotheticalStateAfter(op);
			LOGGER.trace("\t\tbefore: {}", before);
			LOGGER.trace("\t\t after: {}", after);

			String threadID = op.diagnosticAttributes().get(THREAD_ID);
			if (threadID == null) {
				LOGGER.debug("\tMissing " + THREAD_ID + " diagnostic attribute");
			} else {
				Deque<UpdateOperation> q = pendingOperationsByThreadID.get(threadID);
				if (q == null) {
					LOGGER.debug("\tNo queued events for thread \"{}\"", threadID);
				} else {
					LOGGER.trace("\tThread \"{}\" has {} queued operations", threadID, q.size());
					for (UpdateOperation expected : q) {
						Object expectedBefore = currentStateBefore(expected); // May not equal `before` if the two operations have different targets
						Object expectedAfter = hypotheticalStateAfter(expected);
						LOGGER.trace("\t\texpectedAfter: {}", expectedAfter);
						if (op.matchesIfApplied(expected) && Objects.equals(after, expectedAfter)) {
							LOGGER.debug("\tConclusion: found match: {}", expected);
							UpdateOperation discarded;
							while ((discarded = q.removeFirst()) != expected) {
								LOGGER.trace("\t\tdiscard preceding no-op: {}", discarded);
							}
							expected.submitTo(stateTrackingDriver);
							return;
						} else if (Objects.equals(expectedBefore, expectedAfter)) {
							LOGGER.trace("\t\tSkip queued no-op: {}", expected);
						} else {
							LOGGER.trace("\t\tNo match for: {}", expected);
							break;
						}
					}
				}
			}

			if (Objects.equals(before, after)) {
				LOGGER.debug("\tConclusion: spontaneous no-op: {}", op);
			} else {
				throw new AssertionError("No matching operation\n\t" + op);
			}
		} catch (IOException | InterruptedException e) {
			throw new NotYetImplementedException(e);
		}
	}

	private void outgoingFlush(FlushOperation __) {
		LOGGER.debug("outgoingFlush()");
		pendingOperationsByThreadID.forEach((thread, q) -> {
			discardLeadingNops(q);
			if (!q.isEmpty()) {
				throw new AssertionError(q.size() + " pending operations remain on thread " + thread
					+ "\n\tFirst is: " + q.getFirst());
			}
		});
	}

	private void discardLeadingNops(Deque<UpdateOperation> q) {
		try {
			UpdateOperation op;
			while ((op = q.peekFirst()) != null) {
				Object before = currentStateBefore(op);
				Object after = hypotheticalStateAfter(op);
				if (Objects.equals(before, after)) {
					LOGGER.debug("\tDiscarding nop: {}", op);
					var removed = q.removeFirst();
					assert op == removed;
				} else {
					LOGGER.trace("\tNext operation is not a nop: {}", op);
					break;
				}
			}
		} catch (InterruptedException | IOException e) {
			throw new NotYetImplementedException(e);
		}
	}

	@SuppressWarnings("unchecked")
	private <T> T currentStateBefore(UpdateOperation op) throws IOException, InterruptedException {
		Reference<T> stateTrackingRef = (Reference<T>) stateTrackingRef(op.target());
		stateTrackingBosk.driver().flush();
		try (var _ = stateTrackingBosk.readContext()) {
			return stateTrackingRef.valueIfExists();
		}
	}

	@SuppressWarnings("unchecked")
	private <T> T hypotheticalStateAfter(UpdateOperation op) throws IOException, InterruptedException {
		R originalState;
		Reference<T> stateTrackingRef = (Reference<T>) stateTrackingRef(op.target());
		stateTrackingBosk.driver().flush();
		try (var _ = stateTrackingBosk.readContext()) {
			originalState = stateTrackingBosk.rootReference().value();
		}
		op.submitTo(stateTrackingDriver);
		stateTrackingBosk.driver().flush();
		try (var _ = stateTrackingBosk.readContext()) {
			return stateTrackingRef.valueIfExists();
		} finally {
			stateTrackingBosk.driver().submitReplacement(stateTrackingBosk.rootReference(), originalState);
		}
	}

	@SuppressWarnings("unchecked")
	private <T> Reference<T> stateTrackingRef(Reference<T> original) {
		try {
			return (Reference<T>) stateTrackingBosk.rootReference().then(Object.class, original.path());
		} catch (InvalidTypeException e) {
			throw new AssertionError("References are expected to be compatible: " + original, e);
		}
	}

	private static final Logger LOGGER = LoggerFactory.getLogger(DriverStateVerifier.class);
}
