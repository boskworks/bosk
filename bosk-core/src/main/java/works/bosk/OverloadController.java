package works.bosk;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static works.bosk.OverloadController.Mode.CATCH_UP;
import static works.bosk.OverloadController.Mode.OVERLOAD;
import static works.bosk.OverloadController.Mode.PROBATION;
import static works.bosk.OverloadController.Mode.STANDARD;

/**
 * Decides which mode the hook runner is in, and how many queued notifications
 * to allow before leaving overload mode.
 *
 * <p>
 * The hook runner reports the events that drive this state machine: queued and
 * dequeued notifications, completed notifications, and an empty backlog. This
 * class turns those events into mode transitions and probation budgets; it
 * knows nothing about the queue's contents or the hooks they run.
 *
 * <p>
 * Overload mode is left through a bounded standard-mode probation. The probation
 * starts when the compacted backlog has drained, and runs subsequent updates
 * one at a time. It succeeds if it spends its whole budget of notifications
 * without overflowing the queue. A successful probation enters
 * {@link Mode#CATCH_UP}, a transient mode that runs one final compacted
 * generation before the driver returns to standard mode.
 *
 * <p>
 * A standard-mode overflow soon after a successful probation means the probation
 * was too short. The next probation uses twice as many notifications as its
 * budget.
 * After sustained standard-mode work, the multiplier decays linearly back to one.
 */
final class OverloadController {
	enum Mode {
		/**
		 * Standard mode: each reference changed by an update is executed
		 * separately, with the state snapshot taken immediately after that update.
		 */
		STANDARD,
		/**
		 * Compacted mode: each batch contains at most one execution per hook
		 * registration and reference, executed against a later state snapshot.
		 */
		OVERLOAD,
		/**
		 * An attempt at standard mode, to see whether the driver can keep up
		 * and whether the overload has calmed down.
		 */
		PROBATION,
		/**
		 * A final compacted generation that clears the backlog accumulated during
		 * the probation, so hook latency returns to normal promptly.
		 */
		CATCH_UP;

		/**
		 * @return whether this mode records each update's calls as pending rather
		 * than queueing its notification
		 */
		boolean isCompacting() {
			return switch (this) {
				case STANDARD, PROBATION -> false;
				case OVERLOAD, CATCH_UP -> true;
			};
		}
	}

	private final int overloadThreshold;
	/**
	 * Queued notifications: one per update that matches at least one hook
	 * registration, and one per hook registration. The queue itself stays with the
	 * runner; this total lives here so that the threshold decision does too.
	 * Guarded by this object's monitor.
	 */
	private int queuedNotificationCount;

	/**
	 * Volatile because the draining thread reads the mode outside the driver's monitor.
	 */
	private volatile Mode mode;
	/**
	 * The probe budget is this value times {@link #overloadThreshold}. Each doubling
	 * requires a successful probation that already executed the previous budget,
	 * so overflow is unreachable in practice. The first multiplication to overflow
	 * would be {@link #probeBudget()} when this multiplier reaches
	 * {@code Long.MAX_VALUE / overloadThreshold}; {@link Math#multiplyExact(long, long)}
	 * makes that assumption explicit.
	 */
	private long probeMultiplier = 1;
	private long probeRemaining;
	private boolean postProbationOverflowWindow;
	private int standardExecutions;
	private int decayExecutions;

	OverloadController(int overloadThreshold) {
		this.overloadThreshold = overloadThreshold;
		this.mode = isPermanentOverload() ? OVERLOAD : STANDARD;
		LOGGER.debug("Starting in {} mode with an overload threshold of {}", mode, overloadThreshold);
	}

	Mode mode() {
		return mode;
	}

	/**
	 * @return whether the driver always stays in overload mode: it starts there,
	 * never uses the queue, and never probes
	 */
	boolean isPermanentOverload() {
		return overloadThreshold == 0;
	}

	/**
	 * Records a notification added to the queue, entering overload mode if the
	 * queued notifications reach the threshold.
	 */
	synchronized void recordQueuedNotification() {
		assert !mode.isCompacting() : "Only standard-style triggering queues work";
		queuedNotificationCount++;
		if (queuedNotificationCount >= overloadThreshold) {
			LOGGER.warn("Hook backlog has reached the threshold ({}); entering overload mode", overloadThreshold);
			if (mode == STANDARD) {
				enterOverload();
			} else {
				probationFailed();
			}
		}
	}

