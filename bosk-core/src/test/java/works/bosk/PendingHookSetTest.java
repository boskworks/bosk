package works.bosk;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

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
		PendingHookSet.PendingHooks hooks = pending.detach().get(pathA);
		assertTrue(hooks.toRun().contains(0), "Hook must be pending");
		assertTrue(hooks.existedAtBaseline(0), "The reference must have existed before the first notification");
	}

	@Test
	void firstBaselineIsKeptAcrossLaterChanges() {
		pending.add(0, pathA, false, true);
		pending.add(0, pathA, true, true);
		PendingHookSet.PendingHooks hooks = pending.detach().get(pathA);
		assertFalse(hooks.existedAtBaseline(0), "The baseline must come from the first pending change");
	}

	@Test
	void nonzeroHookIndexIsStored() {
		pending.add(5, pathA, true, true);
		PendingHookSet.PendingHooks hooks = pending.detach().get(pathA);
		assertFalse(hooks.toRun().contains(0), "Hook index 0 must not be pending");
		assertTrue(hooks.toRun().contains(5), "Hook index 5 must be pending");
		assertTrue(hooks.existedAtBaseline(5), "The baseline for hook index 5 must be present");
	}

	@Test
	void hooksOnSamePathKeepIndependentBaselines() {
		pending.add(0, pathA, false, true);
		pending.add(1, pathA, true, true);
		pending.add(0, pathA, true, false);
		PendingHookSet.PendingHooks hooks = pending.detach().get(pathA);
		assertFalse(hooks.toRun().contains(0), "Hook 0 must have nothing pending after a created-then-deleted reference");
		assertTrue(hooks.toRun().contains(1), "Hook 1 must have a pending deletion call");
		assertTrue(hooks.existedAtBaseline(1), "Hook 1's baseline must be independent of hook 0's");
	}

	@Test
	void recreatedReference_startsWithFreshBaseline() {
		pending.add(0, pathA, false, true);
		pending.add(0, pathA, true, false);
		assertTrue(pending.isEmpty(), "Transient reference must be discarded");
		pending.add(0, pathA, false, true);
		PendingHookSet.PendingHooks hooks = pending.detach().get(pathA);
		assertTrue(hooks.toRun().contains(0), "Recreated reference must be pending again");
		assertFalse(hooks.existedAtBaseline(0), "A recreation must start a fresh baseline");
	}

	@Test
	void detach_clearsPendingSet() {
		pending.add(0, pathA, true, true);
		Map<Path, PendingHookSet.PendingHooks> batch = pending.detach();
		assertEquals(List.of(pathA), new ArrayList<>(batch.keySet()), "Batch must contain the pending path");
		assertTrue(pending.isEmpty(), "Detached work must leave the pending set empty");
	}

	@Test
	void detach_preservesFirstSeenOrder() {
		pending.add(0, pathB, true, true);
		pending.add(0, pathA, true, true);
		Map<Path, PendingHookSet.PendingHooks> batch = pending.detach();
		assertEquals(List.of(pathB, pathA), new ArrayList<>(batch.keySet()), "Paths must be in first-seen order");
	}

	@Test
	void mergeEarlierRestoresUnrunNotificationsWithEarlierBaseline() {
		pending.add(0, pathA, false, true);
		Map<Path, PendingHookSet.PendingHooks> batch = pending.detach();
		pending.add(0, pathA, true, true);
		pending.mergeEarlier(batch);
		PendingHookSet.PendingHooks hooks = pending.detach().get(pathA);
		assertTrue(hooks.toRun().contains(0), "Unrun notification must be restored");
		assertFalse(hooks.existedAtBaseline(0), "The earlier batch's baseline must win");
	}

	@Test
	void mergeEarlierIgnoresNotificationsThatAlreadyRan() {
		pending.add(0, pathA, false, true);
		Map<Path, PendingHookSet.PendingHooks> batch = pending.detach();
		batch.get(pathA).toRun().remove(0);
		batch.get(pathA).setExistedAtBaseline(0, false);
		pending.mergeEarlier(batch);
		assertTrue(pending.isEmpty(), "A cleared notification must not be restored");
	}

	@Test
	void mergeEarlier_earlierPresentBaselineWins() {
		pending.add(0, pathA, true, true);
		Map<Path, PendingHookSet.PendingHooks> batch = pending.detach();
		pending.add(0, pathA, false, true);
		pending.mergeEarlier(batch);
		PendingHookSet.PendingHooks hooks = pending.detach().get(pathA);
		assertTrue(hooks.existedAtBaseline(0), "The earlier batch's baseline must win");
	}
}
