package works.bosk;

import static works.bosk.OverloadController.Mode.CATCH_UP;
import static works.bosk.OverloadController.Mode.OVERLOAD;
import static works.bosk.OverloadController.Mode.PROBATION;
import static works.bosk.OverloadController.Mode.STANDARD;
import static works.bosk.OverloadController.ProbationResult.CONTINUE;
import static works.bosk.OverloadController.ProbationResult.FAILED;
import static works.bosk.OverloadController.ProbationResult.SUCCEEDED;

/**
 * Decides when the local driver should leave overload mode.
 *
 * <p>
 * Overload mode is left through a bounded standard-mode probation. The probation
 * starts when the pending set has drained and there is queued work to test. It
 * succeeds only if at least half of the backlog has drained by the end of its
 * work budget. A successful probation runs one final overload catch-up generation
 * before the driver returns to standard mode.
 *
 * <p>
 * A standard-mode overflow soon after a successful probation means the probation
 * was too short. The next probation uses twice as many queue-lengths of hook
 * executions. After sustained standard-mode work, the multiplier decays linearly
 * back to one.
 */
final class OverloadController {
	enum Mode {
		STANDARD,
		OVERLOAD,
		PROBATION,
		CATCH_UP
	}

	enum ProbationResult {
		CONTINUE,
		SUCCEEDED,
		FAILED
	}

	private final int queueMaxSize;

	/**
	 * Volatile because the draining thread reads the mode outside the driver's monitor.
	 */
	private volatile Mode mode = STANDARD;
	private long probeMultiplier = 1;
	private long probeRemaining;
	private long probeStartBacklog;
	private boolean postProbationOverflowWindow;
	private int standardExecutions;
	private int decayExecutions;

	OverloadController(int queueMaxSize) {
		this.queueMaxSize = queueMaxSize;
	}

	Mode mode() {
		return mode;
	}

	synchronized long probeBudget() {
		return Math.multiplyExact(probeMultiplier, (long) queueMaxSize);
	}

	synchronized long probeRemaining() {
		return probeRemaining;
	}

	/**
	 * Enters overload mode after a standard-mode queue overflow.
	 */
	synchronized void enterOverload() {
		assert mode == STANDARD : "Overload mode is entered from standard mode";
		if (postProbationOverflowWindow) {
			probeMultiplier = Math.multiplyExact(probeMultiplier, 2);
		}
		mode = OVERLOAD;
		postProbationOverflowWindow = false;
		standardExecutions = 0;
		decayExecutions = 0;
		probeRemaining = 0;
	}

	/**
	 * Starts a standard-mode probation from overload mode.
	 *
	 * @param startBacklog the backlog when the probation begins
	 */
	synchronized void startProbation(int startBacklog) {
		assert mode == OVERLOAD : "Probation starts from overload mode";
		mode = PROBATION;
		probeStartBacklog = startBacklog;
		probeRemaining = probeBudget();
	}

	/**
	 * Records one completed hook execution during a probation.
	 *
	 * @param backlog the backlog after the execution
	 */
	synchronized ProbationResult recordProbationExecution(int backlog) {
		assert mode == PROBATION : "Probation executions only occur in probation mode";
		probeRemaining--;
		if (probeRemaining > 0) {
			return CONTINUE;
		}
		if ((long) backlog * 2 <= probeStartBacklog) {
			mode = CATCH_UP;
			return SUCCEEDED;
		} else {
			mode = OVERLOAD;
			return FAILED;
		}
	}

	/**
	 * Returns to overload mode after an interrupted batch. Any probe-budget
	 * adjustment is preserved.
	 */
	synchronized void returnToOverload() {
		mode = OVERLOAD;
		postProbationOverflowWindow = false;
		probeRemaining = 0;
	}

	/**
	 * Abandons a probation because the queue reached its limit.
	 */
	synchronized void probationFailed() {
		assert mode == PROBATION : "A probation can only fail while in probation mode";
		mode = OVERLOAD;
		probeRemaining = 0;
	}

	/**
	 * Completes the final catch-up generation.
	 *
	 * @param canUseStandard true if standard mode can take over
	 */
	synchronized void catchUpFinished(boolean canUseStandard) {
		assert mode == CATCH_UP : "Catch-up completion requires catch-up mode";
		if (canUseStandard) {
			mode = STANDARD;
			postProbationOverflowWindow = true;
			standardExecutions = 0;
			decayExecutions = 0;
		} else {
			mode = OVERLOAD;
			postProbationOverflowWindow = false;
			probeRemaining = 0;
		}
	}

	/**
	 * Abandons the final catch-up generation because the queue overflowed.
	 */
	synchronized void catchUpFailed() {
		assert mode == CATCH_UP : "Catch-up can only fail while in catch-up mode";
		mode = OVERLOAD;
		postProbationOverflowWindow = false;
		probeRemaining = 0;
	}

	/**
	 * Records one completed standard-mode hook execution. The first queue's
	 * worth of executions validates a preceding probation; after that, each
	 * queue's worth slowly reduces an inflated probe multiplier.
	 */
	synchronized void recordStandardExecution() {
		if (mode != STANDARD) {
			return;
		}
		if (postProbationOverflowWindow) {
			standardExecutions++;
			if (standardExecutions >= queueMaxSize) {
				postProbationOverflowWindow = false;
				standardExecutions = 0;
				decayExecutions = 0;
			}
		} else {
			decayExecutions++;
			if (decayExecutions >= queueMaxSize) {
				decayExecutions = 0;
				if (probeMultiplier > 1) {
					probeMultiplier--;
				}
			}
		}
	}
}
