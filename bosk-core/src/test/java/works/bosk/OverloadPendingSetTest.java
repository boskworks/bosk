package works.bosk;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OverloadPendingSetTest {
	final OverloadPendingSet pending = new OverloadPendingSet();
	final Path pathA = Path.just("a");
	final Path pathB = Path.of("b", "c");

	@Test
	void creationThenDeletion_cancels() {
		pending.add(0, pathA, false, true);
		pending.add(0, pathA, true, false);
		assertTrue(pending.isEmpty(), "A created and deleted reference needs no hook call");
		assertEquals(0, pending.size(), "A cancelled notification should not count toward the backlog");
	}

	@Test
	void sizeCountsPendingHookObligations() {
		pending.add(0, pathA, true, true);
		assertEquals(1, pending.size(), "One pending hook should count once");
		pending.add(0, pathA, true, true);
		assertEquals(1, pending.size(), "A duplicate notification for the same hook should not count again");
		pending.add(1, pathA, true, true);
		assertEquals(2, pending.size(), "A second hook should count separately");
		pending.add(5, pathB, true, true);
		assertEquals(3, pending.size(), "A notification on another path should count separately");

		Map<Path, OverloadPendingSet.PendingHooks> batch = pending.detach();
		assertEquals(0, pending.size(), "Detached work should not count toward the pending backlog");
		pending.merge(batch);
		assertEquals(3, pending.size(), "Requeued work should count toward the pending backlog again");
	}

	@Test
	void deletionOfPreExistingReference_remainsPending() {
		pending.add(0, pathA, true, false);
		assertFalse(pending.isEmpty(), "A deleted pre-existing reference needs a deletion call");
		OverloadPendingSet.PendingHooks hooks = pending.detach().get(pathA);
		assertTrue(hooks.toRun().get(0), "Hook should be pending");
		assertTrue(hooks.existedAtBaseline().get(0), "Reference existed before the first notification");
	}

	@Test
	void firstBaselineIsKeptAcrossLaterChanges() {
		pending.add(0, pathA, false, true);
		pending.add(0, pathA, true, true);
		OverloadPendingSet.PendingHooks hooks = pending.detach().get(pathA);
		assertFalse(hooks.existedAtBaseline().get(0), "Baseline comes from the first pending change");
	}

	@Test
	void nonzeroHookIndexStoredAtItsBit() {
		pending.add(5, pathA, true, true);
		OverloadPendingSet.PendingHooks hooks = pending.detach().get(pathA);
		assertFalse(hooks.toRun().get(0), "Hook index 0 should not be set");
		assertTrue(hooks.toRun().get(5), "Hook index 5 should be set");
		assertTrue(hooks.existedAtBaseline().get(5), "Baseline for hook index 5 should be set");
	}

	@Test
	void hooksOnSamePathKeepIndependentBaselines() {
		pending.add(0, pathA, false, true);
		pending.add(1, pathA, true, true);
		pending.add(0, pathA, true, false);
		OverloadPendingSet.PendingHooks hooks = pending.detach().get(pathA);
		assertFalse(hooks.toRun().get(0), "Hook 0 saw only a created-then-deleted reference");
		assertTrue(hooks.toRun().get(1), "Hook 1 saw a pre-existing reference and needs a deletion call");
		assertTrue(hooks.existedAtBaseline().get(1), "Hook 1 baseline is independent of hook 0");
	}

	@Test
	void recreatedReference_startsWithFreshBaseline() {
		pending.add(0, pathA, false, true);
		pending.add(0, pathA, true, false);
		assertTrue(pending.isEmpty(), "Transient reference should be discarded");
		pending.add(0, pathA, false, true);
		OverloadPendingSet.PendingHooks hooks = pending.detach().get(pathA);
		assertTrue(hooks.toRun().get(0), "Recreated reference should be pending again");
		assertFalse(hooks.existedAtBaseline().get(0), "Recreation starts a fresh baseline");
	}

	@Test
	void detach_clearsPendingSet() {
		pending.add(0, pathA, true, true);
		Map<Path, OverloadPendingSet.PendingHooks> batch = pending.detach();
		assertEquals(List.of(pathA), new ArrayList<>(batch.keySet()), "Batch should contain the pending path");
		assertTrue(pending.isEmpty(), "Detached work should leave the pending set empty");
	}

	@Test
	void detach_preservesFirstSeenOrder() {
		pending.add(0, pathB, true, true);
		pending.add(0, pathA, true, true);
		Map<Path, OverloadPendingSet.PendingHooks> batch = pending.detach();
		assertEquals(List.of(pathB, pathA), new ArrayList<>(batch.keySet()), "Paths should be in first-seen order");
	}

	@Test
	void mergeRestoresUnrunNotificationsWithEarlierBaseline() {
		pending.add(0, pathA, false, true);
		Map<Path, OverloadPendingSet.PendingHooks> batch = pending.detach();
		pending.add(0, pathA, true, true);
		pending.merge(batch);
		OverloadPendingSet.PendingHooks hooks = pending.detach().get(pathA);
		assertTrue(hooks.toRun().get(0), "Unrun notification should be restored");
		assertFalse(hooks.existedAtBaseline().get(0), "The earlier batch baseline should win");
	}

	@Test
	void mergeIgnoresNotificationsThatAlreadyRan() {
		pending.add(0, pathA, false, true);
		Map<Path, OverloadPendingSet.PendingHooks> batch = pending.detach();
		batch.get(pathA).toRun().clear(0);
		batch.get(pathA).existedAtBaseline().clear(0);
		pending.merge(batch);
		assertTrue(pending.isEmpty(), "A cleared notification should not be restored");
	}
}
