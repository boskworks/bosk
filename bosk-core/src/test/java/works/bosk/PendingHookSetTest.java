package works.bosk;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import works.bosk.PendingHookSet.PendingPaths;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PendingHookSetTest {
	final PendingHookSet pending = new PendingHookSet();
	final Path pathA = Path.just("a");
	final Path pathB = Path.of("b", "c");

	@Test
	void creationThenDeletion_cancels() {
		pending.add(0, pathA, false, true);
		pending.add(0, pathA, true, false);
		assertTrue(pending.isEmpty(), "A created and deleted reference needs no hook call");
	}

	@Test
	void deletionOfPreExistingReference_remainsPending() {
		pending.add(0, pathA, true, false);
		assertFalse(pending.isEmpty(), "A deleted pre-existing reference needs a deletion call");
		assertEquals(Map.of(pathA, true), pending.detach().get(0),
			"A pre-existing reference keeps the deletion call with a baseline that says it existed");
	}

	@Test
	void firstBaselineIsKeptAcrossLaterChanges() {
		pending.add(0, pathA, false, true);
		pending.add(0, pathA, true, true);
		assertEquals(Map.of(pathA, false), pending.detach().get(0),
			"Baseline comes from the first pending change");
	}

	@Test
	void hooksWithoutNotificationsHaveNoBucket() {
		pending.add(5, pathA, true, true);
		Map<Integer, PendingPaths> batch = pending.detach();
		assertFalse(batch.containsKey(0), "A hook with no notifications has no bucket");
		assertEquals(Map.of(pathA, true), batch.get(5), "The notified hook holds the notification");
	}

	@Test
	void hooksOnSamePathKeepIndependentBaselines() {
		pending.add(0, pathA, false, true);
		pending.add(1, pathA, true, true);
		pending.add(0, pathA, true, false);
		Map<Integer, PendingPaths> batch = pending.detach();
		assertFalse(batch.containsKey(0), "Hook 0 saw only a created-then-deleted reference");
		assertEquals(Map.of(pathA, true), batch.get(1),
			"Hook 1 saw a pre-existing reference and needs a deletion call");
	}

	@Test
	void recreatedReference_startsWithFreshBaseline() {
		pending.add(0, pathA, false, true);
		pending.add(0, pathA, true, false);
		assertTrue(pending.isEmpty(), "Transient reference should be discarded");
		pending.add(0, pathA, false, true);
		assertEquals(Map.of(pathA, false), pending.detach().get(0),
			"Recreation starts a fresh baseline");
	}

	@Test
	void detach_clearsPendingSet() {
		pending.add(0, pathA, true, true);
		Map<Integer, PendingPaths> batch = pending.detach();
		assertEquals(Set.of(0), batch.keySet(), "Batch should contain the pending hook");
		assertTrue(pending.isEmpty(), "Detached work should leave the pending set empty");
	}

	@Test
	void detach_preservesFirstSeenPathOrder() {
		pending.add(0, pathB, true, true);
		pending.add(0, pathA, true, true);
		assertEquals(List.of(pathB, pathA), new ArrayList<>(pending.detach().get(0).keySet()),
			"Paths should be in first-seen order");
	}

	@Test
	void detach_ordersHooksByRegistrationIndex() {
		pending.add(5, pathA, true, true);
		pending.add(1, pathB, true, true);
		assertEquals(List.of(1, 5), new ArrayList<>(pending.detach().keySet()),
			"Hooks should be in registration order");
	}

	@Test
	void mergeEarlierRestoresUnrunNotificationsWithEarlierBaseline() {
		pending.add(0, pathA, false, true);
		Map<Integer, PendingPaths> batch = pending.detach();
		pending.add(0, pathA, true, true);
		pending.mergeEarlier(batch);
		assertEquals(Map.of(pathA, false), pending.detach().get(0),
			"The earlier batch baseline should win");
	}

	@Test
	void mergeEarlierIgnoresNotificationsThatAlreadyRan() {
		pending.add(0, pathA, false, true);
		Map<Integer, PendingPaths> batch = pending.detach();
		batch.get(0).remove(pathA);
		pending.mergeEarlier(batch);
		assertTrue(pending.isEmpty(), "A cleared notification should not be restored");
	}

	@Test
	void mergeEarlierKeepsTheEarlierBaselineInBothDirections() {
		PendingHookSet earlierSet = new PendingHookSet();
		earlierSet.add(0, pathA, true, true);
		earlierSet.add(0, pathB, false, true);
		Map<Integer, PendingPaths> earlier = earlierSet.detach();

		pending.add(0, pathA, false, true);
		pending.add(0, pathB, true, true);
		pending.mergeEarlier(earlier);

		assertEquals(Map.of(pathA, true, pathB, false), pending.detach().get(0),
			"The earlier batch baseline wins even when this batch recorded the opposite");
	}

	@Test
	void emptiedBucketIsRemoved() {
		pending.add(0, pathA, false, true);
		pending.add(0, pathA, true, false);
		assertTrue(pending.isEmpty(), "The emptied bucket should leave no hook with pending work");
		pending.add(0, pathA, true, true);
		assertEquals(Map.of(pathA, true), pending.detach().get(0),
			"A later notification should start a fresh bucket");
	}
}
