package works.bosk;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static works.bosk.OverloadController.Mode.CATCH_UP;
import static works.bosk.OverloadController.Mode.OVERLOAD;
import static works.bosk.OverloadController.Mode.PROBATION;
import static works.bosk.OverloadController.Mode.STANDARD;
import static works.bosk.OverloadController.ProbationResult.CONTINUE;
import static works.bosk.OverloadController.ProbationResult.FAILED;
import static works.bosk.OverloadController.ProbationResult.SUCCEEDED;

class OverloadControllerTest {
	final OverloadController controller = new OverloadController(10);

	@Test
	void startsInStandardModeWithOneQueueLengthOfProbeBudget() {
		assertEquals(STANDARD, controller.mode(), "A new controller should start in standard mode");
		assertEquals(10, controller.probeBudget(), "The initial probe budget should be one queue length");
	}

	@Test
	void probeBudgetIsProportionalToTheQueueLimit() {
		assertEquals(1000, new OverloadController(1000).probeBudget(), "The probe budget should be one queue length");
	}

	@Test
	void probationRunsForTheProbeBudget() {
		controller.enterOverload();
		controller.startProbation(5);
		assertEquals(PROBATION, controller.mode(), "Starting a probation should enter probation mode");
		assertEquals(10, controller.probeRemaining(), "The probation should have one queue length of executions");
	}

	@Test
	void probationSucceedsWhenHalfTheBacklogDrains() {
		controller.enterOverload();
		controller.startProbation(10);
		for (int i = 0; i < 9; i++) {
			assertEquals(CONTINUE, controller.recordProbationExecution(5), "The probation should not finish early");
		}
		assertEquals(SUCCEEDED, controller.recordProbationExecution(5), "The probation should succeed after the budget");
		assertEquals(CATCH_UP, controller.mode(), "A successful probation should start the final catch-up");
	}

	@Test
	void probationFailsWhenTheBacklogDoesNotDrain() {
		controller.enterOverload();
		controller.startProbation(10);
		for (int i = 0; i < 9; i++) {
			controller.recordProbationExecution(6);
		}
		assertEquals(FAILED, controller.recordProbationExecution(6), "The probation should fail without a clear drain");
		assertEquals(OVERLOAD, controller.mode(), "A failed probation should return to overload mode");
	}

	@Test
	void probationFailureReturnsToOverload() {
		controller.enterOverload();
		controller.startProbation(10);
		controller.probationFailed();
		assertEquals(OVERLOAD, controller.mode(), "A probation overflow should return to overload mode");
	}

	@Test
	void catchUpFinishesInStandardMode() {
		controller.enterOverload();
		controller.startProbation(10);
		for (int i = 0; i < 9; i++) {
			controller.recordProbationExecution(5);
		}
		controller.recordProbationExecution(5);
		controller.catchUpFinished(true);
		assertEquals(STANDARD, controller.mode(), "A completed catch-up should enter standard mode");
	}

	@Test
	void catchUpFailureReturnsToOverload() {
		controller.enterOverload();
		controller.startProbation(10);
		for (int i = 0; i < 9; i++) {
			controller.recordProbationExecution(5);
		}
		controller.recordProbationExecution(5);
		controller.catchUpFailed();
		assertEquals(OVERLOAD, controller.mode(), "A failed catch-up should return to overload mode");
	}

	@Test
	void earlyStandardOverflowDoublesTheProbeBudget() {
		completeSuccessfulProbation();
		assertEquals(10, controller.probeBudget(), "The base probe budget should be unchanged");
		controller.enterOverload();
		assertEquals(20, controller.probeBudget(), "An early standard overflow should double the probe budget");
	}

	@Test
	void repeatedEarlyOverflowsKeepDoublingTheProbeBudget() {
		completeSuccessfulProbation();
		controller.enterOverload();
		completeSuccessfulProbation();
		controller.enterOverload();
		completeSuccessfulProbation();
		controller.enterOverload();
		assertEquals(80, controller.probeBudget(), "The probe budget should keep doubling without a cap");
	}

	@Test
	void lateStandardOverflowDoesNotGrowTheProbeBudget() {
		completeSuccessfulProbation();
		for (int i = 0; i < 10; i++) {
			controller.recordStandardExecution();
		}
		controller.enterOverload();
		assertEquals(10, controller.probeBudget(), "A validated probation should not be extended by a later overflow");
	}

	@Test
	void probeBudgetDecaysAfterSustainedStandardWork() {
		completeSuccessfulProbation();
		controller.enterOverload();
		assertEquals(20, controller.probeBudget(), "An early overflow should double the probe budget");

		completeSuccessfulProbation();
		for (int i = 0; i < 20; i++) {
			controller.recordStandardExecution();
		}
		assertEquals(10, controller.probeBudget(), "Sustained standard work should decay the multiplier linearly");
	}

	@Test
	void returnToOverloadPreservesTheProbeBudget() {
		controller.enterOverload();
		controller.startProbation(10);
		controller.returnToOverload();
		assertEquals(OVERLOAD, controller.mode(), "An interrupted probation should return to overload mode");
		assertEquals(10, controller.probeBudget(), "An interrupted probation should not change the probe budget");
	}

	private void completeSuccessfulProbation() {
		if (controller.mode() == STANDARD) {
			controller.enterOverload();
		}
		long budget = controller.probeBudget();
		controller.startProbation(10);
		for (long i = 1; i < budget; i++) {
			assertEquals(CONTINUE, controller.recordProbationExecution(5), "The probation should continue");
		}
		assertEquals(SUCCEEDED, controller.recordProbationExecution(5), "The probation should succeed");
		controller.catchUpFinished(true);
		assertEquals(STANDARD, controller.mode(), "The catch-up should enter standard mode");
	}
}
