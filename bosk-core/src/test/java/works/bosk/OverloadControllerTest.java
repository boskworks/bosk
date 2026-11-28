package works.bosk;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static works.bosk.OverloadController.Mode.CATCH_UP;
import static works.bosk.OverloadController.Mode.OVERLOAD;
import static works.bosk.OverloadController.Mode.PROBATION;
import static works.bosk.OverloadController.Mode.STANDARD;

class OverloadControllerTest {
	final OverloadController controller = new OverloadController(10);

	@Test
	void startsInStandardModeWithOneQueueLengthOfProbeBudget() {
		assertEquals(STANDARD, controller.mode(), "A new controller should start in standard mode");
		assertEquals(10, controller.probeBudget(), "The initial probe budget should be one queue length");
	}

	@Test
	void probeBudgetIsProportionalToTheQueueLimit() {
		assertEquals(1000, new OverloadController(1000).probeBudget(), "The probe budget should be one limit's worth of notifications");
	}

	@Test
	void probationRunsForTheProbeBudget() {
		startOverload();
		controller.hookWorkDrained();
		assertEquals(PROBATION, controller.mode(), "Starting a probation should enter probation mode");
		assertEquals(10, controller.probeRemaining(), "The probation should have one queue length of notifications");
	}

	@Test
	void probationSucceedsAfterItsBudget() {
		startOverload();
		controller.hookWorkDrained();
		for (int i = 0; i < 9; i++) {
			controller.recordCompletedNotification();
			assertEquals(PROBATION, controller.mode(), "The probation should not finish early");
		}
		controller.recordCompletedNotification();
		assertEquals(CATCH_UP, controller.mode(), "A successful probation should start the final catch-up");
	}

	@Test
	void queuedNotificationsReachTheThreshold() {
		queueNotifications(9);
		assertEquals(STANDARD, controller.mode(), "Nine queued notifications should be below the threshold");
		controller.recordQueuedNotification();
		assertEquals(OVERLOAD, controller.mode(), "Reaching the threshold should enter overload mode");
	}

	@Test
	void dequeuedNotificationsReduceTheCount() {
		queueNotifications(9);
		controller.recordDequeuedNotification();
		controller.recordDequeuedNotification();
		queueNotifications(2);
		assertEquals(STANDARD, controller.mode(), "Dequeued notifications should no longer count toward the threshold");
	}

	@Test
	void clearQueuedNotificationsForgetsTheCount() {
		queueNotifications(9);
		controller.clearQueuedNotifications();
		queueNotifications(9);
		assertEquals(STANDARD, controller.mode(), "Cleared notifications should not count toward the threshold");
	}

	@Test
	void zeroThresholdStartsInOverloadMode() {
		assertEquals(OVERLOAD, new OverloadController(0).mode(), "A zero threshold should start in overload mode");
		assertEquals(STANDARD, new OverloadController(1).mode(), "A threshold of one should start in standard mode");
	}

	@Test
	void zeroThresholdStaysInOverloadModeAfterReset() {
		OverloadController alwaysOverload = new OverloadController(0);
		alwaysOverload.reset();
		assertEquals(OVERLOAD, alwaysOverload.mode(), "A zero threshold should not reset to standard mode");
	}

	@Test
	void onlyZeroIsPermanentOverload() {
		assertTrue(new OverloadController(0).isPermanentOverload(), "Only a zero threshold should stay in overload mode");
		assertFalse(new OverloadController(1).isPermanentOverload(), "A threshold of one should use the ordinary path");
		assertFalse(new OverloadController(2).isPermanentOverload(), "A threshold of two should allow standard mode");
	}

	@Test
	void resetForgetsQueuedNotifications() {
		startOverload();
		controller.reset();
		queueNotifications(9);
		assertEquals(STANDARD, controller.mode(), "Reset should forget queued notifications");
	}

	@Test
	void probationFailureReturnsToOverload() {
		startOverload();
		controller.hookWorkDrained();
		queueNotifications(10);
		assertEquals(OVERLOAD, controller.mode(), "A probation overflow should return to overload mode");
	}

	@Test
	void catchUpFinishesInStandardMode() {
		completeSuccessfulProbation();
		assertEquals(STANDARD, controller.mode(), "A completed catch-up should enter standard mode");
	}

	@Test
	void earlyStandardOverflowDoublesTheProbeBudget() {
		completeSuccessfulProbation();
		assertEquals(10, controller.probeBudget(), "The base probe budget should be unchanged");
		queueNotifications(10);
		assertEquals(20, controller.probeBudget(), "An early standard overflow should double the probe budget");
	}

	@Test
	void repeatedEarlyOverflowsKeepDoublingTheProbeBudget() {
		completeSuccessfulProbation();
		queueNotifications(10);
		completeSuccessfulProbation();
		queueNotifications(10);
		completeSuccessfulProbation();
		queueNotifications(10);
		assertEquals(80, controller.probeBudget(), "The probe budget should keep doubling without a cap");
	}

	@Test
	void lateStandardOverflowDoesNotGrowTheProbeBudget() {
		completeSuccessfulProbation();
		for (int i = 0; i < 10; i++) {
			controller.recordCompletedNotification();
		}
		queueNotifications(10);
		assertEquals(10, controller.probeBudget(), "A validated probation should not be extended by a later overflow");
	}

	@Test
	void probeBudgetDecaysAfterSustainedStandardWork() {
		completeSuccessfulProbation();
		queueNotifications(10);
		assertEquals(20, controller.probeBudget(), "An early overflow should double the probe budget");

		completeSuccessfulProbation();
		for (int i = 0; i < 20; i++) {
			controller.recordCompletedNotification();
		}
		assertEquals(10, controller.probeBudget(), "Sustained standard work should decay the multiplier linearly");
	}

	/**
	 * Queues a threshold's worth of notifications, which the drain would then
	 * remove before the backlog empties, leaving the controller in overload mode.
	 */
	private void startOverload() {
		queueNotifications(10);
		controller.clearQueuedNotifications();
	}

	private void completeSuccessfulProbation() {
		if (controller.mode() == STANDARD) {
			queueNotifications(10);
		}
		// The queue drains into the first overload batch before the backlog empties.
		controller.clearQueuedNotifications();
		long budget = controller.probeBudget();
		controller.hookWorkDrained();
		for (long i = 1; i < budget; i++) {
			controller.recordCompletedNotification();
			assertEquals(PROBATION, controller.mode(), "The probation should continue");
		}
		controller.recordCompletedNotification();
		controller.catchUpFinished();
		assertEquals(STANDARD, controller.mode(), "The catch-up should enter standard mode");
	}

	private void queueNotifications(int count) {
		for (int i = 0; i < count; i++) {
			controller.recordQueuedNotification();
		}
	}

}