	/**
	 * Records a notification removed from the queue.
	 */
	synchronized void recordDequeuedNotification() {
		queuedNotificationCount--;
	}

	/**
	 * Forgets the queued notifications, as when the queue is drained or discarded.
	 */
	synchronized void clearQueuedNotifications() {
		queuedNotificationCount = 0;
	}

	synchronized long probeBudget() {
		return Math.multiplyExact(probeMultiplier, (long) overloadThreshold);
	}

	synchronized long probeRemaining() {
		return probeRemaining;
	}

	/**
	 * Enters overload mode after a standard-mode backlog overflow.
	 */
	private void enterOverload() {
		assert mode == STANDARD : "Overload mode is entered from standard mode";
		if (postProbationOverflowWindow) {
			probeMultiplier = Math.multiplyExact(probeMultiplier, 2);
			LOGGER.debug("Probation was too short; doubling the probe budget to {} notifications", probeBudget());
		}
		mode = OVERLOAD;
		postProbationOverflowWindow = false;
		standardExecutions = 0;
		decayExecutions = 0;
		probeRemaining = 0;
		LOGGER.debug("Entering overload mode");
	}

	/**
	 * Tries standard mode again after the hook work has drained, unless the
	 * driver is not in overload mode or the threshold opts out of standard mode.
	 */
	synchronized void hookWorkDrained() {
		if (mode == OVERLOAD && !isPermanentOverload()) {
			mode = PROBATION;
			probeRemaining = probeBudget();
			LOGGER.debug("Starting a probation with a budget of {} notifications", probeRemaining);
		}
	}

	/**
	 * Records a completed notification in whichever mode the driver is in.
	 */
	synchronized void recordCompletedNotification() {
		switch (mode) {
			case STANDARD -> recordStandardWork();
			case PROBATION -> recordProbationWork();
			case OVERLOAD, CATCH_UP ->
				// The hook's own update changed the mode while the hook ran, so this
				// notification does not belong to the current mode's budget.
				LOGGER.debug("Ignoring a completed notification in {} mode", mode);
		}
	}

	/**
	 * Records completed standard-mode work. The first threshold's worth of
	 * notifications validates a preceding probation; after that, each threshold's
	 * worth slowly reduces an inflated probe multiplier.
	 */
	private void recordStandardWork() {
		if (postProbationOverflowWindow) {
			standardExecutions++;
			if (standardExecutions >= overloadThreshold) {
				postProbationOverflowWindow = false;
				standardExecutions = 0;
				decayExecutions = 0;
				LOGGER.debug("Probation validated by sustained standard-mode work");
			}
		} else {
			decayExecutions++;
			if (decayExecutions >= overloadThreshold) {
				decayExecutions = 0;
				if (probeMultiplier > 1) {
					probeMultiplier--;
					LOGGER.debug("Decaying the probe budget to {} notifications", probeBudget());
				}
			}
		}
	}

	private void recordProbationWork() {
		assert mode == PROBATION : "Probation work only occurs in probation mode";
		probeRemaining--;
		LOGGER.trace("Probation: {} notifications of the budget remain", probeRemaining);
		if (probeRemaining <= 0) {
			mode = CATCH_UP;
			LOGGER.debug("Probation spent its budget; entering catch-up");
		}
	}

	/**
	 * Returns to standard mode after an abnormal stop, keeping the probe multiplier.
	 */
	synchronized void reset() {
		mode = isPermanentOverload() ? OVERLOAD : STANDARD;
		queuedNotificationCount = 0;
		probeRemaining = 0;
		postProbationOverflowWindow = false;
		standardExecutions = 0;
		decayExecutions = 0;
		LOGGER.debug("Hook work abandoned; mode is now {}", mode);
	}

	/**
	 * Abandons a probation because the queue reached its limit.
	 */
	private void probationFailed() {
		assert mode == PROBATION : "A probation can only fail while in probation mode";
		mode = OVERLOAD;
		probeRemaining = 0;
		LOGGER.debug("Probation failed; returning to overload mode");
	}

	/**
	 * Completes the final catch-up generation and returns to standard mode.
	 */
	synchronized void catchUpFinished() {
		assert mode == CATCH_UP : "Catch-up completion requires catch-up mode";
		mode = STANDARD;
		postProbationOverflowWindow = true;
		standardExecutions = 0;
		decayExecutions = 0;
		LOGGER.debug("Catch-up finished; entering standard mode");
	}

	private static final Logger LOGGER = LoggerFactory.getLogger(Bosk.HOOK_LOGGER_NAME);
}
